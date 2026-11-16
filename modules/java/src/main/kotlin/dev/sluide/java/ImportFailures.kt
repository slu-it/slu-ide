package dev.sluide.java

import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import java.io.IOException
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

private const val MAX_WRAPPER_BYTES = 16 * 1024
private const val MAX_MESSAGE_CHARS = 300
private const val NO_PROJECT = "No project was found; see the log."

/** The distribution file's name: `gradle-<version>-(bin|all).zip`; the version starts with a digit. */
private val DISTRIBUTION = Regex("""^gradle-(\d[0-9A-Za-z.]*(?:-[0-9A-Za-z.]+)*?)-(?:bin|all)\.zip$""")

/** What a version number from the project or the server may look like to be written into a banner. */
private val VERSION = Regex("""^\d[0-9A-Za-z.+_-]{0,40}$""")

/** Why a root's import failed, in words for the banner (ADR 0179). Pure except [gradleVersion], which reads a file. */
internal object ImportFailures {
    /**
     * The Gradle version in the root's `gradle/wrapper/gradle-wrapper.properties` `distributionUrl`, taken from the
     * distribution's file name only; null if there is no file, it is larger than 16 KB, or no version is found.
     * Blocks: off the FX thread.
     */
    fun gradleVersion(root: Path): String? {
        val file = root.resolve("gradle/wrapper/gradle-wrapper.properties")
        val bytes =
            try {
                Files.newInputStream(file).use { it.readNBytes(MAX_WRAPPER_BYTES + 1) }
            } catch (_: IOException) {
                return null
            }
        if (bytes.size > MAX_WRAPPER_BYTES) return null
        val properties = Properties()
        try {
            properties.load(StringReader(String(bytes, Charsets.ISO_8859_1)))
        } catch (_: IllegalArgumentException) {
            return null
        }
        val url = properties.getProperty("distributionUrl") ?: return null
        return DISTRIBUTION
            .find(url.substringAfterLast('/'))
            ?.groupValues
            ?.get(1)
            ?.takeIf(VERSION::matches)
    }

    /** The text for jdtls's event 300; a part that is unknown or not a plain version is left out. */
    fun eventDetail(
        gradle: String?,
        jdk: String?,
        newestJava: String?,
        recommendedGradle: String?,
    ): String {
        val gradleText = gradle?.takeIf(VERSION::matches)?.let { " $it" }.orEmpty()
        val jdkText = jdk?.takeIf(VERSION::matches)?.let { "JDK $it" } ?: "this JDK"
        val newest = newestJava?.takeIf(VERSION::matches)?.let { " (newest: $it)" }.orEmpty()
        val upgrade =
            recommendedGradle?.takeIf(VERSION::matches)?.let { ", or upgrade the Gradle wrapper to $it" }.orEmpty()
        return "Gradle$gradleText can't run on $jdkText$newest. Choose another JDK in Project Settings → Java$upgrade."
    }

    /**
     * The first message (its first line) of a severity-1 kept diagnostic on the root folder, else on its `pom.xml`, else
     * on its Gradle build and settings files; "No project was found; see the log." if there is none.
     */
    fun diagnosticDetail(
        root: Path,
        kept: (Path) -> List<Diagnostic>,
    ): String {
        val candidates =
            listOf(root) +
                listOf(
                    "pom.xml",
                    "build.gradle",
                    "build.gradle.kts",
                    "settings.gradle",
                    "settings.gradle.kts",
                ).map(root::resolve)
        for (file in candidates) {
            val message =
                kept(file)
                    .firstOrNull { it.severity == DiagnosticSeverity.Error }
                    ?.message
                    ?.let { it.left ?: it.right?.value }
                    ?.lineSequence()
                    ?.firstOrNull { it.isNotBlank() }
                    ?.trim()
            if (message != null) return message.take(MAX_MESSAGE_CHARS)
        }
        return NO_PROJECT
    }
}
