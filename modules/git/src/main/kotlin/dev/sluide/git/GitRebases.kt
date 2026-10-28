package dev.sluide.git

import dev.sluide.git.cli.GitCli
import dev.sluide.git.cli.StashTip
import dev.sluide.git.cli.canPassArgument
import dev.sluide.git.cli.changedPaths
import dev.sluide.git.cli.currentBranch
import dev.sluide.git.cli.headCommit
import dev.sluide.git.cli.inProgress
import dev.sluide.git.cli.indexMatchesHead
import dev.sluide.git.cli.isAncestor
import dev.sluide.git.cli.mergeCount
import dev.sluide.git.cli.refusal
import dev.sluide.git.cli.stashKept
import dev.sluide.git.cli.stashTip
import dev.sluide.git.cli.unmergedEntries
import dev.sluide.git.cli.unmergedPaths
import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * Rebases the current branch onto another branch, and continues, skips or aborts a rebase in progress, also one started
 * in a terminal (ADR 0063), with the git command line: hooks and the user's configuration apply as for git itself,
 * except the settings that would make it more than a plain rebase. Every method runs git on the calling thread and may
 * take 10 minutes; none can be cancelled. Paths go to git's standard input, never as arguments (ADR 0038).
 */
@Component
class GitRebases(
    private val git: GitCli,
) {
    /** Whether the JVM can name the working-tree file at a path (ADR 0038); tests take the fallback through git. */
    internal var canNameFile: (String) -> Boolean = { canPassArgument(it) }

    private val conflicts = GitConflictCheck(git) { canNameFile(it) }

    /**
     * Rebases [branch] (a short name, expected to be the current branch) onto [onto] (a full refname): `git -c
     * rebase.updateRefs=false -c rebase.rebaseMerges=false rebase --merge --no-autostash --no-fork-point
     * --end-of-options <onto>`, so that no other branch moves, merges are not recreated, local changes are not stashed
     * (git refuses with them, leaving nothing) and commits that [onto] once had are kept. A pull (ADR 0106) passes
     * [forkPoint] (`--fork-point`: commits [onto]'s reflog once had, e.g. before a force push, are not replayed) and
     * [autostash] (`--autostash`: local changes are stashed and applied when the rebase ends, also through
     * [continueRebase], [skipRebase] or [abortRebase]). Refused without running git while git is in the middle of
     * anything (or that cannot be read) or [branch] is no longer the current branch; [GitRebaseResult.UpToDate]
     * without running git if [onto] is already contained in `HEAD` (a rebase would only drop the merges).
     */
    fun rebase(
        root: Path,
        branch: String,
        onto: String,
        forkPoint: Boolean = false,
        autostash: Boolean = false,
    ): GitRebaseResult {
        refusal(onto)?.let { return GitRebaseResult.Failed(it) }
        val busy = git.inProgress(root).getOrElse { return GitRebaseResult.Failed(IN_PROGRESS_UNREADABLE) }
        if (busy != null) return GitRebaseResult.Failed(busyMessage(busy))
        if (git.currentBranch(root) != "refs/heads/$branch") {
            return GitRebaseResult.Failed("'$branch' is no longer the current branch.")
        }
        val contained =
            git.isAncestor(root, onto, "HEAD")
                ?: return GitRebaseResult.Failed("Whether '$branch' already contains $onto could not be read.")
        if (contained) return GitRebaseResult.UpToDate
        val arguments =
            listOf("-c", "rebase.updateRefs=false", "-c", "rebase.rebaseMerges=false", "rebase", "--merge") +
                listOf(
                    if (autostash) "--autostash" else "--no-autostash",
                    if (forkPoint) "--fork-point" else "--no-fork-point",
                    "--end-of-options",
                    onto,
                )
        val stash = git.stashTip(root)
        val result = git.write(root, arguments, timeout = GitCli.CHECKOUT_TIMEOUT, writesFiles = true)
        return outcome(root, result, stash)
    }

    /**
     * How many merge commits `HEAD` has that [onto] (a full refname) does not: a rebase drops them and replays their
     * changes as plain commits. `null` if that cannot be read.
     */
    fun mergeCount(
        root: Path,
        onto: String,
    ): Int? = if (refusal(onto) != null) null else git.mergeCount(root, onto)

    /**
     * Continues the rebase in progress (ADR 0063), with the checks of continuing a cherry-pick (ADR 0042): conflict
     * markers left ([GitRebaseResult.MarkersLeft]) or files that cannot be checked and are not all among the
     * [confirmed] ones ([GitRebaseResult.Unchecked]) refuse it, nothing done; then exactly the unmerged files are
     * staged. If a conflict was resolved so that nothing is left to commit (the index as `HEAD`, and no unstaged
     * changes, which the skip's hard reset would drop) the commit is skipped (`git rebase --skip`); else `git rebase
     * --continue` with `GIT_EDITOR=:`, which keeps the message git prepared. A stop without conflicts (untracked files in
     * the way, a hook) is always continued, so git tells if there is nothing to commit, as in a terminal.
     */
    fun continueRebase(
        root: Path,
        confirmed: Set<GitUncheckedConflict> = emptySet(),
    ): GitRebaseResult {
        pending(root).onFailure { return GitRebaseResult.Failed(it.message.orEmpty()) }
        val entries = git.unmergedEntries(root) ?: return GitRebaseResult.Failed(CONFLICTS_UNREADABLE)
        if (entries.isNotEmpty()) {
            val check = conflicts.check(root, entries) ?: return GitRebaseResult.Failed(CONFLICTS_UNREADABLE)
            if (check.markers.isNotEmpty()) return GitRebaseResult.MarkersLeft(check.markers)
            check.refusal?.let { return GitRebaseResult.Failed(it) }
            if (!confirmed.containsAll(check.unchecked)) return GitRebaseResult.Unchecked(check.unchecked)
            val staged = conflicts.stage(root, entries)
            if (!staged.success) return GitRebaseResult.Failed(staged.message)
            if (nothingToCommit(root)) return skip(root)
        }
        val stash = git.stashTip(root)
        val (result, _) =
            git.writeWithExit(
                root,
                listOf("rebase", "--continue"),
                timeout = GitCli.CHECKOUT_TIMEOUT,
                environment = mapOf("GIT_EDITOR" to ":"),
                writesFiles = true,
            )
        return outcome(root, result, stash)
    }

    /** Skips the commit the rebase in progress stopped at: `git rebase --skip`, which resets the files to `HEAD`. */
    fun skipRebase(root: Path): GitRebaseResult {
        pending(root).onFailure { return GitRebaseResult.Failed(it.message.orEmpty()) }
        return skip(root)
    }

    /** Aborts the rebase in progress: `git rebase --abort`, which takes the branch, index and files back to before it. */
    fun abortRebase(root: Path): GitRebaseResult {
        pending(root).onFailure { return GitRebaseResult.Failed(it.message.orEmpty()) }
        val stash = git.stashTip(root)
        val result = git.write(root, listOf("rebase", "--abort"), timeout = GitCli.CHECKOUT_TIMEOUT, writesFiles = true)
        return outcome(root, result, stash)
    }

    /** The rebase in progress; a failure with the message why nothing is done if there is none or that cannot be read. */
    private fun pending(root: Path): Result<GitPendingRebase> {
        val busy = git.inProgress(root).getOrElse { return refused(IN_PROGRESS_UNREADABLE) }
        return when (busy) {
            is GitPendingRebase -> Result.success(busy)
            null -> refused("No rebase is in progress.")
            else -> refused(busyMessage(busy))
        }
    }

    private fun refused(message: String) = Result.failure<GitPendingRebase>(IllegalStateException(message))

    /** Whether no path is unmerged, the index is as `HEAD` and no tracked file has unstaged changes. */
    private fun nothingToCommit(root: Path): Boolean =
        git.unmergedPaths(root)?.isEmpty() == true &&
            git.indexMatchesHead(root) == true &&
            git.changedPaths(root, staged = false)?.isEmpty() == true

    private fun skip(root: Path): GitRebaseResult {
        val stash = git.stashTip(root)
        val result = git.write(root, listOf("rebase", "--skip"), timeout = GitCli.CHECKOUT_TIMEOUT, writesFiles = true)
        return outcome(root, result, stash)
    }

    /**
     * What a rebase command that gave [result] left: a rebase still in progress is [GitRebaseResult.Stopped] (also when
     * git succeeded, e.g. at an `edit` instruction of an interactive one), none after success is
     * [GitRebaseResult.Done], none after a failure [GitRebaseResult.Failed] with git's message. If what git is in the
     * middle of cannot be read afterwards, git's exit value decides between done and failed; the refresh shows the rest.
     * The stash kept is told by `refs/stash` other than [stashBefore] ([stashKept]): an autostash git could not apply
     * is stored there (ADR 0106).
     */
    private fun outcome(
        root: Path,
        result: GitResult,
        stashBefore: StashTip?,
    ): GitRebaseResult {
        val after = git.inProgress(root).getOrNull()
        return when {
            after is GitPendingRebase -> {
                GitRebaseResult.Stopped(after, result.message)
            }

            result.success -> {
                val kept = stashKept(stashBefore, git.stashTip(root))
                GitRebaseResult.Done(git.headCommit(root), stashKept = kept)
            }

            else -> {
                GitRebaseResult.Failed(result.message)
            }
        }
    }
}
