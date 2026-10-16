package dev.sluide.gitlog

import dev.sluide.git.GitBranchDeletion
import dev.sluide.git.GitBranchTip
import dev.sluide.git.GitBranches
import dev.sluide.git.GitResult
import java.nio.file.Path

/**
 * How the git log changes a repository (ADR 0041); [GitBranches] in the application, a fake in tests. Called off the
 * FX thread, except [canPass].
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
}

/** Changes the repository with the git command line. */
internal class GitLogCliWrites(
    private val branches: GitBranches,
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
}
