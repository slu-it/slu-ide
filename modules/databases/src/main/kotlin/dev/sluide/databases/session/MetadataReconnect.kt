package dev.sluide.databases.session

import dev.sluide.databases.connect.ConnectionConfig
import java.sql.Connection

/** The per-statement settings of a session and how long close() waits for running work. */
internal class SessionLimits(
    /** Read per statement, 0 for none. */
    val statementTimeoutSeconds: () -> Int,
    /** Read per statement: the most rows PostgreSQL reads into memory for a statement in a user's AUTO transaction. */
    val fetchAllCap: () -> Int,
    /** How long close() lets running work end before it aborts the connection. */
    val closeGraceMillis: Long,
)

/**
 * Opens the metadata connection again after it was lost, with the session's own copy of the password. The copy is
 * never logged and is zeroed by [forget] when the session closes.
 */
internal class MetadataReconnect(
    private val connect: (ConnectionConfig, CharArray?) -> Connection,
    private val password: CharArray?,
) {
    @Volatile private var forgotten = false

    /** A new metadata connection, set up like the first; throws when the session closed or connecting fails. */
    fun open(config: ConnectionConfig): Connection {
        if (forgotten) throw DbSessionClosedException()
        return connect(config, password).also { connection ->
            try {
                JdbcSession.setUpMetadata(connection)
            } catch (e: Exception) {
                quietly { connection.close() }
                throw e
            }
        }
    }

    fun forget() {
        forgotten = true
        password?.fill('\u0000')
    }
}
