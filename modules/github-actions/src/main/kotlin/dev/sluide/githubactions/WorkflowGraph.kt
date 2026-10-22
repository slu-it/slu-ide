package dev.sluide.githubactions

/**
 * A workflow drawn as a Mermaid flowchart: [mermaid] (empty if [isEmpty]), [note] a line shown above it (problems,
 * limits) or null.
 */
class WorkflowGraph(
    val mermaid: String,
    val note: String?,
    val isEmpty: Boolean,
)
