package dev.sluide.gitlog

import dev.sluide.git.GitConflictReason
import dev.sluide.git.GitOperation
import dev.sluide.git.GitPendingPick
import dev.sluide.git.GitPick
import dev.sluide.git.GitPickResult
import dev.sluide.git.GitResetMode
import dev.sluide.git.GitResult
import dev.sluide.git.GitUncheckedConflict
import java.nio.file.Path

/**
 * A cherry-pick or revert ([kind]) of [commit] (a full hash) that git stopped and left for the user (ADR 0042), with
 * the [conflicts] (paths; empty if it stopped for another reason, e.g. a hook) and git's [message].
 */
internal data class PickStop(
    val kind: GitPick,
    val commit: String,
    val conflicts: List<String>,
    val message: String,
)

/** "Cherry-Pick", "Revert": in titles and buttons. */
internal val GitPick.title: String
    get() =
        when (this) {
            GitPick.CHERRY_PICK -> "Cherry-Pick"
            GitPick.REVERT -> "Revert"
        }

/** "cherry-pick", "revert": in a sentence. */
internal val GitPick.noun: String get() = command

/** "Cherry-pick", "Revert": at the start of a sentence. */
internal val GitPick.sentence: String get() = noun.replaceFirstChar(Char::uppercaseChar)

/** Cherry-picks or reverts ([kind]) [commit] (a full hash), a merge commit relative to its first parent if [firstParent]. */
internal data class PickCommit(
    val kind: GitPick,
    val commit: String,
    val firstParent: Boolean = false,
) : RefOperation {
    private val label get() = short(commit)

    override val progress
        get() =
            when (kind) {
                GitPick.CHERRY_PICK -> "Cherry-picking $label…"
                GitPick.REVERT -> "Reverting $label…"
            }
    override val errorTitle get() = "${kind.title} Failed"
    override val failure
        get() =
            when (kind) {
                GitPick.CHERRY_PICK -> "$label could not be cherry-picked."
                GitPick.REVERT -> "$label could not be reverted."
            }
    override val fileChange get() = kind.title

    override fun run(
        writes: GitLogWrites,
        root: Path,
    ) = pickOutcome(kind, commit, writes.pick(root, kind, commit, firstParent))
}

/**
 * Continues the cherry-pick or revert ([kind]) in progress, [commit] as the bar read it last (it is read again when it
 * runs): stages the files that were unmerged, unless conflict markers are left in them, and commits. Conflicted files
 * that cannot be checked for markers (binary, deleted on one side, submodules) are committed as they are on disk only
 * once confirmed, as they were shown ([confirmed]); if they are others by then, the user is asked again.
 */
internal data class ContinuePick(
    val kind: GitPick,
    val commit: String,
    val confirmed: Set<GitUncheckedConflict> = emptySet(),
) : RefOperation {
    override val progress get() = "Continuing the ${kind.noun} of ${short(commit)}…"
    override val errorTitle get() = "Continue Failed"
    override val failure get() = "The ${kind.noun} of ${short(commit)} could not be continued."

    // It stages the files as they are on disk: unsaved resolutions matter.
    override val fileChange get() = "Continue"

    override fun run(
        writes: GitLogWrites,
        root: Path,
    ): RefOutcome {
        val current = currentCommit(writes, root, kind) ?: commit
        val result = writes.continuePick(root, kind, confirmed)
        if (result !is GitPickResult.Unchecked) return pickOutcome(kind, current, result)
        val confirmation =
            RefConfirmation(
                "Continue ${kind.title}",
                "These conflicted files cannot be checked for conflict markers. Commit them as they are on disk?",
                COMMIT_AS_ON_DISK_BUTTON,
                copy(commit = current, confirmed = result.files.toSet()),
                result.files.joinToString("\n") { "${it.path} \u2014 ${reasonText(it.reason)}" },
            )
        return RefOutcome(GitResult(true, ""), null, confirmation = confirmation)
    }
}

