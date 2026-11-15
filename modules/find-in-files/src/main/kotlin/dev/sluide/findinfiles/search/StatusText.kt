package dev.sluide.findinfiles.search

import java.util.Locale

/** What the status line under the query tells. */
internal sealed interface SearchState {
    /** The query is too short to search: no status. */
    data object Idle : SearchState

    /** A walk runs and has listed [matches] in [files] files so far. */
    data class Running(
        val matches: Int,
        val files: Int,
    ) : SearchState

    /** The walk ended with [outcome]; [limits] are the ones it ran with. */
    data class Finished(
        val outcome: SearchOutcome,
        val limits: SearchLimits = SearchLimits(),
    ) : SearchState

    /** The walk threw. */
    data object Failed : SearchState
}

/** The status line for [state]. */
internal fun statusText(state: SearchState): String =
    when (state) {
        SearchState.Idle -> {
            ""
        }

        SearchState.Failed -> {
            "Search failed"
        }

        is SearchState.Running -> {
            if (state.matches == 0) "Searching…" else "${found(state.matches, state.files)}…"
        }

        is SearchState.Finished -> {
            when (val outcome = state.outcome) {
                is SearchOutcome.Invalid -> outcome.message
                is SearchOutcome.Done -> doneText(outcome, state.limits) + skippedText(outcome.skipped, state.limits)
            }
        }
    }

private fun doneText(
    outcome: SearchOutcome.Done,
    limits: SearchLimits,
): String =
    when {
        outcome.totalCapped -> {
            "First ${count(outcome.matches)} matches in ${files(outcome.files)}, type more to narrow"
        }

        outcome.visitCapped -> {
            "${matches(outcome.matches)} in the first ${count(limits.maxVisited)} files, type more to narrow"
        }

        outcome.matches == 0 -> {
            "No matches"
        }

        else -> {
            found(outcome.matches, outcome.files)
        }
    }

private fun found(
    matches: Int,
    files: Int,
) = "${matches(matches)} in ${files(files)}"

private fun matches(count: Int) =
    when (count) {
        0 -> "No matches"
        1 -> "1 match"
        else -> "${count(count)} matches"
    }

private fun files(count: Int) = if (count == 1) "1 file" else "${count(count)} files"

private fun skippedText(
    skipped: Int,
    limits: SearchLimits,
): String {
    val why = "(over ${size(limits.maxBytes)} or too slow)"
    return when (skipped) {
        0 -> ""
        1 -> " · 1 file skipped $why"
        else -> " · ${count(skipped)} files skipped $why"
    }
}

private const val MIB = 1024 * 1024

/** [bytes] in whole MiB if they are, else in bytes. */
private fun size(bytes: Int): String {
    val wholeMebibytes = bytes >= MIB && bytes % MIB == 0
    return if (wholeMebibytes) "${count(bytes / MIB)} MiB" else "${count(bytes)} bytes"
}

/**
 * [number] plain below 10 000 ("1000"), else in groups of three separated by spaces, as the explorer search writes
 * 200 000.
 */
private fun count(number: Int): String =
    if (number < 10_000) number.toString() else "%,d".format(Locale.ROOT, number).replace(',', ' ')
