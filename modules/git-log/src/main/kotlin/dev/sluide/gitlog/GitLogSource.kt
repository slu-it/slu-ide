package dev.sluide.gitlog

import dev.sluide.git.GitChangedFile
import dev.sluide.git.GitCommit
import dev.sluide.git.GitCommitDetails
import dev.sluide.git.GitFileDiff
import dev.sluide.git.GitLog
import dev.sluide.git.GitLogFilter
import dev.sluide.git.GitLogPage
import dev.sluide.git.GitRefs
import dev.sluide.git.GitTreePath
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

    /** The details of the commit [hash]; `null` on failure. */
    fun details(
        root: Path,
        hash: String,
    ): GitCommitDetails?

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
}

/** Reads with the git command line. */
internal class GitLogCliSource(
    private val gitLog: GitLog,
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

    override fun details(
        root: Path,
        hash: String,
    ) = gitLog.details(root, hash)

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
}
