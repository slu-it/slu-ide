package dev.sluide.ide.jshell

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class JShellWiringTest {
    @TempDir
    lateinit var dir: Path

    private fun jdk(
        name: String,
        version: String,
        modules: String?,
    ): Path {
        val home = Files.createDirectories(dir.resolve("jvm").resolve(name))
        Files.createDirectories(home.resolve("bin")).resolve("java").writeText("")
        home.resolve("release").writeText("JAVA_VERSION=\"$version\"\n" + (modules?.let { "MODULES=\"$it\"\n" } ?: ""))
        return home
    }

    @Test
    fun `the installed JDKs offered are the ones with JShell, newest first`() {
        val full = jdk("full-21", "21.0.1", null)
        val newer = jdk("full-25", "25.0.5", "java.base jdk.jshell")
        jdk("jlinked", "25.0.5", "java.base")
        jdk("old", "1.8.0_402", null)

        val offered =
            installedJdksWithJShell(dir.resolve("home"), macOs = false, systemFolders = listOf(dir.resolve("jvm")))

        assertEquals(listOf(newer, full), offered.map { it.home })
    }
}
