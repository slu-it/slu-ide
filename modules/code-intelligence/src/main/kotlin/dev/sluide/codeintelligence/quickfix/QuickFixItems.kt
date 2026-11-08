package dev.sluide.codeintelligence.quickfix

import dev.sluide.codeintelligence.edits.WorkspaceEditResult
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.jsonrpc.messages.Either

/**
 * One entry of the quick fix popup: a code action, or a bare command the server answered with. [disabledReason] is
 * the server's `disabled.reason` for one that cannot be applied now (shown, not choosable).
 */
internal data class QuickFix(
    val title: String,
    val action: CodeAction?,
    val command: Command?,
    val preferred: Boolean,
    val disabledReason: String?,
) {
    val enabled: Boolean get() = disabledReason == null
}

/**
 * The entries of a `textDocument/codeAction` answer in the order shown: preferred ones first, then the others, then
 * the disabled ones, each in the server's order. Entries without a title are left out.
 */
internal fun quickFixesOf(answer: List<Either<Command, CodeAction>?>?): List<QuickFix> {
    val fixes =
        answer.orEmpty().filterNotNull().mapNotNull { entry ->
            if (entry.isLeft) {
                val command = entry.left ?: return@mapNotNull null
                QuickFix(command.title.orEmpty(), null, command, false, null)
            } else {
                val action = entry.right ?: return@mapNotNull null
                val disabled = action.disabled?.let { it.reason?.takeIf(String::isNotBlank) ?: "not available" }
                QuickFix(action.title.orEmpty(), action, action.command, action.isPreferred == true, disabled)
            }
        }
    return fixes
        .filter { it.title.isNotBlank() }
        .sortedBy {
            when {
                !it.enabled -> 2
                it.preferred -> 0
                else -> 1
            }
        }
}

/** Whether [action] must be resolved (`codeAction/resolve`) before it can be applied: it has neither edit nor command. */
internal fun needsResolve(
    action: CodeAction,
    serverResolves: Boolean,
): Boolean = action.edit == null && action.command == null && serverResolves

/**
 * What to say when a quick fix's edit did not apply fully: nothing changed, or which files changed and which failed
 * (a closed file that could not be written after the open ones were edited).
 */
internal fun quickFixFailure(result: WorkspaceEditResult): String {
    val failures = result.failures.joinToString(" ")
    if (result.files.isEmpty()) return "Quick fix not applied: $failures"
    val changed = result.files.joinToString(", ") { it.fileName.toString() }
    return "Quick fix applied to $changed only. $failures"
}
