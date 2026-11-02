package dev.sluide.databases.sql

import org.springframework.stereotype.Component

/** Executed statements per console key (connection), newest first, at most [MAX] each; a repeated statement moves to the top. App lifetime, thread-safe. */
@Component
class QueryHistory {
    private val byKey = HashMap<String, ArrayDeque<String>>()

    fun add(
        key: String,
        sql: String,
    ) {
        val statement = sql.trim()
        if (statement.isEmpty()) return
        synchronized(byKey) {
            val list = byKey.getOrPut(key) { ArrayDeque() }
            list.remove(statement)
            list.addFirst(statement)
            while (list.size > MAX) list.removeLast()
        }
    }

    /** The statements of [key], newest first. */
    fun entries(key: String): List<String> = synchronized(byKey) { byKey[key]?.toList() ?: emptyList() }

    companion object {
        const val MAX = 200
    }
}
