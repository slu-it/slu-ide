package dev.sluide.git

import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * One page of the git log: up to the asked number of [commits], newest first. [complete] means there are no more
 * after them; a first page that is empty and complete is a repository without commits yet.
 */
data class GitLogPage(
    val commits: List<GitCommit>,
    val complete: Boolean,
)

/**
 * The commits of a repository (ADR 0035): those of all local branches, remote-tracking branches, tags and `HEAD`, in
 * date order. Stash and notes commits are left out. Also the refs pointing at them and the details of a commit
 * (ADR 0037).
 */
@Component
class GitLog(
    private val git: GitCli,
) {
    /**
     * The commits after the first [skip] ones, at most [limit] of them, in the repository at [root]; `null` if git is
     * not available, fails or takes longer than 10 s. Runs git on the calling thread.
     */
    fun commits(
        root: Path,
        skip: Int,
        limit: Int,
    ): GitLogPage? {
        require(skip >= 0) { "skip must not be negative: $skip" }
        require(limit > 0) { "limit must be positive: $limit" }
        val output = git.log(root, skip, limit) ?: return null
        val commits = GitLogParser.parse(output)
        return GitLogPage(commits, complete = commits.size < limit)
    }

    /**
     * The branches, remote branches, tags and `HEAD` of the repository at [root] (ADR 0037); `null` if git is not
     * available, fails or takes too long. Runs git on the calling thread.
     */
    fun refs(root: Path): GitRefs? {
        val output = git.refs(root) ?: return null
        // Read right after the refs; either fails for an unborn HEAD (no commit) or a detached one (no branch).
        return GitRefsParser.parse(output, git.headCommit(root), git.currentBranch(root))
    }

    /**
     * The details of the commit [hash] (its full object name: 40 or 64 hexadecimal characters) in the repository at
     * [root]; `null` if git is not available, fails, takes longer than 3 s or there is no such commit. Runs git on the
     * calling thread.
     */
    fun details(
        root: Path,
        hash: String,
    ): GitCommitDetails? {
        require(HASH.matches(hash)) { "not a commit hash: $hash" }
        return git.show(root, hash)?.let(GitCommitDetailsParser::parse)
    }

    private companion object {
        val HASH = Regex("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}")
    }
}
