package dev.sluide.gitlog.operations

import dev.sluide.git.GitResult
import dev.sluide.git.GitRewordCheck
import dev.sluide.git.GitRewordResult
import dev.sluide.gitlog.data.GitLogWrites
import dev.sluide.gitlog.data.SHORT_HASH
import java.nio.file.Path

/**
 * What the Edit Commit Message dialog needs (ADR 0100): the [message] of [commit] (a full hash) on [branch], the
 * [otherBranches] that keep the old commit and where it was [pushedTo] (`null`: nowhere).
 */
internal data class RewordPrompt(
    val branch: String,
    val commit: String,
    val message: String,
    val otherBranches: List<String>,
    val pushedTo: String?,
)

/**
 * Reads whether the message of [commit] (a full hash) on the current [branch] can be changed. A refusal is the header's
 * text; otherwise the outcome carries the [RewordPrompt] for the dialog. It reads no files.
 */
internal data class CheckReword(
    val branch: String,
    val commit: String,
) : RefOperation {
    override val progress get() = "Reading the message of ${commit.take(SHORT_HASH)}…"
    override val errorTitle get() = "Edit Commit Message Failed"
    override val failure get() = "The message of ${commit.take(SHORT_HASH)} could not be read."
    override val fileChange: String? get() = null

    override fun run(
        writes: GitLogWrites,
        root: Path,
    ): RefOutcome =
        when (val check = writes.rewordCheck(root, branch, commit)) {
            is GitRewordCheck.Refused -> {
                RefOutcome(GitResult(true, ""), check.reason)
            }

            is GitRewordCheck.Allowed -> {
                RefOutcome(
                    GitResult(true, ""),
                    null,
                    reword = RewordPrompt(branch, commit, check.message, check.otherBranches, check.pushedTo),
                )
            }
        }
}

/** Changes the message of [commit] (a full hash) on the current [branch] to [message] (ADR 0100). */
internal data class RewordCommit(
    val branch: String,
    val commit: String,
    val message: String,
) : RefOperation {
    private val short get() = commit.take(SHORT_HASH)
    override val progress get() = "Changing the message of $short…"
    override val errorTitle get() = "Edit Commit Message Failed"
    override val failure get() = "The message of $short could not be changed."

    // It moves the branch and neither changes nor reads files.
    override val fileChange: String? get() = null

    override fun run(
        writes: GitLogWrites,
        root: Path,
    ): RefOutcome =
        when (val result = writes.reword(root, branch, commit, message)) {
            is GitRewordResult.Done -> RefOutcome(GitResult(true, ""), "Changed the message of $short.")
            GitRewordResult.Unchanged -> RefOutcome(GitResult(true, ""), "The message of $short is unchanged.")
            is GitRewordResult.Failed -> RefOutcome(GitResult(false, result.message), null)
        }
}

/** The notes below the message: which branches keep the old commit, and that a pushed one needs a force push. */
internal fun rewordNotes(
    otherBranches: List<String>,
    pushedTo: String?,
): List<String> =
    listOfNotNull(
        otherBranches.takeIf { it.isNotEmpty() }?.let {
            "${listBranches(it)} ${if (it.size == 1) "keeps" else "keep"} the old commit."
        },
        pushedTo?.let { "Already pushed to $it; a force push will be needed afterwards." },
    )

/**
 * Whether [edited] may be taken as a new message for [original]: it is not blank and differs from it, not counting
 * trailing whitespace of lines and blank lines at both ends.
 */
internal fun canChangeMessage(
    original: String,
    edited: String,
): Boolean = edited.isNotBlank() && tidy(edited) != tidy(original)

private fun tidy(message: String) = message.lines().joinToString("\n") { it.trimEnd() }.trim('\n')

/** "a", "a and b", "a, b and c"; more than three: "a, b, c and 2 more". */
private fun listBranches(names: List<String>): String =
    when {
        names.size == 1 -> names.single()
        names.size <= MAX_NAMED -> "${names.dropLast(1).joinToString(", ")} and ${names.last()}"
        else -> "${names.take(MAX_NAMED).joinToString(", ")} and ${names.size - MAX_NAMED} more"
    }

private const val MAX_NAMED = 3
