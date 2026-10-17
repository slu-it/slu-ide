package dev.sluide.ide

import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains

/** The log file of `logback-spring.xml` (ADR 0051); the build points `sluide.log.dir` at the build folder. */
class LogFileTest {
    @Test
    fun `lines of System Logger reach the log file`() {
        val logFile =
            Path.of(
                requireNotNull(System.getProperty("sluide.log.dir")) {
                    "run by Gradle, which sets sluide.log.dir"
                },
                "sluide.log",
            )
        val marker = "log file test ${UUID.randomUUID()}"
        SpringApplicationBuilder(SluIdeConfiguration::class.java)
            .web(WebApplicationType.NONE)
            .lazyInitialization(true)
            .run()
            .use {
                System.getLogger(LogFileTest::class.java.name).log(System.Logger.Level.INFO, marker)
                assertContains(logFile.readText(), marker)
            }
    }
}
