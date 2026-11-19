package dev.sluide.testsupport

import java.text.MessageFormat
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * The records the JDK's platform logger of [loggerName] (`System.getLogger(name)`, which logs through
 * `java.util.logging` here) writes while the capture is open. The logger's level is [Level.ALL] meanwhile; [close]
 * puts the level back and removes the handler.
 */
class LogCapture(
    loggerName: String,
) : AutoCloseable {
    /** The message of each record, its parameters filled in, with its level. */
    val lines = CopyOnWriteArrayList<Pair<Level, String>>()

    /** The throwable of each record that has one. */
    val thrown = CopyOnWriteArrayList<Throwable>()

    private val logger = Logger.getLogger(loggerName)
    private val previousLevel = logger.level
    private val handler =
        object : Handler() {
            override fun publish(record: LogRecord) {
                lines += record.level to formatted(record)
                record.thrown?.let { thrown += it }
            }

            override fun flush() = Unit

            override fun close() = Unit
        }

    init {
        logger.level = Level.ALL
        logger.addHandler(handler)
    }

    /** The message of each record. */
    fun messages(): List<String> = lines.map { it.second }

    override fun close() {
        logger.removeHandler(handler)
        logger.level = previousLevel
    }

    private fun formatted(record: LogRecord): String {
        val message = record.message.orEmpty()
        val parameters = record.parameters
        if (parameters.isNullOrEmpty()) return message
        // A pattern that cannot be formatted must not throw into the code under test: its raw text is kept.
        return try {
            MessageFormat.format(message, *parameters)
        } catch (_: IllegalArgumentException) {
            message
        }
    }
}
