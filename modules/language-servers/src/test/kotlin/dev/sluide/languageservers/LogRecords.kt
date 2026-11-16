package dev.sluide.languageservers

import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/** The messages [logger] logs while this is open; holds the logger, which java.util.logging keeps only weakly. */
internal class LogRecords(
    private val logger: Logger,
) : Handler(),
    AutoCloseable {
    private val records = CopyOnWriteArrayList<LogRecord>()

    init {
        logger.addHandler(this)
    }

    fun messages(): List<String> = records.map { it.message }

    /** The messages logged at [level]. */
    fun messages(level: Level): List<String> = records.filter { it.level == level }.map { it.message }

    override fun publish(record: LogRecord) {
        records += record
    }

    override fun flush() = Unit

    override fun close() {
        logger.removeHandler(this)
    }
}
