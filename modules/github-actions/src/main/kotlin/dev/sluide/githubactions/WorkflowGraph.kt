package dev.sluide.githubactions

/**
 * A workflow drawn as a Mermaid flowchart: [mermaid] (empty if [isEmpty]), [note] a line shown above it (problems,
 * limits) or null. [unfetched] remote files are drawn "not fetched" (each counted once); [remoteReferences] counts the
 * remote `uses:` drawn, so that fetching them is worth offering (also to refresh them). [locations] says where the
 * boxes drawn come from, by their Mermaid node or subgraph id; a box without a line, or from a remote file without a
 * web page, has none.
 *
 * [mermaid] lays the inputs boxes out beside their steps but leaves the arrows into the steps, [sideLinks] (from, to),
 * to the page that shows it; [exportMermaid] is the same graph for other renderers, with those arrows as ordinary
 * dotted links.
 */
class WorkflowGraph(
    val mermaid: String,
    val note: String?,
    val isEmpty: Boolean,
    val unfetched: Int = 0,
    val remoteReferences: Int = 0,
    val locations: Map<String, SourceLocation> = emptyMap(),
    val sideLinks: List<Pair<String, String>> = emptyList(),
    val exportMermaid: String = mermaid,
)
