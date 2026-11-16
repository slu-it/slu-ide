package dev.sluide.java

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JdtlsSettingsTest {
    @TempDir
    lateinit var dir: Path

    private val script = Path.of("/ws/thoreau-output.gradle")

    @Test
    fun `the settings carry the JDK, the Gradle arguments, automatic updates and source downloads`() {
        val settings = JdtlsSettings.of(Jdk(Path.of("/opt/jdk-21"), "21.0.2", 21), script)

        val expected =
            mapOf(
                "java" to
                    mapOf(
                        "import" to
                            mapOf(
                                "gradle" to
                                    mapOf(
                                        "enabled" to true,
                                        "wrapper" to mapOf("enabled" to true),
                                        "java" to mapOf("home" to "/opt/jdk-21"),
                                        "arguments" to
                                            listOf(
                                                "--init-script",
                                                "/ws/thoreau-output.gradle",
                                                "-Dorg.gradle.daemon.idletimeout=900000",
                                            ),
                                    ),
                                "maven" to mapOf("enabled" to true),
                            ),
                        "configuration" to
                            mapOf(
                                "updateBuildConfiguration" to "automatic",
                                "runtimes" to
                                    listOf(mapOf("name" to "JavaSE-21", "path" to "/opt/jdk-21", "default" to true)),
                            ),
                        "maven" to mapOf("downloadSources" to true),
                        "eclipse" to mapOf("downloadSources" to true),
                    ),
            )
        assertEquals(expected, settings)
    }

    @Test
    fun `Java 8 is JavaSE-1_8 and no JDK leaves out the home and the runtimes`() {
        val old = JdtlsSettings.of(Jdk(Path.of("/opt/jdk-8"), "1.8.0_392", 8), script)
        val none = JdtlsSettings.of(null, script)

        fun runtimes(settings: Map<String, Any?>) =
            (((settings["java"] as Map<*, *>)["configuration"]) as Map<*, *>)["runtimes"]

        fun gradle(settings: Map<String, Any?>) =
            (((settings["java"] as Map<*, *>)["import"]) as Map<*, *>)["gradle"] as Map<*, *>
        assertEquals(listOf(mapOf("name" to "JavaSE-1.8", "path" to "/opt/jdk-8", "default" to true)), runtimes(old))
        assertEquals(null, runtimes(none))
        assertTrue("java" !in gradle(none))
        assertEquals(true, gradle(none)["enabled"])
    }

    @Test
    fun `a path with spaces and quotes stays one argument`() {
        val settings = JdtlsSettings.of(null, Path.of("/w s/\"q\"/thoreau-output.gradle"))

        val arguments =
            ((((settings["java"] as Map<*, *>)["import"] as Map<*, *>)["gradle"]) as Map<*, *>)["arguments"] as List<*>
        assertEquals("/w s/\"q\"/thoreau-output.gradle", arguments[1])
    }

    @Test
    fun `the init script is written into the folder once and rewritten when it differs`() {
        val file = GradleInitScript.writeInto(dir)

        assertEquals(dir.resolve("thoreau-output.gradle"), file)
        assertEquals(GradleInitScript.CONTENT, file.readText())

        val before = Files.getLastModifiedTime(file)
        Thread.sleep(20) // The modification time has to be able to move for the check below.
        GradleInitScript.writeInto(dir)
        assertEquals(before, Files.getLastModifiedTime(file), "an identical script is not written again")

        file.writeText("// edited")
        GradleInitScript.writeInto(dir)
        assertEquals(GradleInitScript.CONTENT, file.readText())
    }

    @Test
    fun `the init script moves the source set output and the default output below the build directory`() {
        val text = GradleInitScript.CONTENT

        assertTrue("baseSourceOutputDir" in text && "hasProperty" in text, "guarded for Gradle without it")
        assertTrue("layout.buildDirectory.dir('jdtls-bin')" in text)
        assertTrue("jdtls-bin/default" in text)
    }
}
