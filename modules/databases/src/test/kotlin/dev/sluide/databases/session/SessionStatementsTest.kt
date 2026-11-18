package dev.sluide.databases.session

import dev.sluide.databases.connect.DatabaseKind
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mariadb.jdbc.Configuration
import org.mariadb.jdbc.HostAddress
import org.mariadb.jdbc.client.Client
import org.mariadb.jdbc.client.Context
import org.mariadb.jdbc.client.DataType
import org.mariadb.jdbc.client.ServerVersion
import org.mariadb.jdbc.client.result.CompleteResult
import org.mariadb.jdbc.client.result.Result
import org.mariadb.jdbc.client.result.StreamingResult
import org.mariadb.jdbc.client.util.ClosableLock
import org.mariadb.jdbc.export.ExceptionFactory
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException
import java.sql.Statement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The parts of [SessionStatements] that read driver state: transaction flags, update counts and MariaDB's cancel. */
class SessionStatementsTest {
    private val db = FakeDatabase()
    private val calls = mutableListOf<String>()

    private inline fun <reified T> fake(crossinline handler: (method: String, args: Array<Any?>) -> Any?): T =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(T::class.java)) { _, method, args ->
            handler(method.name, args ?: emptyArray()) ?: when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                else -> null
            }
        } as T

    private fun statements(
        connection: Connection,
        kind: DatabaseKind = DatabaseKind.POSTGRESQL,
    ) = SessionStatements(connection, kind, 1, {}, {})

    /**
     * A real Connector/J connection object over a client whose context has the given server status. Nothing connects:
     * `Connection.getContext()` answers the client's context, and `isWrapperFor` is `Class.isInstance`, as in the
     * driver's source (`org.mariadb.jdbc.Connection`).
     */
    private fun mariaConnection(serverStatus: Int): Connection {
        val version = fake<ServerVersion> { m, _ -> if (m == "isMariaDBServer") true else null }
        val context =
            fake<Context> { m, _ ->
                when (m) {
                    "getServerStatus" -> serverStatus
                    "getVersion" -> version
                    else -> null
                }
            }
        val config = Configuration.parse("jdbc:mariadb://localhost/db")
        val client =
            fake<Client> { m, _ ->
                when (m) {
                    "getContext" -> context
                    "getExceptionFactory" -> ExceptionFactory(config, HostAddress.from("localhost", 3306))
                    else -> null
                }
            }
        return org.mariadb.jdbc.Connection(config, ClosableLock(), client)
    }

    @Test
    fun `pgjdbc's transaction state tells whether the server has a transaction open`() {
        val statements = statements(db.session)
        assertEquals(false, statements.serverInTransaction())

        db.session.createStatement().execute("BEGIN")

        assertEquals(true, statements.serverInTransaction())
    }

    @ParameterizedTest
    @CsvSource("1, true", "0, false", "2, false", "3, true", "8, false")
    fun `Connector J's server status says it by the in-transaction flag alone`(
        status: Int,
        inTransaction: Boolean,
    ) {
        assertEquals(inTransaction, statements(mariaConnection(status), DatabaseKind.MARIADB).serverInTransaction())
    }

    @Test
    fun `another driver, or one that fails to say, gives no answer`() {
        val plain = fake<Connection> { m, _ -> if (m == "isWrapperFor") false else null }
        val failing = fake<Connection> { _, _ -> throw SQLException("closed", "08003") }

        assertNull(statements(plain).serverInTransaction())
        assertNull(statements(failing).serverInTransaction())
    }

    @Test
    fun `the transaction wrote when the server has assigned it an id`() {
        fun wrote(rows: List<List<Any?>>): Boolean? {
            db.results = { FakeResult.Rows(rows) }
            return statements(db.session).serverWrote()
        }

        assertEquals(true, wrote(listOf(listOf(4711L))))
        assertEquals(false, wrote(listOf(listOf<Any?>(null))))
        assertEquals(false, wrote(emptyList()))
        db.results = { FakeResult.Fails(SQLException("too old", "42883")) }
        assertNull(statements(db.session).serverWrote())
    }

    // The JDK's Statement.getLargeUpdateCount throws UnsupportedOperationException unless the driver overrides it;
    // a driver may also refuse with SQLFeatureNotSupportedException (the interface documents both).
    @ParameterizedTest
    @CsvSource("unsupported", "feature")
    fun `an update count falls back to the int count when the driver has no large one`(refusal: String) {
        val statement =
            fake<Statement> { m, _ ->
                when (m) {
                    "execute" -> {
                        false
                    }

                    "getLargeUpdateCount" -> {
                        throw if (refusal == "unsupported") {
                            UnsupportedOperationException()
                        } else {
                            SQLFeatureNotSupportedException()
                        }
                    }

                    "getUpdateCount" -> {
                        7
                    }

                    else -> {
                        null
                    }
                }
            }
        val connection = fake<Connection> { m, _ -> if (m == "createStatement") statement else null }

        val started = statements(connection).start("UPDATE a", autoTransaction = false, fetchSize = 10, timeout = 0)

        assertEquals(7L, (started as SessionStatements.Started.Count).count)
    }

    private fun live(
        resultSet: ResultSet,
        order: MutableList<String>,
    ) = LiveResult(
        fake<Statement> { m, _ ->
            order += "statement $m"
            null
        },
        resultSet,
        autoTransaction = false,
    )

    private fun allocate(type: Class<*>): Any {
        val unsafe =
            Class
                .forName(
                    "sun.misc.Unsafe",
                ).getDeclaredField("theUnsafe")
                .apply { isAccessible = true }
                .get(null)
        return unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type)
    }

    /** A real Connector/J result the driver holds whole (`loaded()` true), built by its public factory. */
    private fun completeResult(): ResultSet {
        val config = Configuration.parse("jdbc:mariadb://localhost/db")
        val context = fake<Context> { m, _ -> if (m == "getConf") config else null }
        return CompleteResult.createResultSet(
            "c",
            DataType.VARCHAR,
            arrayOf(arrayOf("x")),
            context,
            0,
            ResultSet.TYPE_FORWARD_ONLY,
        )
    }

    private fun resultSet(
        kind: String,
        order: MutableList<String>,
    ): ResultSet {
        val result: Any? =
            when (kind) {
                // Connector/J 3.5.10: a StreamingResult has `loaded` false until it read every row; an instance
                // allocated without a constructor is in that state, and needs no socket here.
                "streaming" -> allocate(StreamingResult::class.java)

                "complete" -> completeResult()

                else -> null
            }
        return fake { m, args ->
            when (m) {
                "isWrapperFor" -> {
                    if (kind == "wrapper fails") throw SQLException("closed", "08003")
                    result != null && args[0] == Result::class.java
                }

                "unwrap" -> {
                    if (kind == "unwrap fails") throw IllegalAccessError("module path")
                    result
                }

                "close" -> {
                    order += "close result"
                    null
                }

                else -> {
                    null
                }
            }
        }
    }

    @ParameterizedTest
    @CsvSource(
        "MARIADB, streaming, false, false, true",
        "MARIADB, complete, false, false, false",
        "MARIADB, wrapper fails, false, false, true",
        "MARIADB, unwrap fails, false, false, true",
        "MARIADB, streaming, true, false, false",
        "MARIADB, streaming, false, true, false",
    )
    fun `an unread MariaDB result is cancelled before it is closed, one the driver holds whole or that ended is not`(
        kind: DatabaseKind,
        result: String,
        reachedEnd: Boolean,
        failed: Boolean,
        cancelled: Boolean,
    ) {
        val order = mutableListOf<String>()
        val live = live(resultSet(result, order), order)

        statements(db.session, kind).end(live, reachedEnd, failed)

        val expected =
            if (cancelled) {
                listOf("statement cancel", "close result", "statement close")
            } else {
                listOf("close result", "statement close")
            }
        assertEquals(expected, order)
    }
}
