package dev.sluide.java

import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImportFailuresTest {
    @TempDir
    lateinit var root: Path

    private fun wrapper(text: String) = root.resolve("gradle/wrapper/gradle-wrapper.properties").file(text)

    private fun diagnostic(
        message: String,
        severity: DiagnosticSeverity,
    ) = Diagnostic(Range(Position(0, 0), Position(0, 1)), message, severity, "jdt")

    @Test
    fun `event 300 names Gradle, the JDK, the newest Java and the wrapper to upgrade to`() {
        assertEquals(
            "Gradle 8.14.3 can't run on JDK 25.0.5 (newest: 24). Choose another JDK in Project Settings → Java, " +
                "or upgrade the Gradle wrapper to 9.7.1.",
            ImportFailures.eventDetail("8.14.3", "25.0.5", "24", "9.7.1"),
        )
    }

    @Test
    fun `event 300 leaves out the parts that are unknown`() {
        assertEquals(
            "Gradle can't run on JDK 25 (newest: 24). Choose another JDK in Project Settings → Java, " +
                "or upgrade the Gradle wrapper to 9.7.1.",
            ImportFailures.eventDetail(null, "25", "24", "9.7.1"),
        )
        assertEquals(
            "Gradle 8.5 can't run on this JDK. Choose another JDK in Project Settings → Java.",
            ImportFailures.eventDetail("8.5", null, null, null),
        )
    }

    @Test
    fun `event 300 does not write a hostile value into the text`() {
        val text = ImportFailures.eventDetail("8.5", "25", "24\nrm -rf <b>", "9\u0000.1")
        assertEquals(
            "Gradle can't run on this JDK. Choose another JDK in Project Settings → Java.",
            ImportFailures.eventDetail("8.5\n<b>", "25 <i>", null, null),
        )

        assertEquals(
            "Gradle 8.5 can't run on JDK 25. Choose another JDK in Project Settings → Java.",
            text,
        )
    }

    @Test
    fun `the first severity 1 message of the root folder wins`() {
        val kept =
            mapOf(
                root to
                    listOf(
                        diagnostic("warn", DiagnosticSeverity.Warning),
                        diagnostic("boom\nmore", DiagnosticSeverity.Error),
                    ),
                root.resolve("pom.xml") to listOf(diagnostic("pom error", DiagnosticSeverity.Error)),
            )

        assertEquals("boom", ImportFailures.diagnosticDetail(root) { kept[it].orEmpty() })
    }

    @Test
    fun `without one on the root the pom's follows, then the build files`() {
        val pom = mapOf(root.resolve("pom.xml") to listOf(diagnostic("pom error", DiagnosticSeverity.Error)))
        val gradle =
            mapOf(
                root.resolve("build.gradle.kts") to listOf(diagnostic("build error", DiagnosticSeverity.Error)),
                root.resolve("settings.gradle") to listOf(diagnostic("settings error", DiagnosticSeverity.Error)),
            )
        val settings = mapOf(root.resolve("settings.gradle.kts") to listOf(diagnostic("kts", DiagnosticSeverity.Error)))

        assertEquals("pom error", ImportFailures.diagnosticDetail(root) { pom[it].orEmpty() })
        assertEquals("build error", ImportFailures.diagnosticDetail(root) { gradle[it].orEmpty() })
        assertEquals("kts", ImportFailures.diagnosticDetail(root) { settings[it].orEmpty() })
    }

    @Test
    fun `without a severity 1 message anywhere it says no project was found`() {
        val warnings = mapOf(root to listOf(diagnostic("warn", DiagnosticSeverity.Warning)))

        assertEquals(
            "No project was found; see the log.",
            ImportFailures.diagnosticDetail(root) { warnings[it].orEmpty() },
        )
    }

    @ParameterizedTest
    @CsvSource(
        "https\\://services.gradle.org/distributions/gradle-8.14.3-bin.zip, 8.14.3",
        "https\\://services.gradle.org/distributions/gradle-9.0-rc-1-all.zip, 9.0-rc-1",
        "https\\://mirror.example/gradle-8.5-all.zip, 8.5",
        "file\\:///opt/dists/gradle-7.6.4-bin.zip, 7.6.4",
    )
    fun `the wrapper's version is read from the distribution file name`(
        url: String,
        version: String,
    ) {
        wrapper("distributionBase=GRADLE_USER_HOME\ndistributionUrl=$url\n")

        assertEquals(version, ImportFailures.gradleVersion(root))
    }

    @ParameterizedTest
    @CsvSource(
        "https\\://x/gradle-8.5.zip",
        "https\\://x/gradle--bin.zip",
        "https\\://x/gradle-8.5-bin.zip.evil",
        "https\\://x/gradle-8.5<script>-bin.zip",
        "https\\://x/gradle-8.5 -rm-bin.zip",
        "https\\://x/my-gradle-8.5-bin.zip",
        "https\\://x/gradle-../../etc-bin.zip",
        "https\\://x/gradle-1.2.3.4.5.6.7.8.9.0.1.2.3.4.5.6.7.8.9.0.1.2.3.4.5.6.7.8.9.0-bin.zip",
    )
    fun `a distribution url that names no version gives none`(url: String) {
        wrapper("distributionUrl=$url\n")

        assertNull(ImportFailures.gradleVersion(root))
    }

    @Test
    fun `a missing wrapper file or property gives none`() {
        assertNull(ImportFailures.gradleVersion(root))
        wrapper("distributionBase=GRADLE_USER_HOME\n")
        assertNull(ImportFailures.gradleVersion(root))
    }

    @Test
    fun `an oversize wrapper file gives none even when the version is at its start`() {
        wrapper("distributionUrl=https\\://x/gradle-8.5-bin.zip\n" + "# padding\n".repeat(20_000))

        assertNull(ImportFailures.gradleVersion(root))
    }
}
