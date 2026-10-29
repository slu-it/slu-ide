package dev.sluide.gitlog.operations

import dev.sluide.git.GitPendingConflicts
import dev.sluide.git.GitResolveResult
import dev.sluide.git.GitResult
import dev.sluide.git.GitStash
import dev.sluide.git.GitStashResult
import dev.sluide.git.GitStashSources
import dev.sluide.git.GitUncheckedConflict
import dev.sluide.gitlog.data.GitLogWrites
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import java.nio.file.Path

/**
 * The Commit tab's checked files to stash (ADR 0107): [count] files, given to git as the [paths] (a renamed file with
 * both of its paths), which [includeUntracked] when some are unversioned.
 */
internal data class CheckedFiles(
    val paths: List<String>,
    val includeUntracked: Boolean,
    val count: Int = paths.size,
)

/**
 * What the Stash Changes dialog asks (ADR 0107): to stash the local changes of [branch] (short name, `null` when
 * detached), or only the [checked] files of the Commit tab; then it does not ask about untracked files.
 */
internal data class StashPrompt(
    val branch: String?,
    val checked: CheckedFiles? = null,
)

/** The header of the Stash Changes dialog: "Stash local changes of 'feature'", "Stash 3 checked files of HEAD". */
internal fun stashHeader(prompt: StashPrompt): String {
    val of = prompt.branch?.let { "'$it'" } ?: "HEAD"
    val count = prompt.checked?.count ?: return "Stash local changes of $of"
    return "Stash $count checked ${if (count == 1) "file" else "files"} of $of"
}

/**
 * Applying [stash] (popping it if [pop]) gave conflicts in [paths] (ADR 0107), which keep git's markers; [kept] if the
 * stash is still in the list.
 */
internal data class StashConflict(
    val stash: GitStash,
    val pop: Boolean,
    val paths: List<String>,
    val kept: Boolean,
)

/**
 * Reads the current branch and what git is in the middle of, then asks with the Stash Changes dialog
 * ([RefOutcome.stashPrompt]) for the local changes, or only the [checked] files; the header says why if nothing can be
 * stashed now.
 */
internal data class PrepareStash(
    val checked: CheckedFiles? = null,
) : RefOperation {
    override val progress get() = "Reading the repository…"
    override val errorTitle get() = STASH_FAILED
    override val failure get() = "The local changes could not be stashed."
    override val fileChange get() = null

    override fun run(
        writes: GitLogWrites,
        root: Path,
    ): RefOutcome = stashPrompt(writes.stashSources(root), checked)
}

/** The outcome of [PrepareStash] with [sources]: the header says what keeps it from starting, else the dialog asks. */
internal fun stashPrompt(
    sources: GitStashSources,
    checked: CheckedFiles?,
): RefOutcome =
    sources.busy?.let { RefOutcome(GitResult(true, ""), it) }
        ?: RefOutcome(GitResult(true, ""), null, stashPrompt = StashPrompt(sources.branch, checked))

/**
 * Stashes the local changes with [message] (`null` for git's own), the untracked files too if [includeUntracked], only
 * the [paths] if given (ADR 0107).
 */
internal data class StashChanges(
    val message: String?,
    val includeUntracked: Boolean,
    val paths: List<String>? = null,
) : RefOperation {
    override val progress get() = "Stashing local changes…"
    override val errorTitle get() = STASH_FAILED
    override val failure get() = "The local changes could not be stashed."

    // It takes the files as they are on disk and resets them.
    override val fileChange get() = "Stash"

    override fun run(
        writes: GitLogWrites,
        root: Path,
    ): RefOutcome =
        when (val result = writes.pushStash(root, message, includeUntracked, paths)) {
            GitStashResult.Done -> RefOutcome(GitResult(true, ""), "Stashed local changes as stash@{0}.")
            GitStashResult.NothingToStash -> RefOutcome(GitResult(true, ""), "No local changes to stash.")
            else -> stashFailure(result)
        }
}

