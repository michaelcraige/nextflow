/*
 * Copyright (c) 2013-2015, Centre for Genomic Regulation (CRG).
 * Copyright (c) 2013-2015, Paolo Di Tommaso and the respective authors.
 *
 *   This file is part of 'Nextflow'.
 *
 *   Nextflow is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   Nextflow is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with Nextflow.  If not, see <http://www.gnu.org/licenses/>.
 */

package nextflow.executor
import java.nio.file.Files
import java.nio.file.Path

import groovy.transform.CompileStatic
import groovy.transform.EqualsAndHashCode
import groovy.transform.PackageScope
import groovy.util.logging.Slf4j
import nextflow.exception.ProcessException
import nextflow.file.FileHelper
import nextflow.file.FileHolder
import nextflow.processor.TaskContext
import nextflow.processor.TaskHandler
import nextflow.processor.TaskMonitor
import nextflow.processor.TaskPollingMonitor
import nextflow.processor.TaskRun
import nextflow.processor.TaskStatus
import nextflow.script.ScriptType
import nextflow.util.Duration
import nextflow.util.InputStreamDeserializer
import nextflow.util.KryoHelper
import org.apache.commons.lang.SerializationUtils
import org.apache.ignite.IgniteException
import org.apache.ignite.IgniteLogger
import org.apache.ignite.cluster.ClusterNode
import org.apache.ignite.compute.ComputeJob
import org.apache.ignite.compute.ComputeJobResult
import org.apache.ignite.compute.ComputeLoadBalancer
import org.apache.ignite.compute.ComputeTaskAdapter
import org.apache.ignite.lang.IgniteCallable
import org.apache.ignite.lang.IgniteFuture
import org.apache.ignite.lang.IgniteInClosure
import org.apache.ignite.resources.IgniteInstanceResource
import org.apache.ignite.resources.LoadBalancerResource
import org.apache.ignite.resources.LoggerResource
import org.jetbrains.annotations.Nullable
/**
 * A Nextflow executor based on GridGain services
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@CompileStatic
@ServiceName('ignite')
@SupportedScriptTypes( [ScriptType.SCRIPTLET, ScriptType.GROOVY] )
class IgExecutor extends Executor {

    @PackageScope
    IgConnector connector

    /**
     * Initialize the executor by getting a reference to the Hazelcast connector
     */
    def void init() {
        super.init()
        connector = IgConnector.create(taskMonitor)
    }

    /**
     * Creates the task monitor for this executor
     * @return An instance of {@code TaskMonitor}
     */
    @Override
    protected TaskMonitor createTaskMonitor() {
        TaskPollingMonitor.create(session, name, Duration.of('1s'))
    }


    /**
     *  Creates an handler for the specified task
     */
    @Override
    TaskHandler createTaskHandler(TaskRun task) {

        if( task.type == ScriptType.GROOVY ) {
            IgTaskHandler.createGroovyHandler(task, this)
        }
        else {
            IgTaskHandler.createScriptHandler(task, this)
        }

    }


    TaskPollingMonitor getTaskMonitor() {
        (TaskPollingMonitor)super.getTaskMonitor()
    }


    IgniteFuture call( IgniteCallable command ) {
        final compute = connector.compute().withAsync()
        compute.call(command)
        return compute.future()
    }

    IgniteFuture execute( ComputeJob task ) {
        final compute = connector.compute().withAsync()
        compute.execute( new GgTaskWrapper(task), null)
        compute.future()
    }


    /**
     * An adapter for GridGain compute task
     *
     * link http://atlassian.gridgain.com/wiki/display/GG60/Load+Balancing
     */
    static class GgTaskWrapper extends ComputeTaskAdapter  {

        // Inject load balancer.
        @LoadBalancerResource
        transient ComputeLoadBalancer balancer

        private ComputeJob theJob

        GgTaskWrapper( ComputeJob job ) {
            this.theJob = job
        }

        @Override
        Map<? extends ComputeJob, ClusterNode> map(List<ClusterNode> nodes, @Nullable Object arg) throws IgniteException {

            Map<ComputeJob, ClusterNode> jobUnit = [:]
            jobUnit.put(theJob, balancer.getBalancedNode(theJob, null))
            return jobUnit
        }

        @Override
        Object reduce(List list) throws IgniteException {
            return list.get(0)
        }
    }

}


