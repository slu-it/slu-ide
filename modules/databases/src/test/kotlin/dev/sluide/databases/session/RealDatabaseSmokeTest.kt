package dev.sluide.databases.session

import dev.sluide.databases.RealDatabases
import dev.sluide.databases.connect.CommitMode
import dev.sluide.databases.connect.DatabaseKind
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [DbSession] against real PostgreSQL 17 and MariaDB 11 servers in containers, opt-in: skipped unless `SLUIDE_DB_SMOKE`
 * is set and docker with the images is there ([RealDatabases], docs/testing.md). Each kind's container is started
 * once for the class and stopped afterwards.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RealDatabaseSmokeTest {
    private val servers = mutableMapOf<DatabaseKind, RealDatabases.Server>()
    private val sessions = mutableListOf<DbSession>()

    @AfterEach
    fun closeSessions() {
        sessions.forEach { it.close() }
        sessions.clear()
    }

    @AfterAll
    fun stopServers() {
        servers.values.forEach { RealDatabases.stop(it) }
    }

    private fun server(kind: DatabaseKind) = servers.getOrPut(kind) { RealDatabases.start(kind) }

    private fun open(
        kind: DatabaseKind,
        mode: CommitMode = CommitMode.AUTO,
        fetchAllCap: Int = 100_000,
    ): DbSession {
        val server = server(kind)
        return DbSessions
            .open(
                server.config().copy(commitMode = mode),
                server.password.toCharArray(),
                { config, password -> server.connect(config, password) },
                { it.trimStart().startsWith("SELECT", ignoreCase = true) },
                { 0 },
                { fetchAllCap },
            ).also { sessions += it }
    }

    private fun <T> CompletableFuture<T>.await(): T = get(30, TimeUnit.SECONDS)

    private fun DbSession.run(sql: String) = execute(sql, 500).await()

    private fun DbSession.rows(sql: String) = assertIs<StatementOutcome.Rows>(run(sql), sql)

    private fun DbSession.single(sql: String) =
        rows(sql)
            .firstPage.rows
            .single()
            .single()

    private fun DbSession.updated(sql: String) = assertIs<StatementOutcome.Updated>(run(sql), sql)

    private fun numbers(
        kind: DatabaseKind,
        count: Int,
    ) = when (kind) {
        DatabaseKind.POSTGRESQL -> "SELECT x FROM generate_series(1, $count) x ORDER BY x"
        DatabaseKind.MARIADB -> "SELECT seq FROM seq_1_to_$count ORDER BY seq"
    }

    private fun Page.numbers() = rows.map { (it.single() as Number).toInt() }

    @ParameterizedTest
    @EnumSource(DatabaseKind::class)
    fun `a query of 1,234 rows is read in pages of 500, to the last one`(kind: DatabaseKind) {
        val session = open(kind)

        val rows = session.rows(numbers(kind, 1234))
        val second = rows.cursor.page(1).await()
        val last = rows.cursor.lastPage(100_000).await()

        assertEquals((1..500).toList(), rows.firstPage.numbers())
        assertFalse(rows.firstPage.last)
        assertEquals((501..1000).toList(), second.numbers())
        assertEquals(2, last.index)
        assertEquals((1001..1234).toList(), last.numbers())
        assertTrue(last.last)
        assertEquals(1234, rows.cursor.knownRowCount)
        assertFalse(rows.cursor.capped)
        assertTrue(rows.cursor.closed)
        assertFalse(rows.cursor.reExecuted)
    }

    @ParameterizedTest
    @EnumSource(DatabaseKind::class)
    fun `an unread result is closed by the next statement, and its pages beyond come from a second run`(
        kind: DatabaseKind,
    ) {
        val session = open(kind)
        val rows = session.rows(numbers(kind, 300_000))

        assertEquals(42, (session.single("SELECT 42") as Number).toInt())
        assertTrue(rows.cursor.closed)
        assertEquals(
            (1001..1500).toList(),
            rows.cursor
                .page(2)
                .await()
                .numbers(),
        )
        assertTrue(rows.cursor.reExecuted)
        assertEquals(7, (session.single("SELECT 7") as Number).toInt())
    }

    @ParameterizedTest
    @EnumSource(DatabaseKind::class)
    fun `cancel stops a sleeping statement and the session runs the next one`(kind: DatabaseKind) {
        val session = open(kind)
        val running = CountDownLatch(1)
        session.addListener { if (it == SessionEvent.RunningChanged(true)) running.countDown() }
        val sleep = if (kind == DatabaseKind.POSTGRESQL) "SELECT pg_sleep(30)" else "SELECT SLEEP(30)"

        val outcome = session.execute(sleep, 500)
        assertTrue(running.await(10, TimeUnit.SECONDS))
        session.cancel()

        assertIs<StatementOutcome.Cancelled>(outcome.get(10, TimeUnit.SECONDS))
        assertEquals(1, (session.single("SELECT 1") as Number).toInt())
    }

    @ParameterizedTest
    @EnumSource(DatabaseKind::class)
    fun `in AUTO mode a transaction the user opens is pending, and a query inside it does not commit it`(
        kind: DatabaseKind,
    ) {
        val table = "smoke_auto_begin_${kind.name.lowercase()}"
        val session = open(kind)
        session.updated("CREATE TABLE $table (id INT)")

        session.run("BEGIN")
        assertTrue(session.pendingTransaction)
        session.updated("INSERT INTO $table VALUES (1)")
        assertEquals(
            500,
            session
                .rows(numbers(kind, 1234))
                .firstPage.rows.size,
        )
        assertEquals(1L, (session.single("SELECT count(*) FROM $table") as Number).toLong())
        assertTrue(session.pendingTransaction)
        session.run("ROLLBACK")

        assertFalse(session.pendingTransaction)
        assertEquals(0L, (session.single("SELECT count(*) FROM $table") as Number).toLong())
    }

    @Test
    fun `inside a user's AUTO transaction PostgreSQL reads at most the fetch-all cap`() {
        val session = open(DatabaseKind.POSTGRESQL, fetchAllCap = 1000)
        session.run("BEGIN")

        val rows = session.rows(numbers(DatabaseKind.POSTGRESQL, 1234))
        val last = rows.cursor.lastPage(100_000).await()

        assertTrue(rows.cursor.capped)
        assertEquals(1000, rows.cursor.knownRowCount)
        assertEquals((501..1000).toList(), last.numbers())
        assertTrue(session.pendingTransaction)
        session.run("ROLLBACK")
        assertFalse(session.pendingTransaction)
    }

    /**
     * The result is sent completely by the server (600 rows: also received by the driver; 3,000 rows: still in the
     * socket buffers) while the cursor read only 501, so closing it may cancel a statement the server has finished.
     */
    @ParameterizedTest
    @ValueSource(ints = [600, 3_000])
    fun `a MariaDB result the server finished sending is closed without harm to the next statement`(count: Int) {
        val session = open(DatabaseKind.MARIADB)
        val rows = session.rows(numbers(DatabaseKind.MARIADB, count))

        assertEquals(42, (session.single("SELECT 42") as Number).toInt())
        assertTrue(rows.cursor.closed)
        assertEquals(43, (session.single("SELECT 43") as Number).toInt())
    }

    @ParameterizedTest
    @EnumSource(DatabaseKind::class)
    fun `in MANUAL mode an insert is pending until it is rolled back`(kind: DatabaseKind) {
        val table = "smoke_manual_${kind.name.lowercase()}"
        open(kind).updated("CREATE TABLE $table (id INT)")
        val session = open(kind, CommitMode.MANUAL)

        session.updated("INSERT INTO $table VALUES (1)")
        assertTrue(session.pendingTransaction)
        assertEquals(1L, (session.single("SELECT count(*) FROM $table") as Number).toLong())
        session.rollback().await()

        assertFalse(session.pendingTransaction)
        assertEquals(0L, (session.single("SELECT count(*) FROM $table") as Number).toLong())
    }

    @ParameterizedTest
    @EnumSource(DatabaseKind::class)
    fun `columns name their base table and schema, not the alias`(kind: DatabaseKind) {
        val table = "smoke_columns"
        val id = if (kind == DatabaseKind.POSTGRESQL) "id SERIAL PRIMARY KEY" else "id INT AUTO_INCREMENT PRIMARY KEY"
        open(kind).updated("CREATE TABLE $table ($id, name VARCHAR(20))")
        val session = open(kind)

        val columns = session.rows("SELECT a.id AS ident, a.name, 1 AS one FROM $table a").columns

        val schema = if (kind == DatabaseKind.POSTGRESQL) "public" else "smoke"
        assertEquals(listOf("ident", "name", "one"), columns.map { it.label })
        assertEquals(listOf("id", "name"), columns.take(2).map { it.name })
        assertEquals(listOf(table, table, null), columns.map { it.table })
        assertEquals(listOf(schema, schema), columns.take(2).map { it.schema })
        assertTrue(columns[0].autoIncrement)
        assertFalse(columns[0].nullable)
        assertTrue(columns[1].nullable)
    }

    @Test
    fun `PostgreSQL values become plain JDK values`() {
        val session = open(DatabaseKind.POSTGRESQL)

        val row =
            session
                .rows(
                    "SELECT '{\"a\": 1}'::json, '{\"a\": 1}'::jsonb, '\\x0102'::bytea, " +
                        "timestamptz '2026-11-02 10:00:00+01', ARRAY[1, 2], ARRAY['a', NULL], " +
                        "'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'::uuid, 1.50::numeric, interval '1 day', " +
                        "date '2026-11-02', timestamp '2026-11-02 10:00', NULL::text",
                ).firstPage.rows
                .single()

        assertEquals("{\"a\": 1}", row[0])
        assertEquals("{\"a\": 1}", row[1])
        assertContentEquals(byteArrayOf(1, 2), row[2] as ByteArray)
        assertEquals(OffsetDateTime.parse("2026-11-02T10:00+01:00").toInstant(), (row[3] as OffsetDateTime).toInstant())
        assertEquals(listOf(1, 2), row[4])
        assertEquals(listOf("a", null), row[5])
        assertEquals(UUID.fromString("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11"), row[6])
        assertEquals(BigDecimal("1.50"), row[7])
        assertEquals("1 day", row[8])
        assertEquals(LocalDate.of(2026, 11, 2), row[9])
        assertEquals(LocalDateTime.of(2026, 11, 2, 10, 0), row[10])
        assertEquals(null, row[11])
    }

    @Test
    fun `MariaDB values become plain JDK values`() {
        val session = open(DatabaseKind.MARIADB)
        session.updated("CREATE TABLE smoke_values (b BLOB, u UUID, j JSON)")
        session.updated(
            "INSERT INTO smoke_values VALUES (x'0102', 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11', '{\"a\": 1}')",
        )

        val row =
            session
                .rows(
                    "SELECT b, u, j, TIMESTAMP '2026-11-02 10:00:00', DATE '2026-11-02', 1.50, NULL FROM smoke_values",
                ).firstPage.rows
                .single()

        assertContentEquals(byteArrayOf(1, 2), row[0] as ByteArray)
        assertEquals("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11", row[1].toString())
        assertEquals("{\"a\": 1}", row[2])
        assertEquals(LocalDateTime.of(2026, 11, 2, 10, 0), row[3])
        assertEquals(LocalDate.of(2026, 11, 2), row[4])
        assertEquals(BigDecimal("1.50"), row[5])
        assertEquals(null, row[6])
    }

    @Test
    fun `PostgreSQL reports the error position as an offset into the statement`() {
        val session = open(DatabaseKind.POSTGRESQL)
        val sql = "SELECT * FRM nowhere"

        val failed = assertIs<StatementOutcome.Failed>(session.run(sql))

        assertEquals("42601", failed.sqlState)
        assertTrue(sql.substring(assertNotNull(failed.position)).startsWith("FRM"), "${failed.position}")
    }

    @Test
    fun `in AUTO mode a query that writes through a function is committed when its result closes`() {
        val setup = open(DatabaseKind.POSTGRESQL)
        setup.updated("CREATE TABLE smoke_written (id INT)")
        setup.updated("CREATE SEQUENCE smoke_auto_seq")
        setup.updated(
            "CREATE FUNCTION smoke_write() RETURNS int LANGUAGE sql AS 'INSERT INTO smoke_written VALUES (1) RETURNING 1'",
        )
        val session = open(DatabaseKind.POSTGRESQL)

        assertEquals(1L, (session.single("SELECT nextval('smoke_auto_seq')") as Number).toLong())
        assertEquals(1, (session.single("SELECT smoke_write()") as Number).toInt())

        // The metadata connection sees only what the session connection committed.
        val count =
            session.withMetadata { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT count(*) FROM smoke_written").use {
                        it.next()
                        it.getLong(1)
                    }
                }
            }
        assertEquals(1L, count.await())
    }

    @Test
    fun `in MANUAL mode on PostgreSQL a query that writes marks the transaction pending`() {
        open(DatabaseKind.POSTGRESQL).updated("CREATE SEQUENCE smoke_manual_seq")
        val session = open(DatabaseKind.POSTGRESQL, CommitMode.MANUAL)

        session.single("SELECT 1")
        assertFalse(session.pendingTransaction)
        session.single("SELECT nextval('smoke_manual_seq')")

        assertTrue(session.pendingTransaction)
    }
}
