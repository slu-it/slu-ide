package dev.sluide.databases.ui.result

import dev.sluide.databases.connect.CommitMode
import dev.sluide.databases.connect.ConnectionConfig
import dev.sluide.databases.connect.DatabaseKind
import dev.sluide.databases.result.DataEdits
import dev.sluide.databases.result.Editability
import dev.sluide.databases.result.TableRef
import dev.sluide.databases.result.col
import dev.sluide.databases.session.ColumnInfo
import dev.sluide.databases.ui.ConnectionEntry
import java.sql.Connection
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [GridSubmit] without JavaFX: the guards that decide what is written, and what the user is told afterwards. */
class GridSubmitTest {
    private val config = ConnectionConfig("c1", "c", DatabaseKind.POSTGRESQL)
    private val fake = EditingSession(config)
    private val entry = ConnectionEntry(config, "k").apply { connected(fake) }
    private val columns = listOf(col("id"), col("name"))

    private val said = mutableListOf<Pair<String, Boolean>>()
    private val logged = mutableListOf<Pair<String, Boolean>>()
    private val reloads = mutableListOf<String>()
    private val running = mutableListOf<Boolean>()
    private var submittedCount = 0
    private var busy = false
    private var editorText: String? = null
    private var tableName: String? = "s.t"
    private var current: DataEdits? = edits(listOf(1L, "a"), listOf(2L, "b"))
    private val questions = mutableListOf<Pair<Int, String>>()
    private var answer: (() -> Boolean) = { true }
    private lateinit var submit: GridSubmit

    private val host =
        object : ResultHost {
            override val entry: ConnectionEntry get() = this@GridSubmitTest.entry

            override fun pageSize() = error("not used")

            override fun logOutcome(outcome: dev.sluide.databases.session.StatementOutcome) = error("not used")

            override fun log(
                text: String,
                error: Boolean,
            ) {
                logged += text to error
            }

            override fun primaryKey(
                session: dev.sluide.databases.session.DbSession,
                ref: TableRef,
            ): CompletableFuture<List<String>> = error("not used")

            override fun fetchAllCap() = error("not used")

            override fun fx(action: Runnable) = action.run()

            override fun exportExecutor(): Executor = error("not used")

            override fun copyToClipboard(text: String) = error("not used")

            override fun openScratch(
                baseName: String,
                extension: String,
                text: String,
            ) = error("not used")
        }

    private val view =
        object : EditingView {
            override val columns: List<ColumnInfo> get() = this@GridSubmitTest.columns

            override fun say(
                text: String,
                error: Boolean,
            ) {
                said += text to error
            }

            override fun editsChanged() = error("not used")

            override val busy: Boolean get() = this@GridSubmitTest.busy
            override val isQuery: Boolean get() = true

            override fun reload(note: String) {
                reloads += note
            }
        }

    private val source =
        object : SubmitSource {
            override val edits: DataEdits? get() = current
            override val tableName: String? get() = this@GridSubmitTest.tableName

            override fun commitEditor(): String? = editorText

            override fun runningChanged() {
                running += submit.running
            }

            override fun submitted() {
                submittedCount++
            }
        }

    init {
        submit =
            GridSubmit(host, view, source) { count, table ->
                questions += count to table
                answer()
            }
    }

    private fun edits(vararg rows: List<Any?>) =
        DataEdits(
            Editability.Editable(TableRef(DatabaseKind.POSTGRESQL, "s", "t"), listOf("id")),
            columns,
            rows.toList(),
        )

    private fun rename(
        row: Int,
        name: String,
    ) = current!!.edit(row, 1, name)

    private val updateA = "execute UPDATE \"s\".\"t\" SET \"name\" = ? WHERE \"id\" = ?"

    @Test
    fun `a busy view or a running submit writes nothing`() {
        rename(0, "A")
        busy = true
        submit.submit()
        assertEquals(emptyList(), fake.changes)

        // With a delete pending, a submit that got past the first guard would ask the question again; the guard
        // at the question's end (running, busy) would stop it only after that, so the question tells them apart.
        current!!.deleteRows(listOf(1))
        submit.submit()
        assertEquals(emptyList(), questions)
        assertEquals(emptyList(), fake.changes)

        busy = false
        fake.hold = true
        submit.submit()
        assertTrue(submit.running)
        submit.submit()
        assertEquals(listOf(1 to "s.t"), questions)
        assertEquals(listOf(true), fake.changes)
        fake.release()
        assertFalse(submit.running)
        assertEquals(listOf(true, false), running)
    }