/**
 * A task handler for GridGain  cluster
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@CompileStatic
class IgTaskHandler extends TaskHandler {

    private IgExecutor executor

    private ScriptType type

    private Path exitFile

    private Path outputFile

    private Path errorFile

    /**
     * The result object for this task
     */
    private IgniteFuture future

    static IgTaskHandler createScriptHandler( TaskRun task, IgExecutor executor ) {
        def handler = new IgTaskHandler(task)
        handler.executor = executor
        handler.type = ScriptType.SCRIPTLET
        handler.exitFile = task.workDir.resolve(TaskRun.CMD_EXIT)
        handler.outputFile = task.workDir.resolve(TaskRun.CMD_OUTFILE)
        handler.errorFile = task.workDir.resolve(TaskRun.CMD_ERRFILE)
        return handler
    }

    static IgTaskHandler createGroovyHandler( TaskRun task, IgExecutor executor ) {
        def handler = new IgTaskHandler(task)
        handler.executor = executor
        handler.type = ScriptType.GROOVY
        return handler
    }

    private IgTaskHandler(TaskRun task) {
        super(task)
    }

    @Override
    void submit() {

        // submit to a gridgain node for execution
        final sessionId = task.processor.session.uniqueId
        final remoteTask = ( type == ScriptType.SCRIPTLET ) ? new IgBashTask(task,sessionId) : new IgClosureTask(task,sessionId)
        future = executor.execute( remoteTask )

        future.listen( { executor.getTaskMonitor().signal(); } as IgniteInClosure )

        // mark as submitted -- transition to STARTED has to be managed by the scheduler
        status = TaskStatus.SUBMITTED
        log.trace "Task $task > Submitted"
    }

    @Override
    boolean checkIfRunning() {
        if( isSubmitted() && future ) {
            log.trace "Task ${task} > RUNNING"
            status = TaskStatus.RUNNING
            return true
        }

        return false
    }

    @Override
    boolean checkIfCompleted() {

        if( isRunning() && (future.isCancelled() || (future.isDone() && (!exitFile || exitFile.lastModified()>0)))  ) {
            status = TaskStatus.COMPLETED

            final result = (ComputeJobResult)future.get()
            if( result.getException() ) {
                task.error = result.getException()
                return true
            }

            if( result.isCancelled() ) {
                task.error = ProcessException.CANCELLED_ERROR
                return true
            }

            // -- the task output depend by the kind of the task executed
            if( isScriptlet() ) {
                task.stdout = outputFile
                task.stderr = errorFile
                task.exitStatus = result.getData() as Integer
            }
            else {
                def data = result.getData() as IgResultData
                task.stdout = data.value
                task.context = new TaskContext( task.processor, data.context )
            }

            log.trace "Task ${task} > DONE"
            return true
        }

        return false
    }

    @Override
    void kill() {
        future?.cancel()
    }


    /**
     * @return Whenever is a shell script task
     */
    boolean isScriptlet() { type == ScriptType.SCRIPTLET }

    /**
     * @return Whenever is a groovy closure task
     */
    boolean isGroovy() { type == ScriptType.GROOVY }

}

/**
 * Models a task executed remotely in a GridGain cluster node
 *
 * @param < T > The type of the value returned by the {@code #call} method
 */
@CompileStatic
abstract class IgBaseTask<T> implements IgniteCallable<T>, ComputeJob {

    @LoggerResource
    private transient IgniteLogger log;

    /**
     * The client session identifier, it is required in order to access to
     * remote class-path
     */
    UUID sessionId

    /**
     * This field is used to transport the class attributes as a unique serialized byte array
     */
    private byte[] payload

    /**
     * Holds the class attributes in this map. Note: is defined as 'transient' because
     * the map content is serialized as a byte[] and saved to the {@code payload} field
     */
    private transient Map<String,Object> attrs = [:]

    /**
     * The local scratch dir where the task is actually executed in the remote node.
     * Note: is declared transient because it is valid only on the remote-side,
     * thus it do not need to be transported
     *
     */
    protected transient Path scratchDir

    /**
     * A temporary where all files are cached. The folder is deleted during instance shut-down
     */
    private static final Path localCacheDir = FileHelper.createLocalDir()

    protected static getLocalCacheDir() { localCacheDir }

    static {
        Runtime.getRuntime().addShutdownHook { localCacheDir.deleteDir() }
    }

    /**
     * Initialize the grid gain task wrapper
     *
     * @param task The task instance to be executed
     * @param The session unique identifier
     */
    protected IgBaseTask( TaskRun task, UUID sessionId ) {

        this.sessionId = sessionId

        attrs.taskId = task.id
        attrs.name = task.name
        attrs.workDir = task.workDir
        attrs.targetDir = task.targetDir
        attrs.inputFiles = [:]
        attrs.outputFiles = []

        // -- The a mapping of input files and target names
        def allFiles = task.getInputFiles().values()
        for( List<FileHolder> entry : allFiles ) {
            if( entry ) for( FileHolder it : entry ) {
                attrs.inputFiles[ it.stageName ] = it.storePath
            }
        }

        // -- the list of expected file names in the scratch dir
        attrs.outputFiles = task.getOutputFilesNames()

        payload = KryoHelper.serialize(attrs)
    }