/** Why a conflicted file is committed as it is on disk, after its path in the confirmation. */
internal fun reasonText(reason: GitConflictReason): String =
    when (reason) {
        GitConflictReason.BINARY -> "binary, the version on disk will be committed"
        GitConflictReason.TOO_LARGE -> "too large to check, the version on disk will be committed"
        GitConflictReason.DELETED_ON_ONE_SIDE -> "deleted on one side, the file on disk will be committed"
        GitConflictReason.DELETED_ON_DISK -> "deleted on disk, the deletion will be committed"
        GitConflictReason.SUBMODULE -> "submodule, the commit checked out in it will be committed"
        GitConflictReason.SYMLINK -> "symbolic link, the link on disk will be committed"
    }

/** The commit of the cherry-pick or revert ([kind]) in progress, read again; `null` if there is none of that kind. */
private fun currentCommit(
    writes: GitLogWrites,
    root: Path,
    kind: GitPick,
): String? = (writes.inProgress(root) as? GitPendingPick)?.takeIf { it.kind == kind }?.commit

/**
 * Aborts the cherry-pick or revert ([kind]) in progress, [commit] as the bar or the stop read it (read again when it
 * runs).
 */
internal data class AbortPick(
    val kind: GitPick,
    val commit: String,
) : RefOperation {
    override val progress get() = "Aborting the ${kind.noun} of ${short(commit)}…"
    override val errorTitle get() = "Abort Failed"
    override val failure get() = "The ${kind.noun} of ${short(commit)} could not be aborted."
    override val fileChange get() = "Abort"

    override fun run(
        writes: GitLogWrites,
        root: Path,
    ): RefOutcome {
        val current = currentCommit(writes, root, kind) ?: commit
        val result = writes.abortPick(root, kind)
        return RefOutcome(result, if (result.success) "Aborted the ${kind.noun} of ${short(current)}." else null)
    }
}

/**
 * Resets the current [branch] (its short name; `null` for a detached `HEAD`) to [target] (a full hash) in [mode].
 * Unless [confirmed], it first reads how many commits would no longer be on any branch, remote branch or tag and, for
 * a hard reset, how many tracked files have local changes; if any would be lost, or that cannot be read, the outcome
 * asks first.
 */
internal data class ResetBranch(
    val target: String,
    val mode: GitResetMode,
    val branch: String?,
    val confirmed: Boolean = false,
) : RefOperation {
    private val name get() = branch ?: "HEAD"

    override val progress get() = "Resetting $name to ${short(target)}…"
    override val errorTitle get() = "Reset Failed"
    override val failure get() = "$name could not be reset to ${short(target)}."

    // Soft and mixed resets keep the files.
    override val fileChange get() = "Reset".takeIf { mode == GitResetMode.HARD }

    override fun run(
        writes: GitLogWrites,
        root: Path,
    ): RefOutcome {
        if (!confirmed) {
            val unreachable = writes.unreachableCount(root, target)
            val changes = if (mode == GitResetMode.HARD) writes.localChangeCount(root) else 0
            val warnings = resetWarnings(unreachable, changes)
            if (warnings.isNotEmpty()) {
                val confirmation =
                    RefConfirmation(
                        resetTitle(branch),
                        warnings.joinToString("\n"),
                        RESET_BUTTON,
                        copy(confirmed = true),
                    )
                return RefOutcome(GitResult(true, ""), null, confirmation = confirmation)
            }
        }
        val previous = writes.headCommit(root)
        val result = writes.reset(root, target, mode)
        val was = previous?.let { "; it was at ${short(it)}" }.orEmpty()
        return RefOutcome(
            result,
            if (result.success) "Reset $name to ${short(target)} (${mode.name.lowercase()})$was." else null,
        )
    }
}

/**
 * What a reset would lose, one sentence each: [unreachable] commits that would no longer be on any branch or tag,
 * local changes in [changes] files; `null` for what could not be read.
 */
internal fun resetWarnings(
    unreachable: Int?,
    changes: Int?,
): List<String> =
    listOfNotNull(
        when {
            unreachable == null -> {
                "Whether commits will no longer be on any branch or tag could not be read."
            }

            unreachable == 1 -> {
                "1 commit will no longer be on any branch or tag; it stays reachable through the reflog for a while."
            }

            unreachable > 1 -> {
                "$unreachable commits will no longer be on any branch or tag; they stay reachable through the reflog " +
                    "for a while."
            }

            else -> {
                null
            }
        },
        when {
            changes == null -> "Whether local changes will be lost could not be read."
            changes == 1 -> "Local changes in 1 file will be lost."
            changes > 1 -> "Local changes in $changes files will be lost."
            else -> null
        },
    )

