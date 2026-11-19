package dev.sluide.httpclient.send

import java.text.MessageFormat
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * The records the JDK's platform logger of [loggerName] (`System.getLogger(name)`, which logs through
 * `java.util.logging` here) writes while the capture is open, with their parameters filled in. Same idea as the one
 * of the databases module's tests.
 */
internal class LogCapture(
    loggerName: String,
) : AutoCloseable {
    /** The formatted message of each record, and the text of its throwable if it has one. */
    val lines = CopyOnWriteArrayList<String>()

    private val logger = Logger.getLogger(loggerName)
    private val previousLevel = logger.level
    private val handler =
        object : Handler() {
            override fun publish(record: LogRecord) {
                val message = record.message.orEmpty()
                val parameters = record.parameters
                lines += if (parameters.isNullOrEmpty()) message else MessageFormat.format(message, *parameters)
                record.thrown?.let { lines += it.toString() }
            }

            override fun flush() = Unit

            override fun close() = Unit
        }

    init {
        logger.level = Level.ALL
        logger.addHandler(handler)
    }

    override fun close() {
        logger.removeHandler(handler)
        logger.level = previousLevel
    }
}
