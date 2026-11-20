package dev.sluide.jshell.engine

import dev.sluide.java.Jdk
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The launcher on a stand-in JDK whose `bin/java` is a shell script. */
class ProcessAgentLauncherTest {
    @TempDir
    lateinit var dir: Path

    private fun jdk(script: String): Jdk {
        val java = Files.createDirectories(dir.resolve("jdk/bin")).resolve("java")
        java.writeText("#!/bin/sh\n$script\n")
        Files.setPosixFilePermissions(java, PosixFilePermissions.fromString("rwxr-xr-x"))
        return Jdk(dir.resolve("jdk"), "25", 25)
    }

    @Test
    fun `the agent runs on the JDK's java with its options and class path`() {
        val launcher =
            ProcessAgentLauncher(classPath = {
                listOf(Path.of("/a/agent.jar"), Path.of("/a/kotlin-stdlib.jar"))
            }, jvmOptions = listOf("-Xmx64m", "-Dx=y"))
        val agent = launcher.launch(jdk("echo \"\$@\"\necho oops >&2"))
        assertEquals(
            "-Xmx64m -Dx=y -cp /a/agent.jar:/a/kotlin-stdlib.jar dev.sluide.jshell.agent.Agent",
            agent.stdout.bufferedReader().readLine(),
        )
        assertEquals("oops", agent.stderr.bufferedReader().readLine())
        assertTrue(agent.waitFor(10_000))
    }

    @Test
    fun `by default the agent's files are installed under the user's home`() {
        val home = System.getProperty("user.home")
        System.setProperty("user.home", dir.resolve("home").toString())
        try {
            val agent = ProcessAgentLauncher().launch(jdk("echo \"\$@\""))
            val folder = dir.resolve("home/.sluide/jshell")
            val classPath =
                agent.stdout
                    .bufferedReader()
                    .readLine()
                    .split(" ")[1]
                    .split(java.io.File.pathSeparator)
            assertEquals(listOf("agent.jar", "kotlin-stdlib.jar"), classPath.map { Path.of(it).fileName.toString() })
            assertTrue(classPath.all { Path.of(it).startsWith(folder) }, classPath.toString())
            assertTrue(agent.waitFor(10_000))
        } finally {
            System.setProperty("user.home", home)
        }
    }

    @Test
    fun `the agent reads its input and ends when it is closed`() {
        val agent =
            ProcessAgentLauncher(classPath = {
                emptyList()
            }).launch(jdk("while read line; do echo \"got \$line\"; done"))
        agent.stdin.write("x\n".toByteArray())
        agent.stdin.flush()
        val output = agent.stdout.bufferedReader()
        assertEquals("got x", output.readLine())
        assertFalse(agent.waitFor(50), "still reading")
        agent.stdin.close()
        assertTrue(agent.waitFor(10_000))
    }

    @Test
    fun `kill ends the agent and the processes it started`() {
        val agent =
            ProcessAgentLauncher(
                classPath = { emptyList() },
            ).launch(jdk("sleep 60 &\necho \$!\nwait\nread line"))
        // the child's pid is printed once it runs, so the kill finds it among the descendants
        val child =
            ProcessHandle
                .of(
                    agent.stdout
                        .bufferedReader()
                        .readLine()
                        .trim()
                        .toLong(),
                ).orElseThrow()
        agent.kill()
        assertTrue(agent.waitFor(10_000))
        val deadline = System.nanoTime() + 10_000_000_000
        while (child.isAlive && System.nanoTime() < deadline) Thread.sleep(20)
        assertFalse(child.isAlive, "the child process")
    }
}