/** The title of the reset dialog and its confirmation: "Reset Current Branch", or "Reset HEAD" when detached. */
internal fun resetTitle(branch: String?) = if (branch != null) "Reset Current Branch" else "Reset HEAD"

/** What the reset dialog does: "Reset branch 'main' (at 5e6f7a8b) to 1a2b3c4d", "Reset detached HEAD (at …) to …". */
internal fun resetDescription(
    branch: String?,
    head: String,
    target: String,
): String {
    val what = if (branch != null) "branch '$branch'" else "detached HEAD"
    return "Reset $what (at ${short(head)}) to ${short(target)}"
}

/** The question before cherry-picking or reverting ([kind]) the merge commit [commit] relative to its first parent. */
internal fun mergeQuestion(
    kind: GitPick,
    commit: String,
): String =
    "${short(commit)} is a merge commit. ${kind.sentence} the changes it brought in relative to its first parent?"

/** What the bar above the table says about a cherry-pick or revert in progress. */
internal fun pendingText(pending: GitPendingPick): String {
    val state =
        when (pending.conflicts.size) {
            0 -> "conflicts resolved."
            1 -> "1 file has conflicts."
            else -> "${pending.conflicts.size} files have conflicts."
        }
    return "${pending.kind.sentence} of ${short(pending.commit)} is in progress: $state"
}

/** What the bar above the table says about an [operation] in progress that Thoreau leaves to the terminal. */
internal fun operationText(operation: GitOperation): String {
    val what =
        when (operation) {
            GitOperation.MERGE -> "A merge"
            GitOperation.REBASE -> "A rebase"
            GitOperation.AM -> "A git am session"
            GitOperation.CHERRY_PICK_SEQUENCE -> "A cherry-pick sequence"
            GitOperation.REVERT_SEQUENCE -> "A revert sequence"
            GitOperation.SEQUENCE -> "A cherry-pick or revert sequence"
        }
    val verb = if (operation == GitOperation.MERGE) "finish" else "continue"
    return "$what is in progress; $verb or abort it in the terminal."
}

/** The outcome of a cherry-pick or revert ([kind]) of [commit], or of continuing one, that gave [result]. */
private fun pickOutcome(
    kind: GitPick,
    commit: String,
    result: GitPickResult,
): RefOutcome {
    val label = short(commit)
    return when (result) {
        is GitPickResult.Done -> {
            val made = result.commit?.let(::short)
            val done =
                when (kind) {
                    GitPick.CHERRY_PICK -> {
                        if (made != null) "Cherry-picked $label as $made." else "Cherry-picked $label."
                    }

                    GitPick.REVERT -> {
                        if (made != null) "Reverted $label in $made." else "Reverted $label."
                    }
                }
            RefOutcome(GitResult(true, ""), done)
        }

        is GitPickResult.Empty -> {
            val done =
                when (kind) {
                    GitPick.CHERRY_PICK -> {
                        "Nothing to cherry-pick: the changes of $label are already on ${result.branch ?: "HEAD"}."
                    }

                    GitPick.REVERT -> {
                        "Nothing to revert: the changes of $label are already undone."
                    }
                }
            RefOutcome(GitResult(true, ""), done)
        }

        is GitPickResult.Stopped -> {
            val done =
                if (result.conflicts.isEmpty()) {
                    "${kind.sentence} of $label stopped."
                } else {
                    "${kind.sentence} of $label stopped with conflicts."
                }
            RefOutcome(
                GitResult(true, result.message),
                done,
                stopped = PickStop(kind, commit, result.conflicts, result.message),
            )
        }

        is GitPickResult.MarkersLeft -> {
            RefOutcome(GitResult(false, "Conflict markers are left in: ${result.paths.joinToString(", ")}"), null)
        }

        // Asked about by ContinuePick before this.
        is GitPickResult.Unchecked -> {
            RefOutcome(GitResult(false, "Conflicted files cannot be checked for conflict markers."), null)
        }

        is GitPickResult.Failed -> {
            RefOutcome(GitResult(false, result.message), null)
        }
    }
}

/** [hash] shortened as the log shows hashes. */
private fun short(hash: String) = hash.take(ChangedFilesPane.SHORT_HASH)
