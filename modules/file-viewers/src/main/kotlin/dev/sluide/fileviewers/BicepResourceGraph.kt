package dev.sluide.fileviewers

import dev.sluide.fileviewers.bicep.BicepGraph

/**
 * The resources and modules of a Bicep file and how they depend on each other, as a Mermaid diagram for the Bicep
 * preview. Only the first resources and dependencies are kept, so that Mermaid can draw them ([note]).
 */
class BicepResourceGraph private constructor(
    /** The graph as a Mermaid `flowchart LR`. */
    val mermaid: String,
    /** Whether the file has no resources or modules, so there is nothing to draw. */
    val isEmpty: Boolean,
    /** A line to show above the diagram when it is not complete, else `null`. */
    val note: String?,
) {
    companion object {
        /** The graph of the Bicep file [text]; best effort, also for text that is no valid Bicep. */
        fun of(text: String): BicepResourceGraph {
            val graph = BicepGraph.parse(text)
            return BicepResourceGraph(graph.toMermaid(), graph.nodes.isEmpty(), graph.note)
        }
    }
}
