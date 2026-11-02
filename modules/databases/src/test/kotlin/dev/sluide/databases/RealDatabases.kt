package dev.sluide.databases

import dev.sluide.databases.connect.ConnectionConfig
import dev.sluide.databases.connect.DatabaseKind
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Real PostgreSQL and MariaDB servers in Docker containers for opt-in smoke tests (docs/testing.md): skipped unless
 * `SLUIDE_DB_SMOKE` is set, `docker` is on the `PATH` and the image is present (nothing is pulled). Each container is
 * named `sluide-smoke-db-…`, publishes its port on 127.0.0.1 only, is started with `--rm` and stopped by [stop].
 */
object RealDatabases {
    /** A running server; [connect] opens a plain JDBC connection to its database. */
    class Server(
        val kind: DatabaseKind,
        val container: String,
        val port: Int,
        val user: String,
        val password: String,
        val database: String,
    ) {
        val url: String
            get() =
                when (kind) {
                    DatabaseKind.POSTGRESQL -> "jdbc:postgresql://127.0.0.1:$port/$database"
                    DatabaseKind.MARIADB -> "jdbc:mariadb://127.0.0.1:$port/$database"
                }

        fun config(): ConnectionConfig =
            ConnectionConfig(
                id = UUID.randomUUID().toString(),
                name = "smoke ${kind.label}",
                kind = kind,
                host = "127.0.0.1",
                port = port,
                database = database,
                user = user,
            )

        /** The `connect` function a session gets: the config's user, the given password. */
        fun connect(
            config: ConnectionConfig = config(),
            password: CharArray? = this.password.toCharArray(),
        ): Connection =
            DriverManager.getConnection(
                url,
                Properties().apply {
                    setProperty("user", config.user)
                    if (password != null) setProperty("password", String(password))
                },
            )
    }

    private val images = mapOf(DatabaseKind.POSTGRESQL to "postgres:17-alpine", DatabaseKind.MARIADB to "mariadb:11")

    /** Skips the calling test unless the smoke tests are enabled and docker with [kind]'s image is there. */
    fun assumeAvailable(kind: DatabaseKind) {
        assumeTrue(System.getenv("SLUIDE_DB_SMOKE") != null, "opt-in: set SLUIDE_DB_SMOKE=1")
        val docker =
            System
                .getenv("PATH")
                ?.split(':')
                ?.any { Files.isExecutable(Path.of(it, "docker")) } == true
        assumeTrue(docker, "no docker on the PATH")
        val image = images.getValue(kind)
        assumeTrue(runCatching { docker("image", "inspect", image) }.isSuccess, "image $image is not present")
    }

    /** Starts a server of [kind] and waits until it accepts connections (at most 90 s); skips like [assumeAvailable]. */
    fun start(kind: DatabaseKind): Server {
        assumeAvailable(kind)
        val password = "smoke-${UUID.randomUUID()}"
        val name = "sluide-smoke-db-${kind.name.lowercase()}-${System.nanoTime()}"
        val (internalPort, env, user, database) =
            when (kind) {
                DatabaseKind.POSTGRESQL -> {
                    Setup(5432, listOf("POSTGRES_PASSWORD=$password"), "postgres", "postgres")
                }

                DatabaseKind.MARIADB -> {
                    Setup(3306, listOf("MARIADB_ROOT_PASSWORD=$password", "MARIADB_DATABASE=smoke"), "root", "smoke")
                }
            }
        val args = mutableListOf("run", "-d", "--rm", "--name", name, "-p", "127.0.0.1::$internalPort")
        env.forEach { args += listOf("-e", it) }
        args += images.getValue(kind)
        docker(*args.toTypedArray())
        try {
            val mapped = docker("port", name, "$internalPort").lines().first { it.startsWith("127.0.0.1:") }
            val server = Server(kind, name, mapped.substringAfterLast(':').toInt(), user, password, database)
            awaitReady(server)
            return server
        } catch (e: Throwable) {
            runCatching { docker("stop", "-t", "1", name) }
            throw e
        }
    }

    /** Stops (and thereby removes) the server's container. */
    fun stop(server: Server?) {
        if (server != null) runCatching { docker("stop", "-t", "1", server.container) }
    }

    private data class Setup(
        val port: Int,
        val env: List<String>,
        val user: String,
        val database: String,
    )

    /**
     * Both images run an init server first (MariaDB's without networking, PostgreSQL's on the socket only) and then
     * restart, so a connection that works once may be dropped; ready means two connects in a row succeed.
     */
    private fun awaitReady(server: Server) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
        var successes = 0
        var last: Exception? = null
        while (System.nanoTime() < deadline) {
            try {
                server.connect().use { it.createStatement().use { s -> s.execute("SELECT 1") } }
                if (++successes == 2) return
            } catch (e: SQLException) {
                successes = 0
                last = e
            }
            Thread.sleep(500) // polling the server until it is up; nothing signals readiness
        }
        throw AssertionError("${server.kind.label} not ready within 90 s", last)
    }

    private fun docker(vararg args: String): String {
        val process = ProcessBuilder(listOf("docker") + args).redirectErrorStream(true).start()
        val output =
            process.inputStream
                .bufferedReader()
                .readText()
                .trim()
        check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) {
            "docker ${args.first()} failed: $output"
        }
        return output
    }
}
