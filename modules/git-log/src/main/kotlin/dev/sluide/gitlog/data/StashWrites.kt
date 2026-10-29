package dev.sluide.gitlog.data

import dev.sluide.git.GitResolveResult
import dev.sluide.git.GitStash
import dev.sluide.git.GitStashResult
import dev.sluide.git.GitStashSources
import dev.sluide.git.GitStashes
import dev.sluide.git.GitUncheckedConflict
import java.nio.file.Path

/**
 * How the git log stashes, pops, applies and drops (ADR 0107), and marks resolved the conflicts git leaves without
 * anything in progress; part of [GitLogWrites], [GitStashes] in the application, a fake in tests. Called off the FX
 * thread.
 */
internal interface StashWrites {
    /** The stashes, newest first; `null` if they cannot be read. */
    fun stashes(root: Path): List<GitStash>?

    /** The current branch and why nothing can be stashed now, before the Stash Changes dialog asks. */
    fun stashSources(root: Path): GitStashSources

    /**
     * Stashes the local changes with [message] (or git's own), the untracked files if [includeUntracked], only the
     * [paths] if given.
     */
    fun pushStash(
        root: Path,
        message: String?,
        includeUntracked: Boolean,
        paths: List<String>?,
    ): GitStashResult

    /** Pops [stash] if it is still `stash@{n}`. */
    fun popStash(
        root: Path,
        stash: GitStash,
    ): GitStashResult

    /** Applies [stash] if it is still `stash@{n}`, keeping it. */
    fun applyStash(
        root: Path,
        stash: GitStash,
    ): GitStashResult

    /** Drops [stash] if it is still `stash@{n}`. */
    fun dropStash(
        root: Path,
        stash: GitStash,
    ): GitStashResult

    /**
     * Stages the files with conflicts while nothing else is in progress, once checked for markers; those that cannot be
     * checked only if [confirmed].
     */
    fun markResolved(
        root: Path,
        confirmed: Set<GitUncheckedConflict>,
    ): GitResolveResult
}

/** [StashWrites] with the git command line. */
internal class GitStashWrites(
    private val stashes: GitStashes,
) : StashWrites {
    override fun stashes(root: Path) = stashes.list(root)

    override fun stashSources(root: Path) = stashes.sources(root)

    override fun pushStash(
        root: Path,
        message: String?,
        includeUntracked: Boolean,
        paths: List<String>?,
    ) = stashes.push(root, message, includeUntracked, paths)

    override fun popStash(
        root: Path,
        stash: GitStash,
    ) = stashes.pop(root, stash)

    override fun applyStash(
        root: Path,
        stash: GitStash,
    ) = stashes.apply(root, stash)

    override fun dropStash(
        root: Path,
        stash: GitStash,
    ) = stashes.drop(root, stash)

    override fun markResolved(
        root: Path,
        confirmed: Set<GitUncheckedConflict>,
    ) = stashes.markResolved(root, confirmed)
}
