package dev.sluide.gitlog.data

import dev.sluide.git.GitResolveResult
import dev.sluide.git.GitStash
import dev.sluide.git.GitStashResult
import dev.sluide.git.GitStashSources
import dev.sluide.git.GitUncheckedConflict
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A fake of the stash writes (ADR 0107), part of [FakeWrites]: records every call as a line (`stash pop stash@{0}`,
 * `stash push -u -m msg a.txt b.txt`) and answers with what the test set.
 */
internal class FakeStashWrites : StashWrites {
    val calls = CopyOnWriteArrayList<String>()

    @Volatile
    var stashes: List<GitStash>? = emptyList()

    @Volatile
    var sources = GitStashSources("main", null)

    /** What push, pop, apply and drop answer, by their line. */
    @Volatile
    var result: (String) -> GitStashResult = { GitStashResult.Done }

    @Volatile
    var resolveResult: (Set<GitUncheckedConflict>) -> GitResolveResult = { GitResolveResult.Done(listOf("a.txt")) }

    private fun call(line: String): GitStashResult {
        calls += line
        return result(line)
    }

    override fun stashes(root: Path): List<GitStash>? {
        calls += "stash list"
        return stashes
    }

    override fun stashSources(root: Path): GitStashSources {
        calls += "stash sources"
        return sources
    }

    override fun pushStash(
        root: Path,
        message: String?,
        includeUntracked: Boolean,
        paths: List<String>?,
    ): GitStashResult {
        val options = listOfNotNull("-u".takeIf { includeUntracked }, message?.let { "-m $it" }) + paths.orEmpty()
        return call((listOf("stash push") + options).joinToString(" "))
    }

    override fun popStash(
        root: Path,
        stash: GitStash,
    ) = call("stash pop ${stash.name} ${stash.commit}")

    override fun applyStash(
        root: Path,
        stash: GitStash,
    ) = call("stash apply ${stash.name} ${stash.commit}")

    override fun dropStash(
        root: Path,
        stash: GitStash,
    ) = call("stash drop ${stash.name} ${stash.commit}")

    override fun markResolved(
        root: Path,
        confirmed: Set<GitUncheckedConflict>,
    ): GitResolveResult {
        calls += "mark resolved${if (confirmed.isEmpty()) "" else " as on disk"}"
        return resolveResult(confirmed)
    }
}
