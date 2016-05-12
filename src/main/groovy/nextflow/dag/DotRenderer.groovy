package nextflow.dag

import groovy.transform.CompileStatic
import java.nio.file.Path

/**
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 * @author Mike Smoot <mes@aescon.com>
 */
class DotRenderer implements DagRenderer {

    /**
     * Render the DAG using the Graphviz DOT format
     * to the specified file.
     * See http://www.graphviz.org/content/dot-language
     * for more info.
     */
    @Override
    void renderDocument(DAG dag, Path file) {
        file.text = renderNetwork(dag)
    }

    static String renderNetwork(DAG dag) {
        def result = []
        result << "digraph graphname {"
        dag.edges.each { edge -> result << renderEdge( edge ) }
        result << "}"
        return result.join('\n')
    }

    private static String renderVertex(vertex) {

        List attrs = []

        switch (vertex.type) {
            case DAG.Type.NODE:
                attrs << "shape=point"
                if (vertex.label) {
                    attrs << "label=\"\""
                    attrs << "xlabel=\"$vertex.label\""
                }
                break

            case DAG.Type.ORIGIN:
                attrs << "shape=point"
                attrs << "label=\"\""
                attrs << "fixedsize=true"
                attrs << "width=0.1"
                if( vertex.label ) {
                    attrs << "xlabel=\"$vertex.label\""
                }
                break

            case DAG.Type.OPERATOR:
                attrs << "shape=circle"
                attrs << "label=\"\""
                attrs << "fixedsize=true"
                attrs << "width=0.1"
                if( vertex.label ) {
                    attrs << "xlabel=\"$vertex.label\""
                }
                break

            case DAG.Type.PROCESS:
                if( vertex.label )
                    attrs << "label=\"$vertex.label\""
                break

            default:
                attrs << "shape=none"
                if( vertex.label )
                    attrs << "label=\"$vertex.label\""
        }


        return attrs ? "${vertex.getName()} [${attrs.join(',')}];" : null
    }

    private static String renderEdge(edge) {
        assert edge.from != null && edge.to != null

        String A = renderVertex( edge.from )
        String B = renderVertex( edge.to )

        def result = new StringBuilder()
        if( A ) result << A << '\n'
        if( B ) result << B << '\n'
        result << "${edge.from.name} -> ${edge.to.name}"
        if( edge.label ) {
            result << " [label=\"${edge.label}\"]"
        }
        result << ";\n"
    }

}