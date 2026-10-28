package dev.sluide.gitlog.operations

import dev.sluide.git.GitMergeMode
import dev.sluide.git.GitMergeResult
import dev.sluide.git.GitPendingMerge
import dev.sluide.git.GitRefKind
import dev.sluide.git.GitRefs
import dev.sluide.git.GitResult
import dev.sluide.git.GitUncheckedConflict
import dev.sluide.gitlog.data.GitLogWrites
import java.nio.file.Path

/**
 * A merge of [source] into [branch] (short names, `null` where not known) that git stopped and left for the user (ADR
 * 0105), with the [conflicts] (paths; empty if it stopped for another reason, e.g. a hook) and git's [message].
 */
internal data class MergeStop(
    val source: String?,
    val branch: String?,
    val conflicts: List<String>,
    val message: String,
)

/**
 * Merges [ref] (the full refname of a local or remote branch, shown as [refLabel]) into the current [branch] (short
 * name) in [mode] (ADR 0105), with [message] or git's own (`null`); local changes are stashed and restored afterwards
 * if [autostash]. Nothing is run if [ref] is already contained in `HEAD`.
 */
internal data class MergeBranch(
    val branch: String,
    val ref: String,
    val refLabel: String,
    val mode: GitMergeMode,
    val message: String?,
    val autostash: Boolean,
) : RefOperation {
    override val progress get() = "Merging $refLabel into $branch…"
    override val errorTitle get() = "Merge Failed"
    override val failure get() = "$refLabel could not be merged into $branch."

    // It changes the files.
    override val fileChange get() = "Merge"

    override fun run(
        writes: GitLogWrites,
        root: Path,
    ): RefOutcome {
        val result = writes.merge(root, branch, ref, mode, message, autostash)
        if (result is GitMergeResult.UpToDate) {
            return RefOutcome(GitResult(true, ""), "'$refLabel' is already contained in '$branch'; nothing to merge.")
        }
        return mergeOutcome(result, refLabel, branch) { done -> mergedText(refLabel, branch, done) }
    }
}

/**
 * Continues the merge in progress of [source] into [branch] (short names, `null` where not known): stages the files
 * that were unmerged, unless conflict markers are left in them, and commits with the message git prepared. Conflicted
 * files that cannot be checked for markers are committed as they are on disk only once confirmed, as they were shown
 * ([confirmed]); if they are others by then, the user is asked again.
 */
internal data class ContinueMerge(
    val source: String? = null,
    val branch: String? = null,
    val confirmed: Set<GitUncheckedConflict> = emptySet(),
) : RefOperation {
    override val progress get() = "Continuing the merge…"
    override val errorTitle get() = "Continue Failed"
    override val failure get() = "The merge could not be continued."

    // It stages the files as they are on disk: unsaved resolutions matter.
    override val fileChange get() = "Continue"

    override fun run(
        writes: GitLogWrites,
        root: Path,
    ): RefOutcome {
        val result = writes.continueMerge(root, confirmed)
        if (result !is GitMergeResult.Unchecked) {
            return mergeOutcome(result, source, branch) {
                if (source != null && branch != null) "Merged $source into $branch." else "Merge committed."
            }
        }
        val confirmation = uncheckedConfirmation("Continue Merge", result.files, copy(confirmed = result.files.toSet()))
        return RefOutcome(GitResult(true, ""), null, confirmation = confirmation)
    }
}

/** Aborts the merge in progress: the index and files go back to before it. */
internal data object AbortMerge : RefOperation {
    override val progress get() = "Aborting the merge…"
    override val errorTitle get() = "Abort Failed"
    override val failure get() = "The merge could not be aborted."
    override val fileChange get() = "Abort"

    override fun run(
        writes: GitLogWrites,
        root: Path,
    ): RefOutcome = mergeOutcome(writes.abortMerge(root), null, null) { "Aborted the merge." }
}

/** What the header says after a merge of [source] into [branch] that [done]: how it was merged. */
internal fun mergedText(
    source: String,
    branch: String,
    done: GitMergeResult.Done,
): String =
    when {
        done.fastForward -> {
            "Fast-forwarded $branch to $source."
        }

        done.squashed && done.conflicts.isNotEmpty() -> {
            "Squashed $source into the index of $branch with conflicts in ${files(done.conflicts.size)}; resolve " +
                "them, then commit."
        }

        done.squashed -> {
            "Squashed $source into the index of $branch; commit the changes."
        }

        else -> {
            "Merged $source into $branch."
        }
    }

