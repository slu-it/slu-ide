package dev.sluide.gitlog.operations

import dev.sluide.git.GitPendingRebase
import dev.sluide.git.GitRebaseResult
import dev.sluide.git.GitResult
import dev.sluide.git.GitUncheckedConflict
import dev.sluide.gitlog.data.GitLogWrites
import dev.sluide.gitlog.data.SHORT_HASH
import java.nio.file.Path

/**
 * A rebase that git stopped and left for the user (ADR 0063): of [branch] (`null` if detached) at the commit [stopped]
 * (a full hash, `null` where there is none), with the [conflicts] (paths; empty if it stopped for another reason, e.g.
 * an `edit` or a hook) and git's [message].
 */
internal data class RebaseStop(
    val branch: String?,
    val stopped: String?,
    val conflicts: List<String>,
    val message: String,
)

/**
 * Rebases the current [branch] (short name) onto [onto] (a full refname, shown as [ontoLabel]; ADR 0063). Nothing is
 * run if [onto] is already contained in `HEAD`. Unless [confirmed], it first asks if `HEAD` has merge commits that
 * [onto] lacks (or that cannot be read), as the rebase drops them.
 */
internal data class RebaseBranch(
    val branch: String,
    val onto: String,
    val ontoLabel: String,
    val confirmed: Boolean = false,
) : RefOperation {
    override val progress get() = "Rebasing $branch onto $ontoLabel…"
    override val errorTitle get() = "Rebase Failed"
    override val failure get() = "$branch could not be rebased onto $ontoLabel."

    // It rewrites the files.
    override val fileChange get() = "Rebase"

    override fun run(
        writes: GitLogWrites,
        root: Path,
    ): RefOutcome {
        if (writes.isAncestor(root, onto, "HEAD") == true) return RefOutcome(GitResult(true, ""), upToDate())
        if (!confirmed) {
            val merges = writes.rebaseMergeCount(root, onto)
            if (merges == null || merges > 0) {
                val confirmation =
                    RefConfirmation(
                        "Rebase Current Branch",
                        mergesQuestion(branch, ontoLabel, merges),
                        REBASE_BUTTON,
                        copy(confirmed = true),
                    )
                return RefOutcome(GitResult(true, ""), null, confirmation = confirmation)
            }
        }
        return rebaseOutcome(writes.rebase(root, branch, onto), branch) { "Rebased $branch onto $ontoLabel." }
    }

    private fun upToDate() = "'$ontoLabel' is already contained in '$branch'; nothing to rebase."
}

/** The question before a rebase of [branch] onto [onto] that drops [merges] merge commits (`null`: maybe some). */
internal fun mergesQuestion(
    branch: String,
    onto: String,
    merges: Int?,
): String {
    val has =
        when (merges) {
            null -> "$branch may have merge commits since $onto"
            1 -> "$branch has 1 merge commit since $onto"
            else -> "$branch has $merges merge commits since $onto"
        }
    return "$has; the rebase drops them and replays their changes as plain commits."
}

/**
 * Continues the rebase in progress: stages the files that were unmerged, unless conflict markers are left in them, and
 * commits. Conflicted files that cannot be checked for markers are committed as they are on disk only once confirmed,
 * as they were shown ([confirmed]); if they are others by then, the user is asked again.
 */
internal data class ContinueRebase(
    val confirmed: Set<GitUncheckedConflict> = emptySet(),
) : RefOperation {
    override val progress get() = "Continuing the rebase…"
    override val errorTitle get() = "Continue Failed"
    override val failure get() = "The rebase could not be continued."

    // It stages the files as they are on disk: unsaved resolutions matter.
    override val fileChange get() = "Continue"

    override fun run(
        writes: GitLogWrites,
        root: Path,
    ): RefOutcome {
        val branch = pendingBranch(writes, root)
        val result = writes.continueRebase(root, confirmed)
        if (result !is GitRebaseResult.Unchecked) return rebaseOutcome(result, branch) { finished(branch) }
        val confirmation =
            uncheckedConfirmation("Continue Rebase", result.files, copy(confirmed = result.files.toSet()))
        return RefOutcome(GitResult(true, ""), null, confirmation = confirmation)
    }
}

/** Skips the commit the rebase in progress stopped at; its changes are dropped. */
internal data object SkipRebase : RefOperation {
    override val progress get() = "Skipping the commit of the rebase…"
    override val errorTitle get() = "Skip Failed"
    override val failure get() = "The commit could not be skipped."

    // The skip resets the files to HEAD.
    override val fileChange get() = "Skip"

    override fun run(
        writes: GitLogWrites,
        root: Path,
    ): RefOutcome {
        val branch = pendingBranch(writes, root)
        return rebaseOutcome(writes.skipRebase(root), branch) {
            "Skipped the commit; ${finished(branch).replaceFirstChar(Char::lowercaseChar)}"
        }
    }
}