    /** ONLY FOR TESTING PURPOSE */
    protected IgBaseTask() {}

    /**
     * @return The task unique ID
     */
    protected Object getTaskId() { attrs.taskId }

    /**
     * @return The task descriptive name (only for debugging)
     */
    protected String getName() { attrs.name }

    /**
     * @return The path where result files have to be copied
     */
    protected Path getTargetDir() { (Path)attrs.targetDir }

    /**
     * @return The task working directory i.e. the folder containing the scripts files, but
     * it is not the actual task execution directory
     */
    protected Path getWorkDir() { (Path)attrs.workDir }

    /**
     * @return The a mapping of input files and target names
     */
    Map<String,Path> getInputFiles() { (Map<String,Path>)attrs.inputFiles }

    /**
     * @return the list of expected file names in the scratch dir
     */
    List<String> getOutputFiles() { (List<String>)attrs.outputFiles }

    /**
     * Copies to the task input files to the execution folder, that is {@code scratchDir}
     * folder created when this method is invoked
     *
     */
    protected void stage() {

        if( attrs == null && payload )
            attrs = (Map<String,Object>)KryoHelper.deserialize(payload)

        // create a local scratch dir
        scratchDir = FileHelper.createLocalDir()

        if( !inputFiles )
            return

        // move the input files there
        for( Map.Entry<String,Path> entry : inputFiles.entrySet() ) {
            final fileName = entry.key
            final source = entry.value
            final cached = FileHelper.getLocalCachePath(source,localCacheDir, sessionId)
            final staged = scratchDir.resolve(fileName)
            log?.debug "Task ${getName()} > staging path: '${source}' to: '$staged'"
            Files.createSymbolicLink(staged, cached)
        }
    }


    /**
     * Copy back the task output files from the execution directory in the local node storage
     * to the task {@code targetDir}
     */
    protected void unstage() {
        log?.debug "Unstaging file names: $outputFiles"

        if( !outputFiles )
            return

        // create a bash script that will copy the out file to the working directory
        if( !Files.exists(targetDir) )
            Files.createDirectories(targetDir)

        for( String name : outputFiles ) {
            try {
                copyToTargetDir(name, scratchDir, targetDir)
            }
            catch( IOException e ) {
                log.error("Unable to copy result file: $name to target dir", e)
            }
        }

    }

    /**
     * Copy the file with the specified name from the task execution folder
     * to the {@code targetDir}
     *
     * @param filePattern A file name relative to the {@code scratchDir}.
     *        It can contain globs wildcards
     */
    protected void copyToTargetDir( String filePattern, Path from, Path to ) {

        def type = filePattern.contains('**') ? 'file' : 'any'

        FileHelper.visitFiles( from, filePattern, type: type ) { Path it ->
            final rel = from.relativize(it)
            it.copyTo(to.resolve(rel))
        }
    }


    /**
     * Invoke the task execution. It calls the following methods in this sequence: {@code stage}, {@code execute0} and {@code unstage}
     *
     * @return The {@code execute0} result value
     * @throws ProcessException
     */
    @Override
    final T call() throws Exception {
        try {
            /*
             * stage the input files in the working are`
             */
            stage()

            /*
             * execute the task
             */
            final T result = execute0()

            /*
             * copy back the result files to the shared area
             */
            unstage()

            // return the exit status eventually
            return result
        }
        catch( Exception e ) {
            log.error("Cannot execute task > $name", e)
            throw new ProcessException(e)
        }

    }

    /**
     * Just a synonym for {@code #call}
     *
     * @return The value returned by the task execution
     */
    final Object execute() {
        call()
    }

    /**
     * The actual task executor code provided by the extending subclass
     *
     * @return The value returned by the task execution
     */
    protected abstract T execute0()
}

/**
 * Execute a remote shell task into a remote GridGain cluster node
 */
@CompileStatic
class IgBashTask extends IgBaseTask<Integer>  {

    private static final long serialVersionUID = - 5552939711667527410L

    @LoggerResource
    private transient IgniteLogger log;

    @IgniteInstanceResource
    private transient IgClassLoaderProvider provider

    private transient Process process

    /**
     * The command line to be executed
     */
    List<String> shell

    /**
     * The name of the container to run
     */
    String container

    /**
     * Whenever the process run an *executable* container
     */
    boolean executable

    Map environment

    Object stdin

    String script

    IgBashTask( TaskRun task, UUID sessionId ) {
        super(task, sessionId)
        this.stdin = task.stdin
        this.container = task.container
        this.executable = task.isContainerExecutable()
        // note: create a copy of the process environment to avoid concurrent
        // process executions override each others
        this.environment = new HashMap( task.processor.getProcessEnvironment() )
        this.environment.putAll( task.getInputEnvironment() )
        this.shell = new ArrayList<>(task.config.getShell())
        this.script = task.script
    }