    @Test
    fun `an editor that does not parse keeps the edits and writes nothing`() {
        rename(0, "A")
        editorText = "not a number"
        submit.submit()
        assertEquals(emptyList(), fake.changes)
        assertEquals(emptyList(), said)
        assertEquals(emptyList(), logged)
    }

    @Test
    fun `no edits or unchanged cells write nothing`() {
        current = null
        submit.submit()
        current = edits(listOf(1L, "a"))
        current!!.edit(0, 1, "x")
        current!!.edit(0, 1, "a")
        submit.submit()
        assertEquals(emptyList(), fake.changes)
        assertEquals(emptyList(), said)
        assertEquals(emptyList(), logged)
    }

    @Test
    fun `without a session it says Not connected`() {
        rename(0, "A")
        val loose = ConnectionEntry(config, "k2")
        val lonely =
            GridSubmit(
                object : ResultHost by host {
                    override val entry: ConnectionEntry get() = loose
                },
                view,
                source,
            ) { _, _ -> true }
        lonely.submit()
        assertEquals(listOf("Not connected" to true), said)
        assertEquals(emptyList(), fake.changes)
    }

    @Test
    fun `a row without a key value is reported and nothing is written`() {
        current = edits(listOf(null, "a"))
        rename(0, "A")
        submit.submit()
        assertEquals(listOf("Row 1 has no key value" to true), said)
        assertEquals(listOf("Submit failed: Row 1 has no key value" to true), logged)
        assertEquals(emptyList(), fake.changes)
        assertFalse(submit.running)
    }

    @Test
    fun `an update is written with its exact SQL and reported`() {
        rename(0, "A")
        fake.statementTimeoutSeconds = 7
        submit.submit()
        assertEquals(listOf(true), fake.changes)
        assertEquals(
            listOf(
                "autoCommit=false",
                "prepare UPDATE \"s\".\"t\" SET \"name\" = ? WHERE \"id\" = ?",
                "timeout 7",
                "setObject 1=A type=1111",
                "setObject 2=1",
                updateA,
                "commit",
                "autoCommit=true",
            ),
            fake.jdbc.log,
        )
        assertEquals(listOf("Submitted 1 change" to false), logged)
        assertEquals(listOf("Submitted 1 change"), reloads)
        assertEquals(1, submittedCount)
        assertEquals(listOf(true, false), running)
        assertEquals(emptyList(), said)
    }

    @Test
    fun `several changes are counted in the plural`() {
        rename(0, "A")
        rename(1, "B")
        submit.submit()
        assertEquals(listOf("Submitted 2 changes"), reloads)
    }

    @Test
    fun `an added row that leaves columns out reloads to show the database's values`() {
        current = edits(listOf(1L, "a"))
        val row = current!!.addRow()
        current!!.edit(row, 1, "n")
        submit.submit()
        assertEquals(listOf("Submitted 1 change; reloaded to show the values the database filled in"), reloads)
        assertEquals(listOf("Submitted 1 change" to false), logged)
    }

    @Test
    fun `deletes committed at once ask with their count and the table, and Cancel writes nothing`() {
        current!!.deleteRows(listOf(0, 1))
        answer = { false }
        submit.submit()
        assertEquals(listOf(2 to "s.t"), questions)
        assertEquals(emptyList(), fake.changes)
        assertFalse(submit.running)

        tableName = null
        answer = { true }
        submit.submit()
        assertEquals(listOf(2 to "s.t", 2 to "the table"), questions)
        assertEquals(listOf(true), fake.changes)
        assertEquals(listOf("Submitted 2 changes"), reloads)
    }

    @Test
    fun `an update alone asks nothing`() {
        rename(0, "A")
        submit.submit()
        assertEquals(emptyList(), questions)
        assertEquals(1, fake.changes.size)
    }

    @Test
    fun `deletes inside a Manual transaction are not asked about and are not committed yet`() {
        val manual = EditingSession(config, autoCommit = false).apply { commitMode = CommitMode.MANUAL }
        entry.connected(manual)
        current!!.deleteRows(listOf(0))
        submit.submit()
        assertEquals(emptyList(), questions)
        assertEquals(listOf("setSavepoint", "release savepoint"), manual.jdbc.log.filter { "avepoint" in it })
        assertFalse("commit" in manual.jdbc.log)
        assertEquals(listOf("Submitted 1 change (not committed yet)"), reloads)
        assertEquals(listOf("Submitted 1 change (not committed yet)" to false), logged)
    }

