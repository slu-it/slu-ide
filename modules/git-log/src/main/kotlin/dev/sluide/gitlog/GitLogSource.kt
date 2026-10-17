package dev.sluide.gitlog

import dev.sluide.git.GitChangedFile
import dev.sluide.git.GitCommit
import dev.sluide.git.GitCommitDetails
import dev.sluide.git.GitComparisonCounts
import dev.sluide.git.GitFileDiff
import dev.sluide.git.GitInProgress
import dev.sluide.git.GitLog
import dev.sluide.git.GitLogFilter
import dev.sluide.git.GitLogPage
import dev.sluide.git.GitRefs
import dev.sluide.git.GitRemotes
import dev.sluide.git.GitRevisionResolution
import dev.sluide.git.GitTreePath
import dev.sluide.git.GitWorkingTreeChanges
import java.nio.file.Path

/**
 * Where the git log reads a repository from; [GitLog] in the application, a fake in tests. Called off the FX thread.
 */
internal interface GitLogSource {
    /** The commits that pass [filter] after the first [skip], at most [limit]; `null` on failure. */
    fun page(
        root: Path,
        filter: GitLogFilter,
        skip: Int,
        limit: Int,
    ): GitLogPage?

    /** The commit the text of [filter] names as a hash, if it passes the filter's users, paths and no merges. */
    fun hashMatch(
        root: Path,
        filter: GitLogFilter,
    ): GitCommit?

    /** The `user.name` of the repository; `null` if it is not set or on failure. */
    fun userName(root: Path): String?

    /** The folders and files of `HEAD`'s tree, empty without commits; `null` on failure. */
    fun paths(root: Path): List<GitTreePath>?

    /** The refs of the repository; `null` on failure. */
    fun refs(root: Path): GitRefs?

    /** What git is in the middle of (a cherry-pick or revert, a merge…); `null` if nothing or on failure. */
    fun pending(root: Path): GitInProgress? = null

    /** The names of all remotes (`git remote`, ADR 0043); `null` on failure (or if not known). */
    fun remotes(root: Path): List<String>? = null

    /** The details of the commit [hash]; `null` on failure. */
    fun details(
        root: Path,
        hash: String,
    ): GitCommitDetails?

    /**
     * The full refnames of the local and remote branches that contain the commit [hash] (ADR 0045); `null` on failure
     * (or if not known).
     */
    fun branchesContaining(
        root: Path,
        hash: String,
    ): List<String>? = null

    /** What each of [revisions] names, a commit or not (ADR 0045); `null` on failure (or if not known). */
    fun resolveCommits(
        root: Path,
        revisions: List<String>,
    ): List<GitRevisionResolution>? = null

    /** The files [commit] changed against [firstParent] (against nothing if `null`); `null` on failure. */
    fun changedFiles(
        root: Path,
        commit: String,
        firstParent: String?,
    ): List<GitChangedFile>?

    /** What [commit] changed in [file] against [firstParent]; `null` on failure. */
    fun fileDiff(
        root: Path,
        commit: String,
        firstParent: String?,
        file: GitChangedFile,
    ): GitFileDiff?

    /**
     * How many commits that pass [filter] (not its revisions) [revision] has that `HEAD` has not, and the other way
     * round (ADR 0044); `null` on failure.
     */
    fun comparisonCounts(
        root: Path,
        revision: String,
        filter: GitLogFilter,
    ): GitComparisonCounts? = null

    /** The tracked files that differ between the commit [revision] names and the working tree; `null` on failure. */
    fun workingTreeChanges(
        root: Path,
        revision: String,
    ): GitWorkingTreeChanges? = null

    /** How [file] differs between [commit] and the working tree (ADR 0044); `null` on failure. */
    fun workingTreeFileDiff(
        root: Path,
        commit: String,
        file: GitChangedFile,
    ): GitFileDiff? = null
}

/** Reads with the git command line. */
internal class GitLogCliSource(
    private val gitLog: GitLog,
    private val gitRemotes: GitRemotes,
) : GitLogSource {
    override fun page(
        root: Path,
        filter: GitLogFilter,
        skip: Int,
        limit: Int,
    ) = gitLog.commits(root, filter, skip, limit)

    override fun hashMatch(
        root: Path,
        filter: GitLogFilter,
    ) = gitLog.hashMatch(root, filter)

    override fun userName(root: Path) = gitLog.userName(root)

    override fun paths(root: Path) = gitLog.paths(root)

    override fun refs(root: Path) = gitLog.refs(root)

    override fun pending(root: Path) = gitLog.inProgress(root)

    override fun remotes(root: Path) = gitRemotes.names(root)

    override fun details(
        root: Path,
        hash: String,
    ) = gitLog.details(root, hash)

    override fun branchesContaining(
        root: Path,
        hash: String,
    ) = gitLog.branchesContaining(root, hash)

    override fun resolveCommits(
        root: Path,
        revisions: List<String>,
    ) = gitLog.resolveCommits(root, revisions)

    override fun changedFiles(
        root: Path,
        commit: String,
        firstParent: String?,
    ) = gitLog.changedFiles(root, commit, firstParent)

    override fun fileDiff(
        root: Path,
        commit: String,
        firstParent: String?,
        file: GitChangedFile,
    ) = gitLog.fileDiff(root, commit, firstParent, file)

    override fun comparisonCounts(
        root: Path,
        revision: String,
        filter: GitLogFilter,
    ) = gitLog.comparisonCounts(root, revision, filter)

    override fun workingTreeChanges(
        root: Path,
        revision: String,
    ) = gitLog.workingTreeChanges(root, revision)

    override fun workingTreeFileDiff(
        root: Path,
        commit: String,
        file: GitChangedFile,
    ) = gitLog.workingTreeFileDiff(root, commit, file)
}