    protected void unstage() {
        super.unstage()
        // copy the 'exit' file and 'output' file
        copyFromScratchToWorkDir(TaskRun.CMD_EXIT)
        copyFromScratchToWorkDir(TaskRun.CMD_OUTFILE)
        copyFromScratchToWorkDir(TaskRun.CMD_ERRFILE, true)
        copyFromScratchToWorkDir(TaskRun.CMD_TRACE, true)
    }


    private void copyFromScratchToWorkDir( String name, boolean ignoreError = false ) {
        try {
            Files.copy(scratchDir.resolve(name), workDir.resolve(name))
        }
        catch( Exception e ) {
            if( !ignoreError )
                log.debug "Unable to copy file: '$name' from: '$scratchDir' to: '$workDir'"
        }
    }


    @Override
    protected Integer execute0() throws IgniteException {

        def session = provider.getSessionFor(sessionId)
        if( log.isTraceEnabled() )
            log.trace "Session config: ${session.config}"

        def wrapper = new BashWrapperBuilder(
                shell: shell,
                input: stdin,
                script: script,
                workDir: scratchDir,    // <-- important: the scratch folder is used as the 'workDir'
                targetDir: targetDir,
                container: container,
                environment: environment,
                dockerMount: localCacheDir,
                dockerConfig: session?.config?.docker,
                statsEnabled: session.statsEnabled,
                executable: executable
        )
        shell.add( wrapper.build().toString() )

        log.debug "Running task > name: ${name} - workdir: ${scratchDir.toFile()}"
        ProcessBuilder builder = new ProcessBuilder()
                .directory(scratchDir.toFile())
                .command(shell)
                .redirectErrorStream(true)

        // launch and wait
        process = builder.start()
        def result = process.waitFor()

        // make sure to destroy the process and close the streams
        try { process.destroy() }
        catch( Throwable e ) { }

        log.debug "Completed task > $name - exitStatus: $result"
        // return the exit value
        return result
    }

    @Override
    void cancel() {
        if( process ) {
            log.debug "Cancelling process for task > $name"
            process.destroy()
        }
        else {
            log.debug "No process to cancel for task > $name"
        }

    }


    String toString() {
        "${getClass().simpleName}[taskId: $taskId; name: $name; workDir: $workDir; scratchDir: ${scratchDir}]"
    }

}

/**
 * Execute a groovy closure task in a remote GridGain node
 */
@CompileStatic
class IgClosureTask extends IgBaseTask<IgResultData> {

    private static final long serialVersionUID = 5515528753549263068L

    @LoggerResource
    private transient IgniteLogger log

    @IgniteInstanceResource
    private transient IgClassLoaderProvider provider

    /**
     * The task closure serialized as a byte array
     */
    final byte[] codeObj

    /**
     * The task delegate context serialized as a byte array
     */
    final byte[] delegateObj


    IgClosureTask( TaskRun task, UUID sessionId ) {
        super(task,sessionId)
        this.codeObj = SerializationUtils.serialize(task.code.dehydrate())
        this.delegateObj = task.context.dehydrate()
    }

    @Override
    protected IgResultData execute0() throws IgniteException {
        log.debug "Running closure for task > ${name}"

        def loader = provider.getClassLoaderFor(sessionId)
        def delegate = TaskContext.rehydrate(delegateObj,loader)
        Closure closure = (Closure)InputStreamDeserializer.deserialize(codeObj,loader)
        Object result = closure.rehydrate(delegate, delegate.getScript(), delegate.getScript()).call()
        return new IgResultData(value: result, context: delegate?.getHolder())

    }


    @Override
    void cancel() {

    }

}

/**
 * Models the result of a remote closure task execution
 */
@CompileStatic
@EqualsAndHashCode
class IgResultData implements Serializable {

    private static final long serialVersionUID = - 7200781198107958188L ;

    /**
     * The closure returned value serialized as a byte array
     */
    private byte[] fValueObj

    /**
     * The closure execution context serialized as a byte array
     */
    private byte[] fContextObj

    transient Object value

    transient Map context

    Throwable error

    def getValue() {
        if( !value && fValueObj != null ) {
            value = KryoHelper.deserialize(fValueObj)
        }
        return value
    }

    void setValue( obj ) {
        this.value = obj
        this.fValueObj = KryoHelper.serialize(obj)
    }

    Map getContext() {
        if( context == null && fContextObj != null ) {
            context = (Map)KryoHelper.deserialize(fContextObj)
        }
        return context
    }

    void setContext( Map ctx ) {
        this.context = ctx
        this.fContextObj = KryoHelper.serialize(ctx)
    }

}