/** Pops [stash] (ADR 0107), or applies it, keeping it in the list, unless [pop]. */
internal data class PopStash(
    val stash: GitStash,
    val pop: Boolean = true,
) : RefOperation {
    override val progress get() = "${if (pop) "Popping" else "Applying"} ${stash.name}…"
    override val errorTitle get() = if (pop) "Pop Failed" else "Apply Failed"
    override val failure get() = "${stash.name} could not be ${if (pop) "popped" else "applied"}."

    // It changes the files.
    override val fileChange get() = if (pop) "Pop" else "Apply"

    override fun run(
        writes: GitLogWrites,
        root: Path,
    ): RefOutcome =
        when (val result = if (pop) writes.popStash(root, stash) else writes.applyStash(root, stash)) {
            GitStashResult.Done -> {
                RefOutcome(GitResult(true, ""), "${if (pop) "Popped" else "Applied"} ${stash.name} (${stash.message}).")
            }

            is GitStashResult.Conflicts -> {
                val conflict = StashConflict(stash, pop, result.paths, result.kept)
                RefOutcome(GitResult(true, ""), stashConflictText(conflict), stashConflicts = conflict)
            }

            // Done in part: the error dialog has the header's text above git's message.
            is GitStashResult.Partly -> {
                RefOutcome(GitResult(false, result.message, changed = true), partlyText(stash, pop, result))
            }

            else -> {
                stashFailure(result)
            }
        }
}

/**
 * What the header and the error dialog say after a pop or apply of [stash] that failed after it changed files
 * ([partly]): what of its tracked changes and untracked files was done, then whether the stash is kept: "Popped
 * stash@{0} only partly: its changes to tracked files were not applied; untracked u1.txt was restored. The stash is
 * kept."
 */
internal fun partlyText(
    stash: GitStash,
    pop: Boolean,
    partly: GitStashResult.Partly,
): String {
    fun names(paths: List<String>) = "untracked ${paths.joinToString(", ")}"
    val clauses =
        listOfNotNull(
            when (partly.tracked) {
                true -> "its changes to tracked files were applied"
                false -> "its changes to tracked files were not applied"
                null -> null
            },
            partly.restored.takeIf { it.isNotEmpty() }?.let {
                "${names(it)} ${if (it.size == 1) "was" else "were"} restored"
            },
            when {
                partly.inTheWay.size == 1 -> "${names(partly.inTheWay)} could not be restored as it already exists"

                partly.inTheWay.size > 1 -> "${names(partly.inTheWay)} could not be restored as they already exist"

                // Not stopped by local changes: by untracked files that could not be read.
                partly.tracked != false -> "its untracked files could not all be restored"

                else -> null
            },
        )
    val kept =
        when {
            !partly.kept -> "The stash is no longer in the list."
            pop -> "The stash is kept."
            else -> "The stash stays in the list."
        }
    return "${if (pop) "Popped" else "Applied"} ${stash.name} only partly: ${clauses.joinToString("; ")}. $kept"
}

/** Pops the newest stash (Git → Pop Latest Stash, ADR 0107), once read; "No stashes to pop." without one. */
internal data object PopLatestStash : RefOperation {
    override val progress get() = "Reading the stashes…"
    override val errorTitle get() = "Pop Failed"
    override val failure get() = "The latest stash could not be popped."
    override val fileChange get() = null

    override fun run(
        writes: GitLogWrites,
        root: Path,
    ): RefOutcome {
        val stashes =
            writes.stashes(root) ?: return RefOutcome(GitResult(false, "The stashes could not be read."), null)
        val latest = stashes.firstOrNull() ?: return RefOutcome(GitResult(true, ""), "No stashes to pop.")
        return RefOutcome(GitResult(true, ""), null, next = PopStash(latest))
    }
}

/** Drops [stash] (ADR 0107), once confirmed ([dropConfirmation]). */
internal data class DropStash(
    val stash: GitStash,
) : RefOperation {
    override val progress get() = "Dropping ${stash.name}…"
    override val errorTitle get() = "Drop Failed"
    override val failure get() = "${stash.name} could not be dropped."

    // No file changes.
    override val fileChange get() = null

    override fun run(
        writes: GitLogWrites,
        root: Path,
    ): RefOutcome =
        when (val result = writes.dropStash(root, stash)) {
            GitStashResult.Done -> RefOutcome(GitResult(true, ""), "Dropped ${stash.name}.")
            else -> stashFailure(result)
        }
}

/** The question before dropping [stash]: Drop, or Cancel, the default. */
internal fun dropConfirmation(stash: GitStash) =
    RefConfirmation(
        "Drop Stash",
        "Drop ${stash.name} (${stash.message})? Its changes are lost.",
        DROP_STASH_BUTTON,
        DropStash(stash),
    )

