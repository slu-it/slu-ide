package dev.sluide.gitlog.operations

import dev.sluide.git.GitConflictReason
import dev.sluide.git.GitInProgress
import dev.sluide.git.GitOperation
import dev.sluide.git.GitPendingConflicts
import dev.sluide.git.GitPendingMerge
import dev.sluide.git.GitPendingOperation
import dev.sluide.git.GitPendingPick
import dev.sluide.git.GitPendingRebase
import dev.sluide.git.GitPick
import dev.sluide.git.GitPickResult
import dev.sluide.git.GitResetMode
import dev.sluide.git.GitResult
import dev.sluide.git.GitUncheckedConflict
import dev.sluide.gitlog.data.GitLogWrites
import dev.sluide.gitlog.data.SHORT_HASH
import javafx.scene.control.ButtonType
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

/**
 * Cherry-picks or reverts ([kind]) [commit] (a full hash), a merge commit relative to its first parent if [firstParent].
 * It first reads which ignored files git would overwrite or delete without a word (a local `.env`, an ignored `build/`
 * folder in the way of a file); if there are any, or that cannot be read, the outcome asks first, the files listed in
 * its details. Once [confirmed], it runs if they are among those [shown] (or still cannot be read, as shown); others by
 * then are asked about again. Not while git is in the middle of something, which [GitLogWrites.pick] refuses anyway.
 */
internal data class PickCommit(
    val kind: GitPick,
    val commit: String,
    val firstParent: Boolean = false,
    val confirmed: Boolean = false,
    val shown: List<String>? = null,
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
    ): RefOutcome {
        if (writes.inProgress(root) == null) {
            val ignored = writes.pickOverwrites(root, kind, commit, firstParent)
            val answered =
                confirmed && if (ignored == null) shown == null else shown != null && shown.containsAll(ignored)
            if (!answered && (ignored == null || ignored.isNotEmpty())) {
                val confirmation =
                    RefConfirmation(
                        pickTitle(kind),
                        pickOverwriteQuestion(kind, commit, ignored),
                        pickButton(kind),
                        copy(confirmed = true, shown = ignored),
                        ignored?.joinToString("\n"),
                    )
                return RefOutcome(GitResult(true, ""), null, confirmation = confirmation)
            }
        }
        return pickOutcome(kind, commit, writes.pick(root, kind, commit, firstParent))
    }
}

/**
 * The question before a cherry-pick or revert ([kind]) of [commit] that would overwrite or delete the [ignored] files
 * (listed after the colon), or of which that could not be read (`null`).
 */
internal fun pickOverwriteQuestion(
    kind: GitPick,
    commit: String,
    ignored: List<String>?,
): String {
    val label = short(commit)
    val what = "the ${kind.noun} of $label"
    return when {
        ignored == null -> {
            "Whether $what overwrites or deletes ignored files could not be read.\n${kind.sentence} $label anyway?"
        }

        ignored.size == 1 -> {
            "1 ignored file will be overwritten or deleted by $what; git keeps no copy of it:"
        }

        else -> {
            "${ignored.size} ignored files will be overwritten or deleted by $what; git keeps no copy of them:"
        }
    }
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
            uncheckedConfirmation(
                "Continue ${kind.title}",
                result.files,
                copy(commit = current, confirmed = result.files.toSet()),
            )
        return RefOutcome(GitResult(true, ""), null, confirmation = confirmation)
    }
}

/**
 * The question before committing the conflicted [files] that cannot be checked as they are on disk; [next] runs once
 * confirmed. Marking them resolved (ADR 0107) asks its own [question] with its own [button], and the files are
 * [staged] rather than committed.
 */
internal fun uncheckedConfirmation(
    title: String,
    files: List<GitUncheckedConflict>,
    next: RefOperation,
    question: String = UNCHECKED_QUESTION,
    button: ButtonType = COMMIT_AS_ON_DISK_BUTTON,
    staged: Boolean = false,
) = RefConfirmation(
    title,
    question,
    button,
    next,
    files.joinToString("\n") { "${it.path} \u2014 ${reasonText(it.reason, if (staged) "staged" else "committed")}" },
)

private const val UNCHECKED_QUESTION =
    "These conflicted files cannot be checked for conflict markers. Commit them as they are on disk?"

/** What continuing gave when conflict markers are left in [paths]: a failure, nothing done. */
internal fun markersLeftOutcome(paths: List<String>) =
    RefOutcome(GitResult(false, "Conflict markers are left in: ${paths.joinToString(", ")}"), null)

/**
 * Why a conflicted file is taken as it is on disk, after its path in the confirmation; what is then done with it is
 * [done] ("committed", or "staged" for Mark Resolved).
 */
