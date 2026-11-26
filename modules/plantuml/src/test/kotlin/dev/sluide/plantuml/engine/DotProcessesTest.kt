package dev.sluide.plantuml.engine

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DotProcessesTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `a process runs dot when its command is the real path or the path that was set`() {
        // Homebrew's dot is a link into the Cellar. Linux reports the link's target as the command (/proc/<pid>/exe),
        // macOS the path the process was started with.
        val real =
            Files
                .createDirectories(
                    temp.resolve("Cellar/graphviz/bin"),
                ).resolve("dot")
                .also { Files.createFile(it) }
        val link = Files.createSymbolicLink(Files.createDirectories(temp.resolve("bin")).resolve("dot"), real)

        assertTrue(DotProcesses.isDot(real.toRealPath().toString(), link))
        assertTrue(DotProcesses.isDot(link.toString(), link))
    }

    @Test
    fun `only the children that run dot are killed`() {
        // sleep stands in for dot; cat with its input open is another child that must live on.
        val sleep = listOf("/bin/sleep", "/usr/bin/sleep").map(Path::of).first { Files.isExecutable(it) }
        val dot = ProcessBuilder(sleep.toString(), "30").start()
        val other = ProcessBuilder("cat").start()
        try {
            // Both have their command once they run it.
            awaitTrue { dot.info().command().isPresent && other.info().command().isPresent }

            DotProcesses.killer(sleep).now()

            awaitTrue { !dot.isAlive }
            assertTrue(other.isAlive)
        } finally {
            dot.destroyForcibly()
            other.destroyForcibly()
        }
    }

    private fun awaitTrue(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000
        while (!condition()) {
            assertTrue(System.nanoTime() < deadline, "condition not met within 10 s")
            Thread.sleep(10)
        }
    }

    @Test
    fun `another program, a dot elsewhere or no command is not dot`() {
        val dot = Files.createFile(temp.resolve("dot"))

        assertFalse(DotProcesses.isDot(null, dot))
        assertFalse(DotProcesses.isDot("/usr/bin/sleep", dot))
        assertFalse(DotProcesses.isDot(temp.resolve("other/dot").toString(), dot))
        assertFalse(DotProcesses.isDot("dot", dot))
    }

    @Test
    fun `a dot that is gone is still matched by the path that was set, and only by it`() {
        val gone = temp.resolve("gone/dot")

        assertTrue(DotProcesses.isDot(gone.toString(), gone))
        assertFalse(DotProcesses.isDot(null, gone))
    }
}
