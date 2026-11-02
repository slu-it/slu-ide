package dev.sluide.databases.ui.result

import dev.sluide.databases.session.StatementOutcome
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One line of the Output tab. */
internal data class OutputLine(
    val text: String,
    val error: Boolean,
)

/** The text of the Output tab's lines. */
internal object OutputText {
    const val MAX_STATEMENT = 200
    private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val WHITESPACE = Regex("\\s+")

    /** The statement on one line, cut at [MAX_STATEMENT] characters. */
    fun statement(sql: String): String {
        val one = sql.trim().replace(WHITESPACE, " ")
        return if (one.length > MAX_STATEMENT) one.take(MAX_STATEMENT) + "…" else one
    }

    /** "23 ms" below a second, else "2.1 s". */
    fun duration(millis: Long): String =
        if (millis < 1000) "$millis ms" else String.format(Locale.ROOT, "%.1f s", millis / 1000.0)

    private fun rows(count: Long) = if (count == 1L) "1 row" else "$count rows"

    /** "time  statement  →  outcome". */
    fun line(
        time: LocalTime,
        outcome: StatementOutcome,
    ): OutputLine {
        val result =
            when (outcome) {
                is StatementOutcome.Rows -> {
                    val n =
                        outcome.firstPage.rows.size
                            .toLong()
                    val more = if (outcome.firstPage.last) "" else " (more)"
                    "${rows(n)}$more in ${duration(outcome.millis)}"
                }

                is StatementOutcome.Updated -> {
                    "${rows(outcome.count)} updated in ${duration(outcome.millis)}"
                }

                is StatementOutcome.Cancelled -> {
                    "cancelled after ${duration(outcome.millis)}"
                }

                is StatementOutcome.Failed -> {
                    outcome.message.trim().replace(WHITESPACE, " ")
                }
            }
        return OutputLine(
            "${TIME.format(time)}  ${statement(outcome.sql)}  →  $result",
            outcome is StatementOutcome.Failed,
        )
    }

    /** "time  text" for a note such as "Committed". */
    fun note(
        time: LocalTime,
        text: String,
        error: Boolean,
    ): OutputLine = OutputLine("${TIME.format(time)}  $text", error)
}
