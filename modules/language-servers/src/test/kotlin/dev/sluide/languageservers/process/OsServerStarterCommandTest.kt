package dev.sluide.languageservers.process

import dev.sluide.languageservers.LogRecords
import dev.sluide.languageservers.ServerCommand
import dev.sluide.languageservers.awaitTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The command line, environment, working directory, standard error and exit of a server process, with `sh` scripts as
 * servers (Linux and macOS, like [OsServerStarterTest]).
 */
class OsServerStarterCommandTest {
    @TempDir
    lateinit var root: Path

    private val started = mutableListOf<ServerProcess>()
    private val logger =
        Logger.getLogger("dev.sluide.languageservers.process.ServerProcess").also {
            it.level =
                Level.ALL
        }
    private val logs = LogRecords(logger)

    @AfterEach
    fun cleanUp() {
        started.forEach { it.destroyTree() }
        logs.close()
    }

    private fun start(
        script: String,
        arguments: List<String> = emptyList(),
        environment: Map<String, String?> = emptyMap(),
        workingDirectory: Path? = null,
    ): ServerProcess {
        val command =
            ServerCommand(Path.of("/bin/sh"), listOf("-c", script) + arguments, environment, workingDirectory)
        return OsServerStarter.start("n#1", command, root).also { started += it }
    }

    private fun ServerProcess.stdout() = input.bufferedReader().readText()

    private fun stderrLines() = logs.messages(Level.INFO).filter { it.startsWith("lsp n#1 stderr: ") }

    @Test
    fun `a server runs in the project folder unless its command names another folder`() {
        val other = root.resolve("other").also { it.toFile().mkdirs() }

        assertEquals(root.toRealPath().toString(), start("pwd -P").stdout().trim())
        assertEquals(other.toRealPath().toString(), start("pwd -P", workingDirectory = other).stdout().trim())
    }

    @Test
    fun `the environment is the inherited one with the command's changes, a null value removing a variable`() {
        assumeTrue(System.getenv("HOME") != null, "the test needs an inherited variable to remove")

        val process =
            start(
                "echo \"\$SLUIDE_A|\$SLUIDE_B|\${HOME-removed}\"",
                environment = linkedMapOf("SLUIDE_A" to "a b", "HOME" to null, "SLUIDE_B" to "c;d"),
            )

        assertEquals("a b|c;d|removed", process.stdout().trim())
    }

    @Test
    fun `arguments reach the server as they are, a shell never reads them`() {
        val process = start("printf %s \"\$1\"", arguments = listOf("sh", "a;b \$HOME `x` 'q'"))

        assertEquals("a;b \$HOME `x` 'q'", process.stdout())
    }

    @Test
    fun `the start is logged with the command line cut to 300 characters, the folder and the pid`() {
        val long = "x".repeat(400)
        val before = LiveServerProcesses.all()

        start("exit 0 #$long")
        val pid = (LiveServerProcesses.all() - before).single().pid()

        val line = "/bin/sh -c exit 0 #$long"
        assertEquals(listOf("lsp n#1 start ${line.take(300)} (in $root), pid $pid"), logs.messages(Level.INFO))
    }

    @Test
    fun `standard error lines are logged, each cut to 500 characters`() {
        start("echo one >&2; printf '%0501d\\n' 0 >&2; sleep 0.3")

        awaitTrue("both lines logged") { stderrLines().size == 2 }

        assertEquals(listOf("lsp n#1 stderr: one", "lsp n#1 stderr: " + "0".repeat(500)), stderrLines())
    }

    @Test
    fun `a chatty server's first 2000 standard error lines are logged, then one note`() {
        start("i=0; while [ \$i -lt 2003 ]; do echo l\$i >&2; i=\$((i+1)); done; sleep 0.3")

        awaitTrue("the note") { logs.messages(Level.INFO).contains("lsp n#1 stderr: further lines not logged") }
        // The lines after the note have no signal of their own: give the reader time to log them if it would.
        Thread.sleep(300)

        val lines = stderrLines()
        assertEquals(2001, lines.size)
        assertEquals("lsp n#1 stderr: l0", lines.first())
        assertEquals("lsp n#1 stderr: l1999", lines[1999])
        assertEquals("lsp n#1 stderr: further lines not logged", lines.last())
    }

    @Test
    fun `waiting for the end gives the exit code, logs it with the run time and unlists the process`() {
        val before = LiveServerProcesses.all()
        val process = start("sleep 0.3; exit 7")
        val handle = (LiveServerProcesses.all() - before).single()

        assertEquals(7, assertTimeoutPreemptively(Duration.ofSeconds(30)) { process.waitFor() })

        val millis =
            Regex(
                "lsp n#1 exit 7 after (\\d+) ms",
            ).matchEntire(logs.messages(Level.INFO).last())?.groupValues?.get(1)
        assertTrue(millis != null && millis.toLong() in 250..30_000, "run time in ms: $millis")
        assertFalse(handle in LiveServerProcesses.all())
    }

    @Test
    fun `waiting a while says whether the server ended`() {
        val process = start("sleep 30")

        assertFalse(process.waitFor(50))
        process.destroyTree()

        assertTrue(assertTimeoutPreemptively(Duration.ofSeconds(30)) { process.waitFor(20_000) })
    }

    @Test
    fun `destroying the tree kills the server with what it started and unlists it`() {
        val before = LiveServerProcesses.all()
        val process = start("sleep 30 & sleep 30")
        val handle = (LiveServerProcesses.all() - before).single()
        awaitTrue("both sleeps running") { process.descendants().size == 2 }
        val children = process.descendants()
        assertTrue(children.all { it.isAlive })

        process.destroyTree()

        awaitTrue("server and children ended") { !handle.isAlive && children.none { it.isAlive } }
        assertFalse(handle in LiveServerProcesses.all())
    }
}
