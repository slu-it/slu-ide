package dev.sluide.ide

import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains

/** The log file of `logback-spring.xml` (ADR 0051); the build points `sluide.log.dir` at the build folder. */
class LogFileTest {
    private val logFile =
        Path.of(
            requireNotNull(System.getProperty("sluide.log.dir")) {
                "run by Gradle, which sets sluide.log.dir"
            },
            "sluide.log",
        )
    private val marker = "log file test ${UUID.randomUUID()}"

    @Test
    fun `lines of System Logger reach the log file`() {
        withContext {
            System.getLogger(LogFileTest::class.java.name).log(System.Logger.Level.INFO, marker)
            assertContains(logFile.readText(), marker)
        }
    }

    @Test
    fun `uncaught exceptions reach the log file with their thread and stack trace`() {
        withContext {
            logUncaught(Thread("worker of $marker"), IllegalStateException("failure of $marker"))
            val text = logFile.readText()
            assertContains(text, "Uncaught exception in thread \"worker of $marker\"")
            assertContains(text, "java.lang.IllegalStateException: failure of $marker")
            assertContains(text, "at dev.sluide.ide.LogFileTest")
        }
    }

    @Test
    fun `uncaught exceptions reach the log file after the context closed`() {
        withContext {}
        logUncaught(Thread("shutdown hook of $marker"), IllegalStateException("failure of $marker"))
        assertContains(logFile.readText(), "Uncaught exception in thread \"shutdown hook of $marker\"")
    }

    @Test
    fun `uncaught exceptions go to stderr once logging stopped`() {
        val err = ByteArrayOutputStream()
        val previous = System.err
        System.setErr(PrintStream(err, true))
        try {
            logUncaught(Thread("late of $marker"), IllegalStateException("failure of $marker"), logging = false)
        } finally {
            System.setErr(previous)
        }
        assertContains(err.toString(), "Exception in thread \"late of $marker\" java.lang.IllegalStateException")
    }

    private fun withContext(test: () -> Unit) {
        SpringApplicationBuilder(SluIdeConfiguration::class.java)
            .web(WebApplicationType.NONE)
            .lazyInitialization(true)
            .run()
            .use { test() }
    }
}
