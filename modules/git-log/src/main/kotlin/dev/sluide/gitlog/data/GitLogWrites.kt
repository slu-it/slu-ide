package dev.sluide.gitlog.data

import dev.sluide.git.GitBranchDeletion
import dev.sluide.git.GitBranchTip
import dev.sluide.git.GitCancellation
import dev.sluide.git.GitChangedFile
import dev.sluide.git.GitCommitResult
import dev.sluide.git.GitFetchResult
import dev.sluide.git.GitInProgress
import dev.sluide.git.GitMergeMode
import dev.sluide.git.GitMergeResult
import dev.sluide.git.GitPick
import dev.sluide.git.GitPickResult
import dev.sluide.git.GitPushResult
import dev.sluide.git.GitRebaseResult
import dev.sluide.git.GitRemoteDeletion
import dev.sluide.git.GitRemoteDeletionResult
import dev.sluide.git.GitResetMode
import dev.sluide.git.GitResult
import dev.sluide.git.GitRewordCheck
import dev.sluide.git.GitRewordResult
import dev.sluide.git.GitUncheckedConflict
import java.nio.file.Path

/**
 * How the git log changes a repository (ADRs 0041, 0042, 0043, 0049, 0063, 0078, 0105); [GitLogCliWrites] in the
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

    /**
     * How many commits of a detached `HEAD` a checkout of [target] (a hash or full refname) would leave on no branch,
     * remote branch or tag; 0 while `HEAD` is on a branch, `null` if that cannot be read.
     */
    fun leftBehind(
        root: Path,
        target: String,
    ): Int?

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

    /** Rebases the current [branch] (short name) onto [onto] (a full refname); ADR 0063. */
    fun rebase(
        root: Path,
        branch: String,
        onto: String,
    ): GitRebaseResult

    /** How many merge commits `HEAD` has that [onto] (a full refname) does not; `null` if that cannot be read. */
    fun rebaseMergeCount(
        root: Path,
        onto: String,
    ): Int?

    /** Stages the resolved files and continues the rebase in progress; unchecked ones only if all are [confirmed]. */
    fun continueRebase(
        root: Path,
        confirmed: Set<GitUncheckedConflict>,
    ): GitRebaseResult

    /** Skips the commit the rebase in progress stopped at. */
    fun skipRebase(root: Path): GitRebaseResult

    /** Aborts the rebase in progress. */
    fun abortRebase(root: Path): GitRebaseResult

    /**
     * Merges [ref] (the full refname of a branch) into the current [branch] (short name) in [mode], with [message] or
     * git's own (`null`), local changes stashed and applied afterwards if [autostash]; ADR 0105.
     */
    fun merge(
        root: Path,
        branch: String,
        ref: String,
        mode: GitMergeMode,
        message: String?,
        autostash: Boolean,
    ): GitMergeResult

    /** Stages the resolved files and continues the merge in progress; unchecked ones only if all are [confirmed]. */
    fun continueMerge(
        root: Path,
        confirmed: Set<GitUncheckedConflict>,
    ): GitMergeResult

    /** Aborts the merge in progress. */
    fun abortMerge(root: Path): GitMergeResult

    /** Whether the message of the commit (a full hash) of the current [branch] can be changed; ADR 0100. */
    fun rewordCheck(
        root: Path,
        branch: String,
        commit: String,
    ): GitRewordCheck

    /** Changes the message of the commit (a full hash) of the current [branch] to [message]; ADR 0100. */
    fun reword(
        root: Path,
        branch: String,
        commit: String,
        message: String,
    ): GitRewordResult

    /** How many commits a reset to [target] would leave on no branch or tag; `null` if that cannot be read. */
    fun unreachableCount(
        root: Path,
        target: String,
    ): Int?

    /** How many tracked files have local changes; `null` if that cannot be read. */
    fun localChangeCount(root: Path): Int?

    /** How many files have staged changes that differ from the file on disk; `null` if that cannot be read. */
    fun stagedOnlyCount(root: Path): Int?

    /** Whether git refuses a soft reset (a merge, unmerged paths); `null` if that cannot be read. */
    fun softResetRefused(root: Path): Boolean?

    /**
     * The ignored files a cherry-pick or revert ([kind]) of [commit] would overwrite or delete; `null` if that cannot be
     * read.
     */
    fun pickOverwrites(
        root: Path,
        kind: GitPick,
        commit: String,
        firstParent: Boolean,
    ): List<String>?

    /** The untracked or ignored files a hard reset to [target] would overwrite; `null` if that cannot be read. */
    fun overwrittenFiles(
        root: Path,
        target: String,
    ): List<String>?

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

    /**
     * Commits the tracked changes [files] and the [untracked] files with [message], or amends `HEAD` with them if
     * [amend] (ADR 0048); [cancellation] stops git.
     */
    fun commit(
        root: Path,
        message: String,
        files: List<GitChangedFile>,
        untracked: List<String>,
        amend: Boolean,
        cancellation: GitCancellation,
    ): GitCommitResult

    /**
     * Pushes the local [branch] to the [target] branch of [remote] (ADR 0049); with [force] only if the remote branch
     * is at [expected] (a full hash, `null`: it must not exist); [setUpstream] makes the target the branch's upstream;
     * [cancellation] stops git.
     */
    fun push(
        root: Path,
        remote: String,
        branch: String,
        target: String,
        force: Boolean,
        expected: String?,
        setUpstream: Boolean,
        cancellation: GitCancellation,
    ): GitPushResult

    /**
     * The branch of [remote] (`refs/heads/…`) that the remote-tracking ref [trackingRef] (full refname) keeps, as the
     * fetch refspecs map it (ADR 0078); `null` if none does or git fails.
     */
    fun remoteBranch(
        root: Path,
        remote: String,
        trackingRef: String,
    ): String?

    /** Deletes the [refs] on [remote] in one push, branches with their lease (ADR 0078); [cancellation] stops git. */
    fun deleteRemoteRefs(
        root: Path,
        remote: String,
        refs: List<GitRemoteDeletion>,
        cancellation: GitCancellation,
    ): GitRemoteDeletionResult
}
