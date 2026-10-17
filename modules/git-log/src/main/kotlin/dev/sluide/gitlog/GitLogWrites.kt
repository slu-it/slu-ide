package dev.sluide.gitlog

import dev.sluide.git.GitBranchDeletion
import dev.sluide.git.GitBranchTip
import dev.sluide.git.GitBranches
import dev.sluide.git.GitCancellation
import dev.sluide.git.GitCommits
import dev.sluide.git.GitFetchResult
import dev.sluide.git.GitInProgress
import dev.sluide.git.GitPick
import dev.sluide.git.GitPickResult
import dev.sluide.git.GitRemotes
import dev.sluide.git.GitResetMode
import dev.sluide.git.GitResult
import dev.sluide.git.GitUncheckedConflict
import java.nio.file.Path

/**
 * How the git log changes a repository (ADRs 0041, 0042, 0043); [GitBranches], [GitCommits] and [GitRemotes] in the
 * application, a fake in tests. Called off the FX thread, except [canPass].
 */
internal interface GitLogWrites {
    /** Whether [argument] can be passed to git unchanged in the JVM's locale. */
    fun canPass(argument: String): Boolean

    /** Checks out the local branch [branch] (short name). */
    fun switchBranch(
        root: Path,
        branch: String,
    ): GitResult

    /** Checks out [revision] (a hash or full refname) detached. */
    fun switchDetached(
        root: Path,
        revision: String,
    ): GitResult

    /** Creates and checks out a local branch tracking [remoteBranch] (full refname). */
    fun switchTracking(
        root: Path,
        remoteBranch: String,
    ): GitResult

    /** Whether the local branch [name] (short name) exists. */
    fun branchExists(
        root: Path,
        name: String,
    ): Boolean

    /** The commit the local branch [name] points at, or that there is none, or that it could not be read. */
    fun branchTip(
        root: Path,
        name: String,
    ): GitBranchTip

    /** Whether [ancestor] is an ancestor of [descendant] or the same; `null` on failure. */
    fun isAncestor(
        root: Path,
        ancestor: String,
        descendant: String,
    ): Boolean?

    /** Creates the branch [name] at [startPoint], checks it out if [checkout], replaces an existing one if [overwrite]. */
    fun createBranch(
        root: Path,
        name: String,
        startPoint: String,
        checkout: Boolean,
        overwrite: Boolean,
    ): GitResult

    /** Creates the tag [name] at [commit], annotated with [message] unless it is blank. */
    fun createTag(
        root: Path,
        name: String,
        commit: String,
        message: String?,
    ): GitResult

    /** Renames the local branch [old] to [new]. */
    fun renameBranch(
        root: Path,
        old: String,
        new: String,
    ): GitResult

    /** Deletes the local branches [names] (short names), not fully merged ones only with [force]. */
    fun deleteBranches(
        root: Path,
        names: List<String>,
        force: Boolean,
    ): GitBranchDeletion

    /** Deletes the tags [names] (short names). */
    fun deleteTags(
        root: Path,
        names: List<String>,
    ): GitResult

    /** The commit `HEAD` points at; `null` if there is none or it cannot be read. */
    fun headCommit(root: Path): String?

    /** Cherry-picks or reverts ([kind]) [commit], a merge commit relative to its first parent if [firstParent]. */
    fun pick(
        root: Path,
        kind: GitPick,
        commit: String,
        firstParent: Boolean,
    ): GitPickResult

    /** What git is in the middle of; `null` if nothing or it cannot be read. */
    fun inProgress(root: Path): GitInProgress?

    /**
     * Stages the resolved files and continues the cherry-pick or revert ([kind]) in progress; conflicted files that
     * cannot be checked for markers only if all are among the [confirmed] ones.
     */
    fun continuePick(
        root: Path,
        kind: GitPick,
        confirmed: Set<GitUncheckedConflict>,
    ): GitPickResult

