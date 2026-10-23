package dev.sluide.githubactions

/**
 * A workflow drawn as a Mermaid flowchart: [mermaid] (empty if [isEmpty]), [note] a line shown above it (problems,
 * limits) or null. [unfetched] remote files are drawn "not fetched" (each counted once); [remoteReferences] counts the
 * remote `uses:` drawn, so that fetching them is worth offering (also to refresh them).
 */
class WorkflowGraph(
    val mermaid: String,
    val note: String?,
    val isEmpty: Boolean,
    val unfetched: Int = 0,
    val remoteReferences: Int = 0,
)
