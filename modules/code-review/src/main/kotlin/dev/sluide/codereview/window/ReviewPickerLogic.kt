package dev.sluide.codereview.window

import dev.sluide.codereview.ResolvedReview
import dev.sluide.codereview.ReviewBase
import dev.sluide.codereview.ReviewCommit
import dev.sluide.codereview.model.ReviewSelection
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** One row of the commit picker: what it shows and what picking it selects ([commitIndex] for a commit). */
internal data class PickerEntry(
    val text: String,
    val selection: ReviewSelection,
    val commitIndex: Int? = null,
)

/** What the commit picker's button shows for [selection]. */
internal fun selectionLabel(
    review: ResolvedReview,
    selection: ReviewSelection,
): String =
    when (selection) {
        ReviewSelection.All -> {
            "All changes"
        }

        ReviewSelection.Uncommitted -> {
            "Uncommitted changes"
        }

        is ReviewSelection.Commits -> {
            val newest = review.commits.getOrNull(selection.newest)
            val oldest = review.commits.getOrNull(selection.oldest)
            when {
                newest == null || oldest == null -> "All changes"
                selection.newest == selection.oldest -> "${newest.shortHash} ${newest.subject}"
                else -> "${oldest.shortHash}..${newest.shortHash} (${selection.oldest - selection.newest + 1} commits)"
            }
        }
    }

/** The rows of the commit picker: all changes, the uncommitted ones when there are, then the commits newest first. */
internal fun pickerEntries(
    review: ResolvedReview,
    clock: Clock = Clock.systemDefaultZone(),
): List<PickerEntry> {
    val count = review.commits.size
    val noun = if (count == 1) "commit" else "commits"
    val all = "All changes ($count $noun${if (review.uncommitted) " + uncommitted" else ""})"
    val head =
        listOfNotNull(
            PickerEntry(all, ReviewSelection.All),
            if (review.uncommitted) PickerEntry("Uncommitted changes", ReviewSelection.Uncommitted) else null,
        )
    return head +
        review.commits.mapIndexed { index, commit ->
            PickerEntry(commitText(commit, clock), ReviewSelection.Commits(index, index), index)
        }
}

private fun commitText(
    commit: ReviewCommit,
    clock: Clock,
) = "${commit.shortHash}  ${commit.subject} — ${commit.author}, ${relativeDate(commit.time, clock)}"

/** The commits between [anchor] and [target] (indices into the commits, newest first), whichever comes first. */
internal fun commitRange(
    anchor: Int,
    target: Int,
): ReviewSelection.Commits = ReviewSelection.Commits(minOf(anchor, target), maxOf(anchor, target))

/** The commit index a Shift range starts from when nothing was clicked yet: the newest picked commit, else none. */
internal fun anchorOf(selection: ReviewSelection): Int? = (selection as? ReviewSelection.Commits)?.newest

/** [time] as "just now", "5 minutes ago", "yesterday", "3 days ago", or `yyyy-MM-dd` after 30 days. */
internal fun relativeDate(
    time: Instant,
    clock: Clock,
): String {
    val age = Duration.between(time, clock.instant())
    val days = age.toDays()
    return when {
        age.toMinutes() < 1 -> "just now"
        age.toHours() < 1 -> plural(age.toMinutes(), "minute")
        days < 1 -> plural(age.toHours(), "hour")
        days == 1L -> "yesterday"
        days <= MAX_RELATIVE_DAYS -> "$days days ago"
        else -> LocalDate.ofInstant(time, clock.zone).toString()
    }
}

private fun plural(
    count: Long,
    unit: String,
) = "$count $unit${if (count == 1L) "" else "s"} ago"

private const val MAX_RELATIVE_DAYS = 30L

/** The base picker's items: the default bases first, then the rest. */
internal fun baseGroups(bases: List<ReviewBase>): Pair<List<ReviewBase>, List<ReviewBase>> =
    bases.partition { it.isDefault }

/** The base picker's button text. */
internal fun baseButtonText(review: ResolvedReview?): String =
    review?.base?.let { "into: ${it.label}" } ?: "choose a base"
