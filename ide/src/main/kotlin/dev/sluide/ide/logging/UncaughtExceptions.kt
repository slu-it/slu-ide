package dev.sluide.ide.logging

import ch.qos.logback.classic.LoggerContext
import org.slf4j.LoggerFactory

// SLF4J, not System.Logger like elsewhere: Spring Boot removes the bridge from java.util.logging when the context
// closes, so System.Logger would drop the exceptions of shutdown hooks (ADR 0051).
private val log = LoggerFactory.getLogger("dev.sluide.ide.logging.UncaughtExceptions")

/**
 * Logs an [error] no code caught on [thread], so that it reaches the log file; installed as the default handler once
 * Spring has configured logging. Spring Boot stops Logback as the last step of shutting down; after that ([logging]
 * false) the error is printed to stderr as the JVM would.
 */
internal fun logUncaught(
    thread: Thread,
    error: Throwable,
    logging: Boolean = (LoggerFactory.getILoggerFactory() as? LoggerContext)?.isStarted == true,
) {
    if (logging) {
        log.error("Uncaught exception in thread \"{}\"", thread.name, error)
    } else {
        System.err.print("Exception in thread \"${thread.name}\" ")
        error.printStackTrace()
    }
}