internal val DROP_STASH_BUTTON = ButtonType("Drop", ButtonBar.ButtonData.OK_DONE)

/** Why a stash command that gave [result] did nothing, as a failure; [GitStashResult.Changed] in the user's words. */
private fun stashFailure(result: GitStashResult): RefOutcome =
    when (result) {
        is GitStashResult.Failed -> RefOutcome(GitResult(false, result.message), null)
        GitStashResult.Changed -> RefOutcome(GitResult(false, STASH_LIST_CHANGED), null)
        else -> RefOutcome(GitResult(false, "Git did something unexpected."), null)
    }

/** Why a stash was not popped, applied or dropped: `stash@{n}` no longer names it. */
internal const val STASH_LIST_CHANGED = "The stash list changed; check it again."

private const val STASH_FAILED = "Stash Failed"

/**
 * What the header says after [conflict]: "Popping stash@{0} gave conflicts in 2 files; the stash is kept.", for an
 * apply "…; the stash stays in the list.".
 */
internal fun stashConflictText(conflict: StashConflict): String {
    val what = "${if (conflict.pop) "Popping" else "Applying"} ${conflict.stash.name}"
    return "$what gave conflicts in ${files(conflict.paths.size)}; ${stashKeptText(conflict)}."
}

/** What the Stash Conflicts dialog says above the paths: "Popping stash@{0} gave conflicts in:". */
internal fun stashConflictHeader(conflict: StashConflict): String =
    "${if (conflict.pop) "Popping" else "Applying"} ${conflict.stash.name} gave conflicts in:"

/**
 * What the Stash Conflicts dialog says below the paths: "The stash is kept; drop it once they are resolved." for a pop,
 * "The stash stays in the list; drop it once they are resolved." for an apply.
 */
internal fun stashConflictNote(conflict: StashConflict): String =
    "${stashKeptText(conflict).replaceFirstChar(Char::uppercase)}; drop it once they are resolved."

private fun stashKeptText(conflict: StashConflict): String =
    when {
        !conflict.kept -> "the stash is no longer in the list"
        conflict.pop -> "the stash is kept"
        else -> "the stash stays in the list"
    }

/**
 * Marks the files with conflicts resolved while git is in the middle of nothing else (ADR 0107): stages them unless
 * conflict markers are left; those that cannot be checked for markers only once confirmed, as they were shown
 * ([confirmed]).
 */
internal data class MarkResolved(
    val confirmed: Set<GitUncheckedConflict> = emptySet(),
) : RefOperation {
    override val progress get() = "Marking the files resolved…"
    override val errorTitle get() = "Mark Resolved Failed"
    override val failure get() = "The files could not be marked resolved."

    // It stages the files as they are on disk: unsaved resolutions matter.
    override val fileChange get() = "Mark Resolved"

    override fun run(
        writes: GitLogWrites,
        root: Path,
    ): RefOutcome =
        when (val result = writes.markResolved(root, confirmed)) {
            is GitResolveResult.Done -> {
                RefOutcome(GitResult(true, ""), "Marked ${files(result.paths.size)} resolved.")
            }

            is GitResolveResult.MarkersLeft -> {
                markersLeftOutcome(result.paths)
            }

            is GitResolveResult.Unchecked -> {
                val confirmation =
                    uncheckedConfirmation(
                        "Mark Resolved",
                        result.files,
                        copy(confirmed = result.files.toSet()),
                        "These conflicted files cannot be checked for conflict markers. Mark them resolved as they " +
                            "are on disk?",
                        STAGE_AS_ON_DISK_BUTTON,
                        staged = true,
                    )
                RefOutcome(GitResult(true, ""), null, confirmation = confirmation)
            }

            is GitResolveResult.Failed -> {
                RefOutcome(GitResult(false, result.message), null)
            }
        }
}

internal val STAGE_AS_ON_DISK_BUTTON = ButtonType("Mark Resolved", ButtonBar.ButtonData.OK_DONE)

/** What the bar above the table says about files with conflicts outside an operation: "2 files have conflicts.". */
internal fun pendingConflictsText(pending: GitPendingConflicts): String =
    if (pending.conflicts.size == 1) "1 file has conflicts." else "${pending.conflicts.size} files have conflicts."

private fun files(count: Int) = if (count == 1) "1 file" else "$count files"