/** Aborts the rebase in progress: the branch, index and files go back to before it. */
internal data object AbortRebase : RefOperation {
    override val progress get() = "Aborting the rebase…"
    override val errorTitle get() = "Abort Failed"
    override val failure get() = "The rebase could not be aborted."
    override val fileChange get() = "Abort"

    override fun run(
        writes: GitLogWrites,
        root: Path,
    ): RefOutcome {
        val branch = pendingBranch(writes, root)
        val done = if (branch != null) "Aborted the rebase of $branch." else "Aborted the rebase."
        return when (val result = writes.abortRebase(root)) {
            is GitRebaseResult.Done -> RefOutcome(GitResult(true, ""), done)
            is GitRebaseResult.Failed -> RefOutcome(GitResult(false, result.message), null)
            is GitRebaseResult.Stopped -> RefOutcome(GitResult(false, result.message), null)
            else -> RefOutcome(GitResult(false, "The rebase could not be aborted."), null)
        }
    }
}

/** The branch of the rebase in progress as git has it now; `null` if detached or there is none. */
private fun pendingBranch(
    writes: GitLogWrites,
    root: Path,
): String? = (writes.inProgress(root) as? GitPendingRebase)?.branch

/** What the header says when a rebase of [branch] finished after continuing or skipping: "Rebased feature.". */
private fun finished(branch: String?) = if (branch != null) "Rebased $branch." else "Rebase finished."

/**
 * The outcome of a rebase of [branch], or of continuing or skipping one, that gave [result]; [done] is what the header
 * says after it finished.
 */
private fun rebaseOutcome(
    result: GitRebaseResult,
    branch: String?,
    done: () -> String,
): RefOutcome =
    when (result) {
        is GitRebaseResult.Done -> {
            RefOutcome(GitResult(true, ""), done())
        }

        is GitRebaseResult.UpToDate -> {
            RefOutcome(GitResult(true, ""), "Nothing to rebase.")
        }

        is GitRebaseResult.Stopped -> {
            val stop =
                RebaseStop(
                    result.pending.branch ?: branch,
                    result.pending.stopped,
                    result.pending.conflicts,
                    result.message,
                )
            RefOutcome(GitResult(true, result.message), stopText(stop), rebaseStopped = stop)
        }

        is GitRebaseResult.MarkersLeft -> {
            markersLeftOutcome(result.paths)
        }

        // Asked about by ContinueRebase before this.
        is GitRebaseResult.Unchecked -> {
            RefOutcome(GitResult(false, "Conflicted files cannot be checked for conflict markers."), null)
        }

        is GitRebaseResult.Failed -> {
            RefOutcome(GitResult(false, result.message), null)
        }
    }

/** What the header says about a rebase that [stop]ped: "Rebase of feature stopped at 5e6f7a8b with conflicts.". */
internal fun stopText(stop: RebaseStop): String {
    val of = stop.branch?.let { "Rebase of $it" } ?: "Rebase"
    val at = stop.stopped?.let { " at ${it.take(SHORT_HASH)}" }.orEmpty()
    val conflicts = if (stop.conflicts.isEmpty()) "" else " with conflicts"
    return "$of stopped$at$conflicts."
}

/** The question before aborting the rebase of [branch] (`null` if detached) from the bar. */
internal fun abortRebaseQuestion(branch: String?): String {
    val what = if (branch != null) "the rebase of '$branch'" else "the rebase"
    val goes = if (branch != null) "The branch" else "HEAD"
    return "Abort $what? $goes goes back to where it was; the commits rebased so far and what was done to resolve " +
        "conflicts are lost."
}

/**
 * The question before skipping the commit [pending] stopped at from the bar: `git rebase --skip` resets the files, so
 * what was done to resolve its conflicts is lost.
 */
internal fun skipRebaseQuestion(pending: GitPendingRebase): String {
    val commit = pending.stopped?.let { "Skip ${it.take(SHORT_HASH)}" } ?: "Skip the current commit"
    val of = pending.branch?.let { " of '$it'" }.orEmpty()
    val lost =
        if (pending.conflicts.isEmpty()) {
            "local changes to tracked files are lost"
        } else {
            "what was done to resolve its conflicts is lost"
        }
    return "$commit and continue the rebase$of? Its changes are left out, and $lost."
}

/** What the bar above the table says about a [pending] rebase. */
internal fun pendingRebaseText(pending: GitPendingRebase): String {
    val head =
        "Rebasing" + pending.branch?.let { " $it" }.orEmpty() +
            pending.onto?.let { " onto ${it.take(SHORT_HASH)}" }.orEmpty()
    val at = pending.stopped?.let { ": stopped at ${it.take(SHORT_HASH)}" }.orEmpty()
    val position = if (pending.step != null && pending.total != null) " (${pending.step} of ${pending.total})" else ""
    val conflicts =
        when (pending.conflicts.size) {
            0 -> ""
            1 -> "; 1 file has conflicts"
            else -> "; ${pending.conflicts.size} files have conflicts"
        }
    return "$head$at$position$conflicts."
}
