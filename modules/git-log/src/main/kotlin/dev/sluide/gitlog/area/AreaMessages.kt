package dev.sluide.gitlog.area

import dev.sluide.gitlog.data.CommitMissing
import dev.sluide.gitlog.data.ComparisonEnd
import dev.sluide.gitlog.data.GitLogPages
import dev.sluide.gitlog.data.GitLogState
import dev.sluide.gitlog.data.LogComparison
import java.util.Locale

/**
 * Why the commit called [name] is not in the table, as [GitLogPages.loadUntil] found it [missing]; [comparing] while a
 * comparison is shown, [filterIsDefault] if the filters hide nothing.
 */
internal fun missingMessage(
    name: String,
    missing: CommitMissing,
    comparing: Boolean,
    filterIsDefault: Boolean,
): String =
    when {
        missing == CommitMissing.UNREADABLE -> {
            GitLogState.FAILED.message
        }

        missing == CommitMissing.BEYOND_LIMIT -> {
            "$name is more than ${"%,d".format(Locale.ENGLISH, GitLogPages.SEEK_LIMIT)} commits down."
        }

        comparing -> {
            "$name is not in this comparison."
        }

        filterIsDefault -> {
            "$name is not in the log."
        }

        else -> {
            "$name is hidden by the filters."
        }
    }

/** What the header says once the log loaded again after the [comparison] [end]ed by itself. */
internal fun endMessage(
    comparison: LogComparison,
    end: ComparisonEnd,
): String {
    val name = comparison.ref.shortName
    return when (end) {
        ComparisonEnd.GONE -> "$name no longer exists."
        ComparisonEnd.CURRENT -> "$name is the current branch now."
    }
}
