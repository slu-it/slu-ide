package dev.sluide.gitlog.data

import dev.sluide.git.GitBranches
import dev.sluide.git.GitCancellation
import dev.sluide.git.GitChangedFile
import dev.sluide.git.GitCommits
import dev.sluide.git.GitMergeMode
import dev.sluide.git.GitMerges
import dev.sluide.git.GitPick
import dev.sluide.git.GitPulls
import dev.sluide.git.GitPush
import dev.sluide.git.GitRebases
import dev.sluide.git.GitRemoteDeletion
import dev.sluide.git.GitRemotes
import dev.sluide.git.GitResetMode
import dev.sluide.git.GitRewords
import dev.sluide.git.GitUncheckedConflict
import java.nio.file.Path

/** Changes the repository with the git command line. */
internal class GitLogCliWrites(
    private val branches: GitBranches,
    private val commits: GitCommits,
    private val rebases: GitRebases,
    private val merges: GitMerges,
    private val pulls: GitPulls,
    private val rewords: GitRewords,
    private val gitRemotes: GitRemotes,
    private val gitPush: GitPush,
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

    override fun leftBehind(
        root: Path,
        target: String,
    ) = branches.leftBehind(root, target)

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

    override fun rebase(
        root: Path,
        branch: String,
        onto: String,
    ) = rebases.rebase(root, branch, onto)

    override fun rebaseMergeCount(
        root: Path,
        onto: String,
    ) = rebases.mergeCount(root, onto)

    override fun continueRebase(
        root: Path,
        confirmed: Set<GitUncheckedConflict>,
    ) = rebases.continueRebase(root, confirmed)

    override fun skipRebase(root: Path) = rebases.skipRebase(root)

    override fun abortRebase(root: Path) = rebases.abortRebase(root)

    override fun merge(
        root: Path,
        branch: String,
        ref: String,
        mode: GitMergeMode,
        message: String?,
        autostash: Boolean,
    ) = merges.merge(root, branch, ref, mode, message, autostash)

    override fun continueMerge(
        root: Path,
        confirmed: Set<GitUncheckedConflict>,
    ) = merges.continueMerge(root, confirmed)

    override fun abortMerge(root: Path) = merges.abortMerge(root)

    override fun pullSources(root: Path) = pulls.sources(root)

    override fun remoteBranchExists(
        root: Path,
        ref: String,
    ) = pulls.exists(root, ref)

    override fun commitCount(
        root: Path,
        from: String,
        to: String,
    ) = pulls.commitCount(root, from, to)

    override fun currentBranch(root: Path) = pulls.currentBranch(root)

    override fun forkPoint(
        root: Path,
        ref: String,
    ) = pulls.forkPoint(root, ref)

    override fun pullRebase(
        root: Path,
        branch: String,
        onto: String,
        autostash: Boolean,
    ) = pulls.rebase(root, branch, onto, autostash)

    override fun rewordCheck(
        root: Path,
        branch: String,
        commit: String,
    ) = rewords.check(root, branch, commit)

    override fun reword(
        root: Path,
        branch: String,
        commit: String,
        message: String,
    ) = rewords.reword(root, branch, commit, message)

    override fun unreachableCount(
        root: Path,
        target: String,
    ) = commits.unreachableCount(root, target)

    override fun localChangeCount(root: Path) = commits.localChangeCount(root)

    override fun stagedOnlyCount(root: Path) = commits.stagedOnlyCount(root)

    override fun softResetRefused(root: Path) = commits.softResetRefused(root)

    override fun pickOverwrites(
        root: Path,
        kind: GitPick,
        commit: String,
        firstParent: Boolean,
    ) = commits.pickOverwrites(root, kind, commit, firstParent)

    override fun overwrittenFiles(
        root: Path,
        target: String,
    ) = commits.overwrittenFiles(root, target)

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

    override fun commit(
        root: Path,
        message: String,
        files: List<GitChangedFile>,
        untracked: List<String>,
        amend: Boolean,
        cancellation: GitCancellation,
    ) = commits.commit(root, message, files, untracked, amend, cancellation)

    override fun push(
        root: Path,
        remote: String,
        branch: String,
        target: String,
        force: Boolean,
        expected: String?,
        setUpstream: Boolean,
        cancellation: GitCancellation,
    ) = gitPush.push(root, remote, branch, target, force, expected, setUpstream, cancellation)

    override fun remoteBranch(
        root: Path,
        remote: String,
        trackingRef: String,
    ) = gitPush.remoteBranch(root, remote, trackingRef)

    override fun deleteRemoteRefs(
        root: Path,
        remote: String,
        refs: List<GitRemoteDeletion>,
        cancellation: GitCancellation,
    ) = gitPush.deleteRemoteRefs(root, remote, refs, cancellation)
}
