package dev.sluide.jshell

import dev.sluide.java.JavaHomes
import dev.sluide.java.Jdk
import dev.sluide.projectdata.ProjectDataStore
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JShellWindowTest {
    @TempDir
    lateinit var dir: Path

    private fun window(canChoose: (() -> Boolean)? = null): JShellWindow {
        val jdks =
            JShellJdks(ProjectDataStore(dir.resolve("p"), dir.resolve("s.json"), emptyList()), JavaHomes(), { null })
        return if (canChoose == null) {
            JShellWindow(jdks, { emptyList() }, {})
        } else {
            JShellWindow(jdks, { emptyList() }, {}, canChooseProjectJdk = canChoose)
        }
    }

    @Test
    fun `listeners hear that the project's JDK changed until they are closed`() {
        val window = window()
        val heard = mutableListOf<String>()
        val first = window.onProjectJdkChanged { heard += "first" }
        window.onProjectJdkChanged { heard += "second" }

        window.projectJdkChanged()
        first.close()
        window.projectJdkChanged()

        assertEquals(listOf("first", "second", "second"), heard)
    }

    @Test
    fun `a window can choose the project's JDK unless it says it cannot`() {
        assertTrue(window().canChooseProjectJdk())
        assertFalse(window { false }.canChooseProjectJdk())
    }

    @Test
    fun `the viewer takes jsh files only, and shows Java`() {
        val viewer = JShellFileViewer()
        val jsh = dir.resolve("a.JSH").apply { writeText("int x = 1;\n") }
        val java = dir.resolve("a.java").apply { writeText("class A {}\n") }
        val binary = dir.resolve("b.jsh").apply { writeBytes(ByteArray(64)) }

        assertTrue(viewer.canOpen(jsh))
        assertFalse(viewer.canOpen(java))
        assertFalse(viewer.canOpen(binary))
        assertEquals(setOf("jsh"), viewer.fileExtensions)
    }

    /**
     * A stand-in JDK, as in ProcessAgentLauncherTest: the real launcher runs its `bin/java` script, with `user.home`
     * redirected for the installed agent files, and [block] runs meanwhile.
     */
    private fun <T> onStandIn(
        script: String,
        block: (Jdk) -> T,
    ): T {
        val java = Files.createDirectories(dir.resolve("jdk/bin")).resolve("java")
        java.writeText("#!/bin/sh\n$script\n")
        Files.setPosixFilePermissions(java, PosixFilePermissions.fromString("rwxr-xr-x"))
        val home = System.getProperty("user.home")
        System.setProperty("user.home", dir.resolve("home").toString())
        try {
            return block(Jdk(dir.resolve("jdk"), "25", 25))
        } finally {
            System.setProperty("user.home", home)
        }
    }

    @Test
    fun `the default session start launches the agent on the JDK and fails when it ends before ready`() {
        onStandIn("exit 1") { jdk ->
            assertFailsWith<JShellStartException> { JShellWindow.REAL_START_SESSION(jdk) {} }
        }
    }

    @Test
    fun `the default session start returns the session of an agent that is ready`() {
        // the ready line is the first line of the recording value.jsonl, sent by the real agent
        val ready = """{"event":"ready","javaVersion":"25.0.5","javaHome":"/jdk","agentPid":null}"""
        onStandIn("echo '$ready'\ncat >/dev/null") { jdk ->
            JShellWindow.REAL_START_SESSION(jdk) {}.use { assertEquals("25.0.5", it.javaVersion) }
        }
    }
}
