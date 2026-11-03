package dev.sluide.databases.ui.result

import dev.sluide.databases.result.BoundStatement
import dev.sluide.databases.result.DataEdits
import dev.sluide.databases.result.EditSubmitter
import dev.sluide.databases.result.StatementKind
import dev.sluide.databases.result.SubmitPlan
import dev.sluide.databases.session.DbSession
import java.sql.Connection
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/** What a submit needs of the editing of its grid. */
internal interface SubmitSource {
    /** The edits to submit; null: none. */
    val edits: DataEdits?

    /** The qualified table the edits go to, for the delete question. */
    val tableName: String?

    /** Hands the text of an open cell editor to the edits: null when none is open or it was taken, else why not. */
    fun commitEditor(): String?

    /** [GridSubmit.running] changed: the controls follow it. */
    fun runningChanged()

    /** The submit went through: the edits are gone (the grid is loaded again next). */
    fun submitted()
}

/** A statement of a submit that was not run: the session changed after the delete question was decided. */
private class SubmitRefusedException(
    message: String,
) : RuntimeException(message)

/**
 * Submit of the edits of one result view (ADR 0121, "Editing"): commits an open cell editor, asks before deletes
 * that are committed at once, runs the statements on the session's thread ([SubmitPlan], [EditSubmitter], with the
 * session's statement timeout) and has the view loaded again afterwards. While [running], nothing is edited.
 */
internal class GridSubmit(
    private val host: ResultHost,
    private val view: EditingView,
    private val source: SubmitSource,
    /** Asks before DELETEs that are committed at once (rows, table). */
    private val confirmDelete: (Int, String) -> Boolean,
) {
    var running = false
        private set

    fun submit() {
        if (running || view.busy) return
        // A text that does not parse keeps its editor open; commitCell has shown why.
        if (source.commitEditor() != null) return
        val data = source.edits ?: return
        if (!data.pending) return
        val session = host.entry.session.get()
        if (session == null) {
            view.say("Not connected", true)
            return
        }
        val statements =
            try {
                data.statements()
            } catch (e: IllegalStateException) {
                fail(e.message ?: "The edits cannot be submitted")
                return
            }
        val deletes = statements.count { it.kind == StatementKind.DELETE }
        val asked = SubmitPlan.asks(session.commitMode, session.pendingTransaction, deletes)
        if (asked && !confirmDelete(deletes, source.tableName ?: "the table")) return
        // The question is a nested event loop: a load may have started meanwhile.
        if (running || view.busy || source.edits !== data) return
        setRunning(true)
        val work =
            try {
                session.withSession(
                    changes = true,
                ) { conn -> onSessionThread(session, conn, statements, deletes, asked) }
            } catch (e: RuntimeException) {
                CompletableFuture.failedFuture(e)
            }
        work.whenComplete { committed, error ->
            host.fx {
                setRunning(false)
                if (error != null) {
                    fail(message(error))
                } else {
                    succeeded(data, statements.size, committed)
                }
            }
        }
    }

    /** Runs the statements unless [SubmitPlan] refuses; true when they were committed at once. */
    private fun onSessionThread(
        session: DbSession,
        conn: Connection,
        statements: List<BoundStatement>,
        deletes: Int,
        asked: Boolean,
    ): Boolean =
        when (val plan = SubmitPlan.decide(session.commitMode, session.pendingTransaction, deletes, asked)) {
            is SubmitPlan.Refused -> {
                throw SubmitRefusedException(plan.reason)
            }

            is SubmitPlan.Run -> {
                EditSubmitter.submit(conn, statements, plan.ownTransaction, session.statementTimeoutSeconds)
                plan.ownTransaction
            }
        }

    private fun setRunning(on: Boolean) {
        running = on
        source.runningChanged()
    }

    /** The data is loaded again, so that what the database computed shows (also the page the cursor cached). */
    private fun succeeded(
        data: DataEdits,
        count: Int,
        committed: Boolean,
    ) {
        val needsRefresh = data.accepted().needsRefresh
        source.submitted()
        val text =
            "Submitted $count ${if (count == 1) "change" else "changes"}" +
                if (committed) "" else " (not committed yet)"
        host.log(text, false)
        view.reload(if (needsRefresh) "$text; reloaded to show the values the database filled in" else text)
    }

    private fun fail(text: String) {
        host.log("Submit failed: $text", true)
        view.say(text, true)
    }

    private fun message(error: Throwable): String {
        val cause = (error as? CompletionException)?.cause ?: error
        return cause.message ?: cause.javaClass.simpleName
    }
}
