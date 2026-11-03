package dev.sluide.databases.result

import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.Savepoint
import java.sql.Statement
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A connection (JDK proxy) that records what is done to it in [log] (`autoCommit=false`, `prepare UPDATE …`,
 * `setObject 1=x type=1111`, `execute UPDATE …`, `commit`, `rollback`, `setSavepoint`, `rollback savepoint`, `release
 * savepoint`, `timeout 5`). [counts] gives the update count of each prepared SQL (default 1); [failOn] makes the SQL
 * throw; [failAutoCommitOn] makes `setAutoCommit(true)` throw.
 */
internal class RecordingJdbc(
    startAutoCommit: Boolean = true,
    private val counts: (String) -> Int = { 1 },
    private val failOn: (String) -> Boolean = { false },
    private val failAutoCommitOn: Boolean = false,
) {
    val log = CopyOnWriteArrayList<String>()
    private var autoCommit = startAutoCommit

    val connection: Connection =
        proxy { method, args ->
            when (method) {
                "getAutoCommit" -> {
                    autoCommit
                }

                "setAutoCommit" -> {
                    log += "autoCommit=${args[0]}"
                    if (failAutoCommitOn && args[0] == true) throw SQLException("connection broken", "08006")
                    autoCommit = args[0] as Boolean
                }

                "commit" -> {
                    log += "commit"
                }

                "prepareStatement" -> {
                    log += "prepare ${args[0]}"
                    statement(args[0] as String)
                }

                "createStatement" -> {
                    sqlStatement()
                }

                "setSavepoint" -> {
                    // pgjdbc refuses it with auto-commit on, also inside a BEGIN of the user
                    if (autoCommit) throw SQLException("Cannot establish a savepoint in auto-commit mode.", "25P01")
                    log += "setSavepoint"
                    proxy<Savepoint> { _, _ -> null }
                }

                "rollback" -> {
                    log += if (args.isEmpty()) "rollback" else "rollback savepoint"
                }

                "releaseSavepoint" -> {
                    log += "release savepoint"
                }

                else -> {
                    throw UnsupportedOperationException("fake JDBC: $method")
                }
            }
        }

    private fun sqlStatement(): Statement =
        proxy { method, args ->
            when (method) {
                "execute" -> {
                    log += "sql ${args[0]}"
                    false
                }

                "setQueryTimeout" -> {
                    log += "timeout ${args[0]}"
                }

                "close" -> {}

                else -> {
                    throw UnsupportedOperationException("fake JDBC: $method")
                }
            }
        }

    private fun statement(sql: String): PreparedStatement =
        proxy { method, args ->
            when (method) {
                "setQueryTimeout" -> {
                    log += "timeout ${args[0]}"
                }

                "setObject" -> {
                    log += "setObject ${args[0]}=${args[1]}" + if (args.size > 2) " type=${args[2]}" else ""
                }

                "executeUpdate" -> {
                    log += "execute $sql"
                    if (failOn(sql)) throw SQLException("boom", "23505")
                    counts(sql)
                }

                "close" -> {}

                else -> {
                    throw UnsupportedOperationException("fake JDBC: $method")
                }
            }
        }

    private inline fun <reified T> proxy(crossinline handler: (String, Array<Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { self, method, args ->
            when (method.name) {
                "toString" -> "fake ${T::class.java.simpleName}"
                "hashCode" -> System.identityHashCode(self)
                "equals" -> self === args?.get(0)
                else -> handler(method.name, args ?: emptyArray()).takeUnless { it == Unit }
            }
        } as T
}