    @Test
    fun `deletes inside a transaction of the user in Auto mode are not asked about and run in a savepoint`() {
        fake.pendingTransaction = true
        current!!.deleteRows(listOf(0))
        submit.submit()
        assertEquals(emptyList(), questions)
        assertEquals(
            listOf("sql SAVEPOINT db_edit", "sql RELEASE SAVEPOINT db_edit"),
            fake.jdbc.log.filter { "SAVEPOINT" in it },
        )
        assertFalse("commit" in fake.jdbc.log)
        assertEquals(listOf("Submitted 1 change (not committed yet)"), reloads)
        assertEquals(emptyList(), said)
    }

    @Test
    fun `edits replaced while the question is open are not written`() {
        current!!.deleteRows(listOf(0))
        answer = {
            current = edits(listOf(1L, "a"))
            true
        }
        submit.submit()
        assertEquals(emptyList(), fake.changes)
        assertFalse(submit.running)
        assertEquals(emptyList(), running)
    }

    @Test
    fun `a load started while the question is open writes nothing`() {
        current!!.deleteRows(listOf(0))
        answer = {
            busy = true
            true
        }
        submit.submit()
        assertEquals(emptyList(), fake.changes)
        assertEquals(emptyList(), running)
    }

    @Test
    fun `a submit started while the question is open writes nothing a second time`() {
        current!!.deleteRows(listOf(0))
        fake.hold = true
        var inner = true
        answer = {
            if (inner) {
                inner = false
                submit.submit()
            }
            true
        }
        submit.submit()
        assertEquals(listOf(true), fake.changes)
        assertEquals(listOf(1 to "s.t", 1 to "s.t"), questions)
    }

    @Test
    fun `deletes whose transaction ended before the session ran them are refused`() {
        fake.pendingTransaction = true
        current!!.deleteRows(listOf(0))
        fake.hold = true
        submit.submit()
        fake.pendingTransaction = false
        fake.release()
        assertEquals(emptyList(), fake.jdbc.log)
        assertEquals(listOf("The transaction ended; submit again" to true), said)
        assertEquals(listOf("Submit failed: The transaction ended; submit again" to true), logged)
        assertEquals(0, submittedCount)
        assertFalse(submit.running)
    }

    @Test
    fun `a failing statement rolls back, keeps the edits and shows the error`() {
        val failing = EditingSession(config, failOn = { true })
        entry.connected(failing)
        rename(0, "A")
        submit.submit()
        assertEquals(
            listOf(
                "autoCommit=false",
                "prepare UPDATE \"s\".\"t\" SET \"name\" = ? WHERE \"id\" = ?",
                "setObject 1=A type=1111",
                "setObject 2=1",
                updateA,
                "rollback",
                "autoCommit=true",
            ),
            failing.jdbc.log,
        )
        assertEquals(listOf("boom" to true), said)
        assertEquals(listOf("Submit failed: boom" to true), logged)
        assertEquals(emptyList(), reloads)
        assertEquals(0, submittedCount)
        assertEquals(listOf(true, false), running)
        assertTrue(current!!.pending)
    }

    @Test
    fun `an unwrapped cause is shown and a failure without a message by its class name`() {
        rename(0, "A")
        fake.failure = CompletionException(IllegalStateException("inner"))
        submit.submit()
        fake.failure = CompletionException(IllegalStateException())
        submit.submit()
        fake.failure = CompletionException("outer", null)
        submit.submit()
        fake.failure = IllegalArgumentException("plain")
        submit.submit()
        fake.failure = IllegalArgumentException()
        submit.submit()
        assertEquals(
            listOf("inner", "IllegalStateException", "outer", "plain", "IllegalArgumentException"),
            said.map { it.first },
        )
        assertTrue(said.all { it.second })
        assertEquals(5, running.count { !it })
    }

    @Test
    fun `a session that throws on the call fails the submit and frees it`() {
        val throwing =
            object : dev.sluide.databases.ui.FakeSession(config) {
                override fun <T> withSession(
                    changes: Boolean,
                    work: (Connection) -> T,
                ): CompletableFuture<T> = throw IllegalStateException("session closed")
            }
        entry.connected(throwing)
        rename(0, "A")
        submit.submit()
        assertEquals(listOf("session closed" to true), said)
        assertEquals(listOf(true, false), running)
    }
}
