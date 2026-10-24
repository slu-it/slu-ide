package dev.sluide.git.cli

import dev.sluide.git.GitResult
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** [message] without git's `hint:` lines; [message] itself if it has nothing else. */
internal fun withoutHints(message: String): String =
    message
        .lines()
        .filterNot { it.startsWith("hint:") }
        .joinToString("\n")
        .trim()
        .ifEmpty { message }

/**
 * The message of a write that git did not finish within [timeout] and was stopped: for one that [writesFiles], also that
 * some of them may be changed and others not (git changes files one by one), and how to see which.
 */
internal fun timedOut(
    timeout: Duration,
    writesFiles: Boolean,
): String {
    val stopped = "git did not finish within ${shown(timeout)}."
    if (!writesFiles) return stopped
    return "$stopped Files and the index may be partly changed; check git status in a terminal."
}

/** [duration] as a message says it: "60 s", "90 s", "10 min". */
internal fun shown(duration: Duration): String =
    if (duration > 1.minutes && duration.inWholeSeconds % 60 == 0L) {
        "${duration.inWholeMinutes} min"
    } else {
        "${duration.inWholeSeconds} s"
    }

/**
 * What a write of git gave: its [result], its [exitValue] (`null` if it did not end by itself), its standard [output]
 * and its standard [errors] (without hints), and whether it was stopped as it [timedOut].
 */
internal data class GitWriteOutput(
    val result: GitResult,
    val exitValue: Int?,
    val output: String = "",
    val errors: String = "",
    val timedOut: Boolean = false,
)
