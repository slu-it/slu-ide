package dev.sluide.gitlog.operations

import dev.sluide.git.GitChangedFile
import dev.sluide.git.GitResult
import dev.sluide.gitlog.data.GitLogWrites
import dev.sluide.gitlog.ui.SHORT_HASH
import java.nio.file.Path

/**
 * Commits the checked tracked changes [files] and [untracked] files with [message], or amends `HEAD` with them if
 * [amend] (ADR 0048). The files are taken as they are on disk, so unsaved editors are asked about first; cancellable,
 * for slow hooks or a GPG pinentry. Cancelled, the header says so and no dialog is shown; nothing was committed then
 * ([GitResult.changed] is `false`). With [push] (Commit and Push), the Push dialog opens once the commit is made
 * (ADR 0049).
 */
internal data class CommitChanges(
    val message: String,
    val files: List<GitChangedFile>,
    val untracked: List<String>,
    val amend: Boolean,
    val push: Boolean = false,
) : RefOperation {
    override val progress get() = if (amend) "Amending…" else "Committing…"
    override val errorTitle get() = "Commit Failed"
    override val failure get() = if (amend) "The commit could not be amended." else "The commit could not be made."
    override val fileChange get() = "Commit"
    override val cancellable get() = true

    /** The paths this commit takes: the files' (new) paths and the untracked ones. */
    val paths: Set<String> get() = files.mapTo(LinkedHashSet()) { it.path } + untracked

    override fun run(
        writes: GitLogWrites,
        root: Path,
    ) = run(writes, root, RunContext())

    override fun run(
        writes: GitLogWrites,
        root: Path,
        context: RunContext,
    ): RefOutcome {
        val committed = writes.commit(root, message, files, untracked, amend, context.cancellation)
        val result = committed.result
        if (!result.success && context.cancellation.cancelled) {
            return RefOutcome(GitResult(true, "", changed = false), CANCELLED, cancelled = true)
        }
        if (!result.success) return RefOutcome(result, null)
        val made = committed.commit?.let { " ${it.take(SHORT_HASH)}" }.orEmpty()
        val verb = if (amend) "Amended" else "Committed"
        val subject = commitSubject(message)
        // No second full stop after a subject that ends with one (or is cut off).
        val end = if (subject.endsWith('.') || subject.endsWith('…')) "" else "."
        return RefOutcome(result, "$verb$made: $subject$end")
    }

    private companion object {
        const val CANCELLED = "Commit cancelled."
    }
}

/**
 * The subject of a commit [message] for the header: its first line that is not blank (git drops leading blank lines),
 * trimmed, at most [SUBJECT_LENGTH] characters, longer ones cut with an ellipsis.
 */
internal fun commitSubject(message: String): String {
    val line =
        message
            .lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            .orEmpty()
    return if (line.length <= SUBJECT_LENGTH) line else line.take(SUBJECT_LENGTH - 1).trimEnd() + "…"
}

/** How long a subject in the header may be: about what fits next to the tabs. */
private const val SUBJECT_LENGTH = 50