    /** Aborts the cherry-pick or revert ([kind]) in progress. */
    fun abortPick(
        root: Path,
        kind: GitPick,
    ): GitResult

    /** How many commits a reset to [target] would leave on no branch or tag; `null` if that cannot be read. */
    fun unreachableCount(
        root: Path,
        target: String,
    ): Int?

    /** How many tracked files have local changes; `null` if that cannot be read. */
    fun localChangeCount(root: Path): Int?

    /** Resets the current branch (or a detached `HEAD`) to [target] in [mode]. */
    fun reset(
        root: Path,
        target: String,
        mode: GitResetMode,
    ): GitResult

    /** The remotes fetching all fetches (not those with `skipFetchAll`); `null` if they cannot be read. */
    fun remotes(root: Path): List<String>?

    /** Fetches [remote], pruning its deleted branches; [cancellation] stops git. */
    fun fetch(
        root: Path,
        remote: String,
        cancellation: GitCancellation,
    ): GitFetchResult
}

/** Changes the repository with the git command line. */
internal class GitLogCliWrites(
    private val branches: GitBranches,
    private val commits: GitCommits,
    private val gitRemotes: GitRemotes,
) : GitLogWrites {
    override fun canPass(argument: String) = branches.canPass(argument)

    override fun switchBranch(
        root: Path,
        branch: String,
    ) = branches.switchBranch(root, branch)

    override fun switchDetached(
        root: Path,
        revision: String,
    ) = branches.switchDetached(root, revision)

    override fun switchTracking(
        root: Path,
        remoteBranch: String,
    ) = branches.switchTracking(root, remoteBranch)

    override fun branchExists(
        root: Path,
        name: String,
    ) = branches.branchExists(root, name)

    override fun branchTip(
        root: Path,
        name: String,
    ) = branches.branchTip(root, name)

    override fun isAncestor(
        root: Path,
        ancestor: String,
        descendant: String,
    ) = branches.isAncestor(root, ancestor, descendant)

    override fun createBranch(
        root: Path,
        name: String,
        startPoint: String,
        checkout: Boolean,
        overwrite: Boolean,
    ) = branches.createBranch(root, name, startPoint, checkout, overwrite)

    override fun createTag(
        root: Path,
        name: String,
        commit: String,
        message: String?,
    ) = branches.createTag(root, name, commit, message)

    override fun renameBranch(
        root: Path,
        old: String,
        new: String,
    ) = branches.renameBranch(root, old, new)

    override fun deleteBranches(
        root: Path,
        names: List<String>,
        force: Boolean,
    ) = branches.deleteBranches(root, names, force)

    override fun deleteTags(
        root: Path,
        names: List<String>,
    ) = branches.deleteTags(root, names)

    override fun headCommit(root: Path) = commits.headCommit(root)

    override fun pick(
        root: Path,
        kind: GitPick,
        commit: String,
        firstParent: Boolean,
    ) = commits.pick(root, kind, commit, firstParent)

    override fun inProgress(root: Path) = commits.inProgress(root)

    override fun continuePick(
        root: Path,
        kind: GitPick,
        confirmed: Set<GitUncheckedConflict>,
    ) = commits.continuePick(root, kind, confirmed)

    override fun abortPick(
        root: Path,
        kind: GitPick,
    ) = commits.abortPick(root, kind)

    override fun unreachableCount(
        root: Path,
        target: String,
    ) = commits.unreachableCount(root, target)

    override fun localChangeCount(root: Path) = commits.localChangeCount(root)

    override fun reset(
        root: Path,
        target: String,
        mode: GitResetMode,
    ) = commits.reset(root, target, mode)

    override fun remotes(root: Path) = gitRemotes.remotes(root)

    override fun fetch(
        root: Path,
        remote: String,
        cancellation: GitCancellation,
    ) = gitRemotes.fetch(root, remote, cancellation)
}