internal fun reasonText(
    reason: GitConflictReason,
    done: String = "committed",
): String =
    when (reason) {
        GitConflictReason.BINARY -> "binary, the version on disk will be $done"
        GitConflictReason.TOO_LARGE -> "too large to check, the version on disk will be $done"
        GitConflictReason.DELETED_ON_ONE_SIDE -> "deleted on one side, the file on disk will be $done"
        GitConflictReason.DELETED_ON_DISK -> "deleted on disk, the deletion will be $done"
        GitConflictReason.SUBMODULE -> "submodule, the commit checked out in it will be $done"
        GitConflictReason.SYMLINK -> "symbolic link, the link on disk will be $done"
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
 * Unless [confirmed], it first reads what git is in the middle of (a reset is the way out of it, ADR 0042, but abandons
 * or changes it), how many commits would no longer be on any branch, remote branch or tag, for a hard reset how many
 * tracked files have local changes and which untracked or ignored files it would overwrite, and for a mixed reset how
 * many files have staged changes that differ from the file on disk; if anything would be lost, or that cannot be read,
 * the outcome asks first, the overwritten files listed in its details. A soft reset git would refuse (a merge, unmerged
 * paths) fails at once, without asking.
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
            // Refused by git anyway: not asked about first.
            if (mode == GitResetMode.SOFT && writes.softResetRefused(root) == true) {
                return RefOutcome(GitResult(false, SOFT_REFUSED), null)
            }
            val busy = writes.inProgress(root)?.let { busyResetWarning(it, mode) }
            val unreachable = writes.unreachableCount(root, target)
            val hard = mode == GitResetMode.HARD
            val changes = if (hard) writes.localChangeCount(root) else 0
            val overwritten = if (hard) writes.overwrittenFiles(root, target) else emptyList()
            val staged = if (mode == GitResetMode.MIXED) writes.stagedOnlyCount(root) else 0
            val warnings = listOfNotNull(busy) + resetWarnings(unreachable, changes, staged, overwritten)
            if (warnings.isNotEmpty()) {
                val confirmation =
                    RefConfirmation(
                        resetTitle(branch),
                        warnings.joinToString("\n"),
                        RESET_BUTTON,
                        copy(confirmed = true),
                        overwritten?.takeIf { it.isNotEmpty() }?.joinToString("\n"),
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
 * local changes in [changes] files, staged changes that differ from the files on disk in [staged] files, the
 * [overwritten] untracked or ignored files (listed after the sentence's colon); `null` for what could not be read.
 */
internal fun resetWarnings(
    unreachable: Int?,
    changes: Int?,
    staged: Int? = 0,
    overwritten: List<String>? = emptyList(),
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
        when {
            staged == null -> "Whether staged changes will be lost could not be read."
            staged == 1 -> "Staged changes in 1 file that differ from the file on disk will be lost."
            staged > 1 -> "Staged changes in $staged files that differ from the files on disk will be lost."
            else -> null
        },
        when {
            overwritten == null -> "Whether untracked or ignored files will be overwritten could not be read."
            overwritten.size == 1 -> "1 untracked or ignored file will be overwritten by $VERSION:"
            overwritten.size > 1 -> "${overwritten.size} untracked or ignored files will be overwritten by $VERSION:"
            else -> null
        },
    )

/** What a hard reset writes over untracked and ignored files with. */
private const val VERSION = "the commit's version"

/** Why a soft reset is not even tried, as git says it. */
internal const val SOFT_REFUSED =
    "A soft reset is not possible during a merge or while files have conflicts; choose Mixed or Hard."

/**
 * What a reset in [mode] does to what git is in the middle of ([busy]), as git 2.53 does it: it abandons a merge or a
 * single cherry-pick or revert; a sequence, rebase or `git am` stays in progress and continues from where `HEAD` is
 * reset to, the changes of the commit (patch) it stopped at dropped by a mixed or hard reset, kept staged by a soft one.
 */
internal fun busyResetWarning(
    busy: GitInProgress,
    mode: GitResetMode,
): String =
    when (busy) {
        is GitPendingPick -> {
            "A ${busy.kind.noun} of ${short(busy.commit)} is in progress; resetting abandons it."
        }

        is GitPendingRebase -> {
            "A rebase ${stays(mode, "commit")}"
        }

        is GitPendingMerge -> {
            "A merge is in progress; resetting abandons it."
        }

        is GitPendingConflicts -> {
            val files = if (busy.conflicts.size == 1) "1 file has" else "${busy.conflicts.size} files have"
            // A soft reset is refused with unmerged paths before this is asked.
            if (mode == GitResetMode.HARD) {
                "$files conflicts; resetting replaces them with the commit's version."
            } else {
                "$files conflicts; resetting keeps them as they are on disk, no longer marked as conflicts."
            }
        }

        is GitPendingOperation -> {
            when (busy.operation) {
                GitOperation.AM -> "A git am session ${stays(mode, "patch")}"
                GitOperation.CHERRY_PICK_SEQUENCE -> "A cherry-pick sequence ${stays(mode, "commit")}"
                GitOperation.REVERT_SEQUENCE -> "A revert sequence ${stays(mode, "commit")}"
                GitOperation.SEQUENCE -> "A cherry-pick or revert sequence ${stays(mode, "commit")}"
            }
        }
    }

/** How a reset in [mode] leaves a sequence, rebase or `git am` that stopped at a [step] (commit or patch). */
private fun stays(
    mode: GitResetMode,
    step: String,
): String {
    val changes =
        if (mode == GitResetMode.SOFT) {
            "the changes of the $step it stopped at stay staged"
        } else {
            "resetting drops the changes of the $step it stopped at"
        }
    return "is in progress; $changes, the rest stays to be continued from the commit HEAD is reset to."
}

/**
 * The question before aborting the cherry-pick or revert ([kind]) of [commit] in progress from the bar: git takes the
 * files and the index back to before it, so what was done to resolve its conflicts is lost.
 */
internal fun abortQuestion(
    kind: GitPick,
    commit: String,
): String =
    "Abort the ${kind.noun} of ${short(commit)}? Its changes are undone, also what was done to resolve its conflicts."

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
            GitOperation.AM -> "A git am session"
            GitOperation.CHERRY_PICK_SEQUENCE -> "A cherry-pick sequence"
            GitOperation.REVERT_SEQUENCE -> "A revert sequence"
            GitOperation.SEQUENCE -> "A cherry-pick or revert sequence"
        }
    return "$what is in progress; continue or abort it in the terminal."
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
            markersLeftOutcome(result.paths)
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
private fun short(hash: String) = hash.take(SHORT_HASH)
