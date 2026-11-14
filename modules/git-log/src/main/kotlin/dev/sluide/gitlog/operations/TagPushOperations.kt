@file:Suppress("ktlint:standard:filename")

package dev.sluide.gitlog.operations

import dev.sluide.git.GitResult
import dev.sluide.git.GitTagPushOutcome
import dev.sluide.gitlog.data.GitLogWrites
import java.nio.file.Path

/**
 * Pushes the [tags] (short names) to the [remotes], one push per remote in their order (ADR 0173). A tag is never
 * overwritten: one the remote has at another object is refused and told so. It stops at the first remote whose push
 * fails, so the later ones get nothing. It changes no files and is cancellable, as a push: cancelled, the header says
 * the push may have reached the remote all the same.
 */
internal data class PushTags(
    val tags: List<String>,
    val remotes: List<String>,
) : RefOperation {
    private val what get() = tags.singleOrNull()?.let { "tag $it" } ?: "${tags.size} tags"

    override val progress get() = "Pushing $what to ${remotes.firstOrNull().orEmpty()}…"
    override val errorTitle get() = "Push Failed"
    override val failure get() = "Not every tag could be pushed."
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
        // By remote, the tags it got, to tell when a later remote fails.
        val sentTo = LinkedHashMap<String, List<String>>()
        for (remote in remotes) {
            if (context.cancellation.cancelled) return cancelled(sentTo, null)
            context.progress("Pushing $what to $remote…")
            val pushed = writes.pushTags(root, remote, tags, context.cancellation)
            if (pushInterrupted(pushed.cancelled, pushed.result, pushed.outcomes.isNotEmpty(), context)) {
                return cancelled(sentTo, remote)
            }
            if (!pushed.result.success) {
                val reasons =
                    tags.mapNotNull { tag ->
                        val outcome = pushed.outcomes[tag]
                        when (outcome?.outcome) {
                            GitTagPushOutcome.PUSHED, GitTagPushOutcome.UP_TO_DATE -> {
                                null
                            }

                            GitTagPushOutcome.EXISTS -> {
                                "$tag: $remote has another tag $tag; delete it there or rename yours."
                            }

                            GitTagPushOutcome.REJECTED -> {
                                "$tag: ${outcome.summary.ifEmpty { "not pushed" }}"
                            }

                            null -> {
                                "$tag: no answer from $remote"
                            }
                        }
                    }
                val skipped = remotes.drop(remotes.indexOf(remote) + 1)
                val skippedNote = if (skipped.isEmpty()) emptyList() else listOf("Skipped: ${joinedWithAnd(skipped)}.")
                val message =
                    (reasons + skippedNote + pushedBefore(sentTo) + listOf("", pushed.result.message))
                        .joinToString("\n")
                val header =
                    tags.singleOrNull()?.let { "Tag $it could not be pushed to $remote." }
                        ?: "Not every tag could be pushed to $remote."
                return RefOutcome(GitResult(false, message, changed = false), null, failure = header)
            }
            val sent = tags.filter { pushed.outcomes[it]?.outcome != GitTagPushOutcome.UP_TO_DATE }
            if (sent.isNotEmpty()) sentTo[remote] = sent
        }
        val where = joinedWithAnd(remotes)
        val done =
            when {
                sentTo.isNotEmpty() -> "Pushed $what to $where."
                tags.size == 1 -> "Tag ${tags.single()} is already on $where."
                else -> "The tags are already on $where."
            }
        return RefOutcome(GitResult(true, "", changed = false), done)
    }

    /**
     * What the header says when pushing was cancelled: what the remotes before got ([sentTo]), and that the push to
     * [interrupted] may have reached it, or, before a push started, only that it was cancelled.
     */
    private fun cancelled(
        sentTo: Map<String, List<String>>,
        interrupted: String?,
    ): RefOutcome {
        val text = interrupted?.let(::pushCancelled) ?: "Push cancelled."
        val done = (pushedBefore(sentTo) + text).joinToString(" ")
        return RefOutcome(GitResult(true, "", changed = false), done, cancelled = true)
    }
}

/** "Pushed to origin before that: v1, v2." for each remote of [sentTo]. */
private fun pushedBefore(sentTo: Map<String, List<String>>): List<String> =
    sentTo.map { (remote, tags) -> "Pushed to $remote before that: ${tags.joinToString(", ")}." }
