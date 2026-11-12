package dev.sluide.codereview

/** The entries picked in the commit picker. */
sealed interface ReviewSelection {
    /** The whole review. */
    data object All : ReviewSelection

    /** The uncommitted changes. */
    data object Uncommitted : ReviewSelection

    /** The commits from index [newest] to index [oldest] of [ResolvedReview.commits] (newest first). */
    data class Commits(
        val newest: Int,
        val oldest: Int,
    ) : ReviewSelection
}

/**
 * The diff range of [selection]: from (`null` for the empty tree) and to. A range of commits runs from the oldest
 * one's first parent to the newest one. Throws [IllegalArgumentException] for indices outside [ResolvedReview.commits]
 * or a newest index after the oldest.
 */
fun ResolvedReview.range(selection: ReviewSelection): Pair<String?, ReviewRevision> =
    when (selection) {
        ReviewSelection.All -> {
            from to if (uncommitted) ReviewRevision.WorkingTree else ReviewRevision.Commit(head)
        }

        ReviewSelection.Uncommitted -> {
            head to ReviewRevision.WorkingTree
        }

        is ReviewSelection.Commits -> {
            require(selection.newest in 0..selection.oldest && selection.oldest < commits.size) {
                "No commits ${selection.newest}..${selection.oldest} among ${commits.size}"
            }
            commits[selection.oldest].firstParent to ReviewRevision.Commit(commits[selection.newest].hash)
        }
    }
