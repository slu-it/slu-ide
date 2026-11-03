package dev.sluide.databases.ui.result

import dev.sluide.databases.result.BoundStatement
import java.sql.SQLException
import java.sql.Types
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EditSubmitterTest {
    private val update =
        BoundStatement("UPDATE t SET a = ? WHERE id = ?", listOf("x", 1L), listOf(Types.OTHER, null), true)
    private val insert = BoundStatement("INSERT INTO t (a) VALUES (?)", listOf("y"), listOf(null))

    @Test
    fun `own transaction commits and restores auto-commit`() {
        val jdbc = RecordingJdbc(startAutoCommit = true)
        EditSubmitter.submit(jdbc.connection, listOf(update, insert), autoCommitUserIdle = true)
        assertEquals(
            listOf(
                "autoCommit=false",
                "prepare ${update.sql}",
                "setObject 1=x type=1111",
                "setObject 2=1",
                "execute ${update.sql}",
                "prepare ${insert.sql}",
                "setObject 1=y",
                "execute ${insert.sql}",
                "commit",
                "autoCommit=true",
            ),
            jdbc.log,
        )
    }

    @Test
    fun `own transaction rolls back on a failure and restores auto-commit`() {
        val jdbc = RecordingJdbc(startAutoCommit = true, failOn = { it == insert.sql })
        val error =
            assertFailsWith<SQLException> { EditSubmitter.submit(jdbc.connection, listOf(update, insert), true) }
        assertEquals("boom", error.message)
        assertEquals(listOf("rollback", "autoCommit=true"), jdbc.log.takeLast(2))
        assertTrue("commit" !in jdbc.log)
    }

    @Test
    fun `a transaction of the user is wrapped in a savepoint that is released`() {
        val jdbc = RecordingJdbc(startAutoCommit = false)
        EditSubmitter.submit(jdbc.connection, listOf(update), autoCommitUserIdle = true)
        assertEquals("setSavepoint", jdbc.log.first())
        assertEquals("release savepoint", jdbc.log.last())
        assertTrue(jdbc.log.none { it.startsWith("autoCommit") || it == "commit" })
    }

    @Test
    fun `auto mode with an open user transaction uses a savepoint in SQL and rolls back to it on a failure`() {
        val jdbc = RecordingJdbc(startAutoCommit = true, failOn = { true })
        assertFailsWith<SQLException> {
            EditSubmitter.submit(jdbc.connection, listOf(update), autoCommitUserIdle = false)
        }
        assertEquals("sql SAVEPOINT db_edit", jdbc.log.first())
        assertEquals("sql ROLLBACK TO SAVEPOINT db_edit", jdbc.log.last())
        assertTrue(jdbc.log.none { it.startsWith("autoCommit") || it == "commit" || it == "rollback" })
    }

    @Test
    fun `auto mode with an open user transaction releases the SQL savepoint after a success`() {
        val jdbc = RecordingJdbc(startAutoCommit = true)
        EditSubmitter.submit(jdbc.connection, listOf(update), autoCommitUserIdle = false)
        assertEquals("sql RELEASE SAVEPOINT db_edit", jdbc.log.last())
        assertTrue("setSavepoint" !in jdbc.log)
    }

    @Test
    fun `an update that matches no row or several rolls back`() {
        for (count in listOf(0, 2)) {
            val jdbc = RecordingJdbc(startAutoCommit = true, counts = { count })
            val error =
                assertFailsWith<EditConflictException> { EditSubmitter.submit(jdbc.connection, listOf(update), true) }
            assertEquals("The row was changed or deleted by someone else ($count rows matched)", error.message)
            assertEquals(listOf("rollback", "autoCommit=true"), jdbc.log.takeLast(2))
        }
    }

    @Test
    fun `an insert may affect any count`() {
        val jdbc = RecordingJdbc(startAutoCommit = true, counts = { 0 })
        EditSubmitter.submit(jdbc.connection, listOf(insert), true)
        assertTrue("commit" in jdbc.log)
    }

    @Test
    fun `the conflict also rolls back to the savepoint`() {
        val jdbc = RecordingJdbc(startAutoCommit = false, counts = { 2 })
        assertFailsWith<EditConflictException> { EditSubmitter.submit(jdbc.connection, listOf(update), false) }
        assertEquals("rollback savepoint", jdbc.log.last())
    }
}
