package dev.sluide.githubactions

import dev.sluide.githubactions.mermaid.WorkflowDrawing
import dev.sluide.githubactions.model.WorkflowJson

object WorkflowGraphs {
    /** Parses [text] of [fileName] with [parser] and draws it; never throws for bad input or a failing parser (note instead). */
    fun of(
        fileName: String,
        text: String,
        parser: WorkflowParser,
    ): WorkflowGraph {
        val json =
            try {
                parser.parse(fileName, text)
            } catch (e: Exception) {
                if (e is InterruptedException) Thread.currentThread().interrupt()
                return WorkflowGraph("", "The workflow parser failed: ${e.message ?: e.javaClass.simpleName}", true)
            }
        return when (val decoded = WorkflowJson.decode(json)) {
            is WorkflowJson.Result.Unsupported -> WorkflowGraph("", decoded.reason, true)
            is WorkflowJson.Result.Decoded -> WorkflowDrawing.draw(decoded.workflow)
        }
    }
}
