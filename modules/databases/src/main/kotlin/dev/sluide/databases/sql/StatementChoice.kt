package dev.sluide.databases.sql

import dev.sluide.databases.connect.DatabaseKind

/** What the Run action of a console executes. */
sealed interface RunChoice {
    data class Single(
        val statement: SqlStatement,
    ) : RunChoice

    /** Several statements and no selection: offer [atCaret] (first) and [all]. */
    data class Choose(
        val atCaret: SqlStatement,
        val all: List<SqlStatement>,
    ) : RunChoice

    /** Several statements in a selection: run all in order, without a chooser. Ranges are relative to the whole text. */
    data class All(
        val statements: List<SqlStatement>,
    ) : RunChoice

    data object None : RunChoice
}

object StatementChoice {
    /**
     * What Run executes. The selection is [selectionStart, selectionEnd) (exclusive end like JavaFX `IndexRange`;
     * equal values = no selection): it is split, one statement gives `Single` (the selected text, trimmed, with its
     * own range), several give `All`.
     * Without a selection: no statements give `None`, one `Single`, several `Choose` with the statement at [caret]
     * (the one containing it, else the nearest before it, else the first).
     */
    fun toRun(
        text: String,
        selectionStart: Int,
        selectionEnd: Int,
        caret: Int,
        kind: DatabaseKind,
    ): RunChoice {
        if (selectionStart != selectionEnd) {
            val from = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
            val to = maxOf(selectionStart, selectionEnd).coerceIn(from, text.length)
            val statements =
                SqlSplitter
                    .split(text.substring(from, to), kind)
                    .map { it.copy(start = it.start + from, end = it.end + from) }
            return fromStatements(statements) { RunChoice.All(statements) }
        }
        val statements = SqlSplitter.split(text, kind)
        return fromStatements(statements) {
            val atCaret =
                statements.firstOrNull { caret in it.start..it.end }
                    ?: statements.lastOrNull { it.end <= caret }
                    ?: statements.first()
            RunChoice.Choose(atCaret, statements)
        }
    }

    private inline fun fromStatements(
        statements: List<SqlStatement>,
        many: () -> RunChoice,
    ): RunChoice =
        when (statements.size) {
            0 -> RunChoice.None
            1 -> RunChoice.Single(statements.single())
            else -> many()
        }
}
