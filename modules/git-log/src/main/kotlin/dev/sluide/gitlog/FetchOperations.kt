package dev.sluide.gitlog

import dev.sluide.git.GitFetchChanges
import dev.sluide.git.GitFetchResult
import dev.sluide.git.GitResult
import java.nio.file.Path

/**
 * Fetches [remote], or all remotes but those with `skipFetchAll` (`null`), one after the other (ADR 0043): one that
 * fails does not stop the others. Cancelling stops the fetch going on and skips the remotes after it. The header says
 * which remote is fetched, then what changed; the remotes that failed are listed in the error dialog with git's
 * messages.
 */
internal data class Fetch(
    val remote: String? = null,
) : RefOperation {
    override val progress get() = remote?.let { fetching(it) } ?: "Fetching…"
    override val errorTitle get() = "Fetch Failed"
    override val failure get() = remote?.let { "$it could not be fetched." } ?: "Not every remote could be fetched."
    override val fileChange get() = null
    override val cancellable get() = true

    override fun run(
        writes: GitLogWrites,
        root: Path,
    ) = run(writes, root, RunContext())

    override fun run(
        writes: GitLogWrites,
        root: Path,
        context: RunContext,
    ): RefOutcome {
        val remotes =
            remote?.let(::listOf)
                ?: writes.remotes(root)
                ?: return RefOutcome(
                    GitResult(false, "The remotes of the repository could not be read."),
                    null,
                    failure = "Nothing was fetched.",
                )
        if (remotes.isEmpty()) return RefOutcome(GitResult(true, ""), NO_REMOTES)
        val results = LinkedHashMap<String, GitFetchResult>()
        for (name in remotes) {
            if (context.cancellation.cancelled) break
            context.progress(fetching(name))
            val result = writes.fetch(root, name, context.cancellation)
            results[name] = result
            if (result.cancelled) break
        }
        val cancelled =
            context.cancellation.cancelled && (results.size < remotes.size || results.values.any { it.cancelled })
        val failed = results.filterValues { !it.result.success && !it.cancelled }
        val done = fetchSummary(results, cancelled)
        if (failed.isEmpty()) return RefOutcome(GitResult(true, ""), done)
        val message =
            failed.entries
                .singleOrNull()
                ?.value
                ?.result
                ?.message
                ?: failed.entries.joinToString("\n\n") { (name, result) -> "$name:\n${result.result.message}" }
        val failure =
            failed.keys.singleOrNull()?.let { "$it could not be fetched." }
                ?: "${failed.size} remotes could not be fetched: ${failed.keys.joinToString(", ")}."
        return RefOutcome(GitResult(false, message), done, failure = failure)
    }
}

/** What the header says when there is no remote to fetch. */
internal const val NO_REMOTES = "No remotes to fetch."

private fun fetching(remote: String) = "Fetching $remote…"

/**
 * What the header says after fetching (ADR 0043), from the [results] by remote, in the order they were fetched (the
 * cancelled one last), and whether the fetch was [cancelled]: "Fetched origin: 1 new branch, 2 branches updated.",
 * "Fetched 2 remotes: nothing new.", "Fetched 1 of 2 remotes: …" when others failed, "Fetched origin with errors: …"
 * when all failed but changed refs all the same (git refused a tag but moved the branches), or "Fetch cancelled." with
 * what changed before; `null` when nothing was fetched and nothing changed. The changes of all remotes count, also of
 * those that failed or were cancelled; a remote whose changes could not be read adds none.
 */
internal fun fetchSummary(
    results: Map<String, GitFetchResult>,
    cancelled: Boolean,
): String? {
    val known = results.values.mapNotNull { it.changes }
    val changes = known.fold(GitFetchChanges(), GitFetchChanges::plus)
    val words = changeWords(changes)
    if (cancelled) return if (changes.none) "Fetch cancelled." else "Fetch cancelled. Fetched so far: $words."
    val fetched = results.filterValues { it.result.success }.keys
    val attempted = results.size
    val details = if (known.isEmpty()) "" else ": ${if (changes.none) "nothing new" else words}"
    return when {
        fetched.size == attempted -> "Fetched ${remotesLabel(results.keys)}$details."
        fetched.isNotEmpty() -> "Fetched ${fetched.singleOrNull() ?: "${fetched.size} of $attempted remotes"}$details."
        changes.none -> null
        else -> "Fetched ${remotesLabel(results.keys)} with errors: $words."
    }
}

/** The one remote's name, else "2 remotes". */
private fun remotesLabel(remotes: Collection<String>) = remotes.singleOrNull() ?: "${remotes.size} remotes"

/** "1 new branch, 2 branches updated, 1 branch pruned, 3 new tags"; empty for no changes. */
private fun changeWords(changes: GitFetchChanges): String =
    listOfNotNull(
        count(changes.newBranches, "new branch", "new branches"),
        count(changes.updatedBranches, "branch updated", "branches updated"),
        count(changes.prunedBranches, "branch pruned", "branches pruned"),
        count(changes.newTags, "new tag", "new tags"),
        count(changes.updatedTags, "tag updated", "tags updated"),
        count(changes.prunedTags, "tag pruned", "tags pruned"),
    ).joinToString(", ")

private fun count(
    count: Int,
    one: String,
    many: String,
) = when (count) {
    0 -> null
    1 -> "1 $one"
    else -> "$count $many"
}
