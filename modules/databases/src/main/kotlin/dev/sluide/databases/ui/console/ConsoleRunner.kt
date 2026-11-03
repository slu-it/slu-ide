package dev.sluide.databases.ui.console

import dev.sluide.databases.session.StatementOutcome
import dev.sluide.databases.sql.QueryHistory
import dev.sluide.databases.sql.RunChoice
import dev.sluide.databases.sql.SqlStatement
import dev.sluide.databases.sql.StatementChoice
import dev.sluide.databases.ui.ConnectionEntry

/** What a run reads from and marks in the console's editor. */
internal interface RunEditor {
    val text: String
    val selectionStart: Int
    val selectionEnd: Int
    val caret: Int

    /** Marks the character at [offset] as an error. */
    fun markError(offset: Int)
}

/** Where a run shows its outcomes: the console's result area. */
internal interface RunTarget {
    fun show(
        outcome: StatementOutcome,
        replaceUnpinned: Boolean,
    )

    fun log(
        text: String,
        error: Boolean,
    )
}

/**
 * The Run action of a console (ADR 0121): picks the statements with [StatementChoice], asks [choose] when there is a
 * choice, then runs them one after the other on the entry's session. Everything here runs on the FX thread; [fx] hands
 * the answers of the session's worker threads back to it.
 */
internal class ConsoleRunner(
    private val entry: ConnectionEntry,
    private val editor: RunEditor,
    private val target: RunTarget,
    private val history: QueryHistory,
    private val pageSize: () -> Int,
    private val fx: (Runnable) -> Unit,
    /** Offers the chooser's [RunChoice.Choose]; calls back with the statements picked (not at all when dismissed). */
    private val choose: (RunChoice.Choose, (List<SqlStatement>) -> Unit) -> Unit,
) {
    /** Whether a sequence of statements is under way (the entry's `running` flag lags behind between statements). */
    private var busy = false

    fun run() {
        if (busy) return
        if (entry.session.get() == null) {
            target.log("Not connected", true)
            return
        }
        val snapshot = editor.text
        val kind = entry.config.get().kind
        when (
            val picked =
                StatementChoice.toRun(snapshot, editor.selectionStart, editor.selectionEnd, editor.caret, kind)
        ) {
            RunChoice.None -> {}

            is RunChoice.Single -> {
                start(listOf(picked.statement), snapshot)
            }

            is RunChoice.All -> {
                start(picked.statements, snapshot)
            }

            is RunChoice.Choose -> {
                choose(picked) { start(it, snapshot) }
            }
        }
    }

    /** Cancels the running statement; the sequence stops at its Cancelled outcome. */
    fun cancel() {
        entry.session.get()?.cancel()
    }

    private fun start(
        statements: List<SqlStatement>,
        snapshot: String,
    ) {
        if (busy || statements.isEmpty()) return
        busy = true
        next(statements, 0, snapshot)
    }

    private fun next(
        statements: List<SqlStatement>,
        index: Int,
        snapshot: String,
    ) {
        val session = entry.session.get()
        if (session == null) {
            target.log("Not connected", true)
            stopped(statements, index)
            return
        }
        val statement = statements[index]
        history.add(entry.historyKey, statement.text)
        val future =
            try {
                session.execute(statement.text, pageSize())
            } catch (e: RuntimeException) {
                target.log(e.message ?: e.javaClass.simpleName, true)
                stopped(statements, index + 1)
                return
            }
        future.whenComplete { outcome, error ->
            fx(
                Runnable {
                    if (error != null || outcome == null) {
                        target.log((error?.cause ?: error)?.message ?: "Statement not run", true)
                        stopped(statements, index + 1)
                    } else {
                        handle(statements, index, snapshot, outcome)
                    }
                },
            )
        }
    }

    private fun handle(
        statements: List<SqlStatement>,
        index: Int,
        snapshot: String,
        outcome: StatementOutcome,
    ) {
        val statement = statements[index]
        target.show(outcome, replaceUnpinned = index == 0)
        if (outcome is StatementOutcome.Failed && outcome.position != null && editor.text == snapshot) {
            editor.markError(statement.start + outcome.position)
        }
        val stop = outcome is StatementOutcome.Failed || outcome is StatementOutcome.Cancelled
        when {
            stop -> stopped(statements, index + 1)
            index + 1 < statements.size -> next(statements, index + 1, snapshot)
            else -> busy = false
        }
    }

    /** Ends the sequence; [from] is the index of the first statement not run. */
    private fun stopped(
        statements: List<SqlStatement>,
        from: Int,
    ) {
        busy = false
        val left = statements.size - from
        if (left > 0) target.log("Stopped: $left statement${if (left == 1) "" else "s"} not run", false)
    }
}