/** Said after what a merge did when git could not apply the local changes it stashed for it without conflicts. */
internal const val STASH_KEPT =
    "Your local changes could not be restored without conflicts; they are kept in the stash."

/**
 * The outcome of a merge of [source] into [branch] (`null` where not known), or of continuing or aborting one, that
 * gave [result]; [done] is what the header says after it was done, followed by [STASH_KEPT] if that applies.
 */
internal fun mergeOutcome(
    result: GitMergeResult,
    source: String?,
    branch: String?,
    done: (GitMergeResult.Done) -> String,
): RefOutcome =
    when (result) {
        is GitMergeResult.Done -> {
            val text = done(result)
            RefOutcome(GitResult(true, ""), if (result.stashKept) "$text $STASH_KEPT" else text)
        }

        is GitMergeResult.UpToDate -> {
            RefOutcome(GitResult(true, ""), "Nothing to merge.")
        }

        is GitMergeResult.Stopped -> {
            val stop = MergeStop(source, branch, result.pending.conflicts, result.message)
            RefOutcome(GitResult(true, result.message), mergeStopText(stop), mergeStopped = stop)
        }

        is GitMergeResult.MarkersLeft -> {
            markersLeftOutcome(result.paths)
        }

        // Asked about by ContinueMerge before this.
        is GitMergeResult.Unchecked -> {
            RefOutcome(GitResult(false, "Conflicted files cannot be checked for conflict markers."), null)
        }

        is GitMergeResult.Failed -> {
            RefOutcome(GitResult(false, result.message), null)
        }
    }

/** "Merge of main into feature", or "Merge" where either is not known. */
private fun mergeOf(
    source: String?,
    branch: String?,
) = if (source != null && branch != null) "Merge of $source into $branch" else "Merge"

/** What the header says about a merge that [stop]ped: "Merge of main into feature stopped with conflicts.". */
internal fun mergeStopText(stop: MergeStop): String {
    val conflicts = if (stop.conflicts.isEmpty()) "" else " with conflicts"
    return "${mergeOf(stop.source, stop.branch)} stopped$conflicts."
}

/** The header of the Merge Stopped dialog: "Merge of main into feature stopped with conflicts in:". */
internal fun mergeStopHeader(stop: MergeStop): String {
    val conflicts = if (stop.conflicts.isEmpty()) "." else " with conflicts in:"
    return "${mergeOf(stop.source, stop.branch)} stopped$conflicts"
}

/** The question before aborting the merge in progress from the bar. */
internal const val ABORT_MERGE_QUESTION =
    "Abort the merge? Its changes are undone, also what was done to resolve its conflicts."

/**
 * What the bar above the table says about a [pending] merge: the first line of its message and the state of its
 * conflicts ("Merge branch 'main' into feature: 2 files have conflicts."); an octopus merge is left to the terminal.
 */
internal fun pendingMergeText(pending: GitPendingMerge): String {
    if (isOctopus(pending)) return "An octopus merge is in progress; finish or abort it in the terminal."
    val state =
        when (pending.conflicts.size) {
            0 -> "conflicts resolved."
            else -> "${files(pending.conflicts.size)} ${if (pending.conflicts.size == 1) "has" else "have"} conflicts."
        }
    return "${pending.message ?: "A merge is in progress"}: $state"
}

/** Whether [pending] merges several commits at once, which the bar leaves to the terminal. */
internal fun isOctopus(pending: GitPendingMerge) = pending.heads.size > 1

/**
 * The [ContinueMerge] for [pending] as the [refs] the log read last name it: the local or remote branch at the commit
 * being merged and the current branch, each `null` if there is none.
 */
internal fun continueMerge(
    pending: GitPendingMerge,
    refs: GitRefs?,
): ContinueMerge {
    val branch = refs?.currentBranch
    val source =
        pending.heads.singleOrNull()?.let { head ->
            refs
                ?.at(head)
                ?.firstOrNull {
                    it.name != branch && (it.kind == GitRefKind.LOCAL_BRANCH || it.kind == GitRefKind.REMOTE_BRANCH)
                }?.shortName
        }
    return ContinueMerge(source, branch?.removePrefix("refs/heads/"))
}

private fun files(count: Int) = if (count == 1) "1 file" else "$count files"
