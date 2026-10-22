package dev.sluide.githubactions

/**
 * Turns a workflow file's text into the bundled parser's JSON, schema 1. May throw (e.g. timeout); [WorkflowGraphs.of]
 * turns that into a note.
 */
fun interface WorkflowParser {
    fun parse(
        fileName: String,
        text: String,
    ): String
}
