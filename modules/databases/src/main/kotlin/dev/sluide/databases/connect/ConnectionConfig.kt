package dev.sluide.databases.connect

/** The database systems the Databases tab connects to, with their JDBC drivers bundled (ADR 0121). */
enum class DatabaseKind(
    /** The name shown in the UI. */
    val label: String,
    /** The port a new connection starts with. */
    val defaultPort: Int,
    /** Schemas (MariaDB: databases) the tree hides unless Show System Schemas is on. */
    val systemSchemas: Set<String>,
    /** Prefixes of further hidden system schemas, e.g. PostgreSQL's `pg_toast_temp_1`. */
    val systemPrefixes: List<String>,
) {
    POSTGRESQL(
        "PostgreSQL",
        5432,
        setOf("pg_catalog", "information_schema"),
        listOf("pg_toast", "pg_temp_"),
    ),
    MARIADB(
        "MariaDB",
        3306,
        setOf("mysql", "information_schema", "performance_schema", "sys"),
        emptyList(),
    ),
    ;

    /** Whether [schema] is one of the system schemas the tree hides by default. */
    fun isSystemSchema(schema: String): Boolean =
        schema in systemSchemas || systemPrefixes.any { schema.startsWith(it) }
}

/** How statements of a console are committed: each one by itself, or by an explicit Commit. */
enum class CommitMode { AUTO, MANUAL }

/** The TLS mode of a connection, in PostgreSQL's terms; mapped to the MariaDB connector's `sslMode` by `JdbcUrls`. */
enum class SslMode { DISABLE, PREFER, REQUIRE, VERIFY_CA, VERIFY_FULL }

/** The colour of a card's stripe, e.g. to mark production connections. */
enum class CardColor { NONE, RED, ORANGE, YELLOW, GREEN, BLUE, PURPLE }

/**
 * One connection of a project, as stored in the project data (field `databases`, ADR 0121). A typed password lives in
 * memory until the app restarts (`PasswordCache`); a saved one only encrypted, in [savedPassword] (ADR 0122).
 */
data class ConnectionConfig(
    /** Stable identity across renames and reorders (a random UUID). */
    val id: String,
    val name: String,
    val kind: DatabaseKind,
    val host: String = "localhost",
    val port: Int = kind.defaultPort,
    /** PostgreSQL: the database connected to; MariaDB: the default database, empty for none. */
    val database: String = "",
    val user: String = "",
    /** Connects read-only (JDBC `readOnly`): the server refuses changes and the grid cannot be edited. */
    val readOnly: Boolean = false,
    /** The mode a console starts with. */
    val commitMode: CommitMode = CommitMode.AUTO,
    val sslMode: SslMode = SslMode.PREFER,
    /** The CA certificate file for verifying the server, if any. */
    val caFile: String? = null,
    /** A JDBC URL typed by the user that replaces the generated one; `null` uses host, port and database. */
    val customUrl: String? = null,
    /** Extra properties handed to the driver. */
    val driverProperties: Map<String, String> = emptyMap(),
    val color: CardColor = CardColor.NONE,
    /** Whether the tree shows the system schemas of this connection. */
    val showSystemSchemas: Boolean = false,
    /** The password encrypted by `Secrets` (`v1:…`, ADR 0122), stored as `"password"`; `null` when none is saved. */
    val savedPassword: String? = null,
)
