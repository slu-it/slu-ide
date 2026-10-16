package dev.sluide.gitlog

import dev.sluide.git.GitCommitDetails
import dev.sluide.git.GitLog
import dev.sluide.git.GitLogPage
import dev.sluide.git.GitRefs
import java.nio.file.Path

/**
 * Where the git log reads a repository from; [GitLog] in the application, a fake in tests. Called off the FX thread.
 */
internal interface GitLogSource {
    /** The commits after the first [skip], at most [limit]; `null` on failure. */
    fun page(
        root: Path,
        skip: Int,
        limit: Int,
    ): GitLogPage?

    /** The refs of the repository; `null` on failure. */
    fun refs(root: Path): GitRefs?

    /** The details of the commit [hash]; `null` on failure. */
    fun details(
        root: Path,
        hash: String,
    ): GitCommitDetails?
}

/** Reads with the git command line. */
internal class GitLogCliSource(
    private val gitLog: GitLog,
) : GitLogSource {
    override fun page(
        root: Path,
        skip: Int,
        limit: Int,
    ) = gitLog.commits(root, skip, limit)

    override fun refs(root: Path) = gitLog.refs(root)

    override fun details(
        root: Path,
        hash: String,
    ) = gitLog.details(root, hash)
}
