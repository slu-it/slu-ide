package dev.sluide.databases.ui.console

import dev.sluide.databases.session.DbSession
import dev.sluide.databases.session.StatementOutcome
import dev.sluide.databases.sql.QueryHistory
import dev.sluide.databases.sql.RunChoice
import dev.sluide.databases.sql.SqlStatement
import dev.sluide.databases.sql.StatementChoice
import dev.sluide.databases.ui.ConnectFailures
import dev.sluide.databases.ui.ConnectionEntry
import javafx.beans.property.SimpleBooleanProperty

/** What a run reads from and marks in the console's editor. */
internal interface RunEditor {
    val text: String
    val selectionStart: Int
    val selectionEnd: Int
    val caret: Int

    /** Marks the character at [offset] as an error; `null` removes the mark. */
    fun markError(offset: Int?)
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
 * Whether a sequence of statements runs on the connection, shared by the consoles of one workspace so that two
 * consoles never interleave their statements on the session.
 */
internal class RunGate {
    val busy = SimpleBooleanProperty(false)
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
    private val gate: RunGate = RunGate(),
) {
    /** Whether this console's sequence of statements is under way (the entry's `running` flag lags between them). */
    var running = false
        private set

    /** Whether a statement of this console was sent and not answered yet. */
    private var inFlight = false

    /** Told apart per sequence, so that the answer of a stopped one is ignored. */
    private var generation = 0

    fun run() {
        if (running || gate.busy.get() || entry.running.get()) return
        if (entry.session.get() == null) {
            target.log("Not connected", true)
            return
        }
        editor.markError(null)
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

    /** The console is closed: no further statement is sent, and the one in flight is cancelled. */
    fun stop() {
        if (!running) return
        generation++
        running = false
        gate.busy.set(false)
        if (inFlight) entry.session.get()?.cancel()
        inFlight = false
    }

    private fun start(
        statements: List<SqlStatement>,
        snapshot: String,
    ) {
        if (running || gate.busy.get() || entry.running.get() || statements.isEmpty()) return
        val session = entry.session.get()
        if (session == null) {
            target.log("Not connected", true)
            return
        }
        running = true
        gate.busy.set(true)
        generation++
        next(session, generation, statements, 0, snapshot)
    }

    private fun next(
        session: DbSession,
        token: Int,
        statements: List<SqlStatement>,
        index: Int,
        snapshot: String,
    ) {
        if (entry.session.get() !== session) {
            target.log("Not connected", true)
            stopped(statements, index)
            return
        }
        val statement = statements[index]
        val future =
            try {
                session.execute(statement.text, pageSize())
            } catch (e: RuntimeException) {
                target.log(ConnectFailures.futureMessage(e), true)
                stopped(statements, index + 1)
                return
            }
        inFlight = true
        history.add(entry.historyKey, statement.text)
        future.whenComplete { outcome, error ->
            fx(
                Runnable {
                    if (token != generation) {
                        // The run was stopped: nobody will read this answer, so its cursor is closed.
                        (outcome as? StatementOutcome.Rows)?.cursor?.close()
                        return@Runnable
                    }
                    inFlight = false
                    try {
                        if (error != null || outcome == null) {
                            target.log(error?.let(ConnectFailures::futureMessage) ?: "Statement not run", true)
                            stopped(statements, index + 1)
                        } else {
                            handle(session, token, statements, index, snapshot, outcome)
                        }
                    } catch (e: RuntimeException) {
                        // Showing the outcome failed: the gate must not stay shut.
                        finish()
                        runCatching { target.log(ConnectFailures.futureMessage(e), true) }
                    }
                },
            )
        }
    }

    private fun handle(
        session: DbSession,
        token: Int,
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
            index + 1 < statements.size -> next(session, token, statements, index + 1, snapshot)
            else -> finish()
        }
    }

    private fun finish() {
        running = false
        gate.busy.set(false)
    }

    /** Ends the sequence; [from] is the index of the first statement not run. */
    private fun stopped(
        statements: List<SqlStatement>,
        from: Int,
    ) {
        finish()
        val left = statements.size - from
        if (left > 0) target.log("Stopped: $left statement${if (left == 1) "" else "s"} not run", false)
    }
}
