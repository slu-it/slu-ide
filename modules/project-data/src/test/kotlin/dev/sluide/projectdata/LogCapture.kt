package dev.sluide.projectdata

import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * The records the JDK's platform logger of [loggerName] (`System.getLogger(name)`, which logs through
 * `java.util.logging` here) writes while the capture is open.
 */
class LogCapture(
    loggerName: String,
) : AutoCloseable {
    /** The message of each record with its level. */
    val lines = CopyOnWriteArrayList<Pair<Level, String>>()

    /** The throwable of each record that has one. */
    val thrown = CopyOnWriteArrayList<Throwable>()

    private val logger = Logger.getLogger(loggerName)
    private val previousLevel = logger.level
    private val handler =
        object : Handler() {
            override fun publish(record: LogRecord) {
                lines += record.level to record.message
                record.thrown?.let { thrown += it }
            }

            override fun flush() = Unit

            override fun close() = Unit
        }

    init {
        logger.level = Level.ALL
        logger.addHandler(handler)
    }

    fun messages() = lines.map { it.second }

    override fun close() {
        logger.removeHandler(handler)
        logger.level = previousLevel
    }
}
