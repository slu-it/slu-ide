package dev.sluide.plantuml

import dev.sluide.testsupport.LogCapture
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.jvm.optionals.getOrNull
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class GraphvizLocatorTest {
    @TempDir
    lateinit var temp: Path

    /** A stand-in `dot` in [folder]; by default it answers `-V` as Graphviz 2.43 does (on stderr, exit 0). */
    private fun dot(
        folder: String,
        executable: Boolean = true,
        script: String = ANSWERS,
    ): Path {
        val dir = Files.createDirectories(temp.resolve(folder))
        val file = Files.writeString(dir.resolve("dot"), script)
        Files.setPosixFilePermissions(
            file,
            PosixFilePermissions.fromString(if (executable) "rwx------" else "rw-------"),
        )
        return file
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '"',
        textBlock = """
        exits with an error | #!/bin/sh\necho 'dot - graphviz version 2.43.0 (0)' >&2\nexit 1\n | exit code 1
        prints no version   | #!/bin/sh\necho 'usage: dot [options]'\n | no Graphviz version in its answer
        cannot be started   | #!/nowhere/sh\n | it cannot be started""",
    )
    fun `a dot that fails its version check is not used, and the log says why`(
        case: String,
        script: String,
        why: String,
    ) {
        val found = dot("bin", script = script.replace("\\n", "\n"))

        val (located, log) =
            LogCapture(GraphvizLocator::class.java.name).use { log ->
                GraphvizLocator({ found.toString() }, emptyList()).locate() to log.messages()
            }

        assertNull(located, case)
        assertEquals(listOf("$found fails the check `dot -V` ($why): drawing with the built-in layout"), log)
    }

    @Test
    fun `the file of the version answer is deleted after the check`() {
        val found = dot("bin")
        val answer = temp.resolve("answer.txt")
        val locator = GraphvizLocator({ found.toString() }, emptyList(), 5.seconds) { Files.createFile(answer) }

        assertEquals(found, locator.locate())
        assertFalse(Files.exists(answer))
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = ["disk full", "unexpected", "interrupted"])
    fun `a version check that cannot run is logged and dot is not used`(failure: String) {
        val found = dot("bin")
        val thrown =
            when (failure) {
                "disk full" -> IOException("disk full")
                "unexpected" -> IllegalStateException("unexpected")
                else -> InterruptedException()
            }
        val why =
            when (failure) {
                "disk full" -> "no file for its answer: disk full"
                "unexpected" -> "it failed: java.lang.IllegalStateException: unexpected"
                else -> "interrupted"
            }
        val locator = GraphvizLocator({ found.toString() }, emptyList(), 5.seconds) { throw thrown }

        val (located, log) =
            LogCapture(GraphvizLocator::class.java.name).use {
                runCatching { locator.locate() }.getOrElse { "threw $it" } to it.messages()
            }
        // An interrupt is passed on: the flag is set again (and cleared here).
        val interrupted = Thread.interrupted()

        assertNull(located)
        assertEquals(listOf("$found fails the check `dot -V` ($why): drawing with the built-in layout"), log)
        assertEquals(failure == "interrupted", interrupted)
    }

    @Test
    fun `an answer that cannot be read is logged as such`() {
        val answer = temp.resolve("answer.txt")
        // It answers, and then removes the file of its answer.
        val found = dot("bin", script = "$ANSWERS rm '$answer'\n")
        val locator = GraphvizLocator({ found.toString() }, emptyList(), 5.seconds) { Files.createFile(answer) }

        val (located, log) = LogCapture(GraphvizLocator::class.java.name).use { locator.locate() to it.messages() }

        assertNull(located)
        val why = "its answer cannot be read: $answer"
        assertEquals(listOf("$found fails the check `dot -V` ($why): drawing with the built-in layout"), log)
    }

    @Test
    fun `a dot that does not answer its version check in time is killed and not used`() {
        // exec: the shell becomes the sleep, whose distinct argument finds it among this JVM's children.
        val found = dot("bin", script = "#!/bin/sh\nexec sleep 31\n")

        val (located, log) =
            LogCapture(GraphvizLocator::class.java.name).use { log ->
                GraphvizLocator({ found.toString() }, emptyList(), 300.milliseconds).locate() to log.messages()
            }

        assertNull(located)
        val why = "no answer within 300ms"
        assertEquals(listOf("$found fails the check `dot -V` ($why): drawing with the built-in layout"), log)
        val deadline = System.nanoTime() + 10_000_000_000
        while (ProcessHandle.current().children().anyMatch(::isTheSleep)) {
            assertTrue(System.nanoTime() < deadline, "the hung dot -V still runs")
            Thread.sleep(10)
        }
    }

    private fun isTheSleep(process: ProcessHandle) =
        process.isAlive && process
            .info()
            .arguments()
            .getOrNull()
            ?.toList() == listOf("31")

    @Test
    fun `an installed Graphviz passes the version check`() {
        // Only where Graphviz is installed in one of the usual folders (the sandbox has /usr/bin/dot).
        val installed =
            listOf("/opt/homebrew/bin", "/usr/local/bin", "/opt/local/bin", "/usr/bin")
                .map { Path.of(it, "dot") }
                .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
        assumeTrue(installed != null, "Graphviz's dot is not installed")

        assertEquals(installed, GraphvizLocator({ null }).locate())
    }

    @Test
    fun `the login shell's dot comes before the fallback folders`() {
        val fromShell = dot("shell")
        dot("first")
        val locator = GraphvizLocator({ "\n  $fromShell  \n" }, listOf(temp.resolve("first")))

        assertEquals(fromShell, locator.locate())
    }

    @Test
    fun `without a login shell result the first fallback folder with an executable dot file wins`() {
        dot("first", executable = false)
        // A folder named dot is executable (searchable) but no program.
        Files.createDirectories(temp.resolve("folder/dot"))
        val third = dot("third")
        dot("fourth")
        val locator =
            GraphvizLocator(
                { null },
                listOf("missing", "first", "folder", "third", "fourth").map(temp::resolve),
            )

        assertEquals(third, locator.locate())
    }

    @Test
    fun `a login shell result that is not an executable file falls back to the folders`() {
        val notExecutable = dot("shell", executable = false)
        val folder = Files.createDirectories(temp.resolve("folder/dot"))
        val fallback = dot("fallback")

        for (printed in listOf(notExecutable.toString(), folder.toString(), "dot not found", "", "\u0000")) {
            assertEquals(fallback, GraphvizLocator({ printed }, listOf(temp.resolve("fallback"))).locate(), printed)
        }
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = ["exception", "linkage error"])
    fun `a login shell lookup that throws is logged and falls back to the folders`(kind: String) {
        // The lookup starts a pseudo terminal through pty4j, which can throw or fail to load its native library.
        val failure = if (kind == "exception") IllegalStateException("no pty") else UnsatisfiedLinkError("no pty")
        val fallback = dot("fallback")
        val locator = GraphvizLocator({ throw failure }, listOf(temp.resolve("fallback")))

        val (found, log) =
            LogCapture(GraphvizLocator::class.java.name).use { log ->
                runCatching { locator.locate() }.getOrNull() to log.thrown.toList()
            }

        assertEquals(fallback, found)
        assertEquals(listOf<Throwable>(failure), log)
    }

    @Test
    fun `by default the login shell is asked`() {
        // Runs the user's real login shell (loginShellPrints); only where a dot is on the PATH, which the login shell
        // then finds too.
        val onPath =
            System
                .getenv("PATH")
                .orEmpty()
                .split(File.pathSeparator)
                .filter { it.isNotEmpty() }
                .map { Path.of(it, "dot") }
                .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
        assumeTrue(onPath != null, "no dot on the PATH")

        assertNotNull(GraphvizLocator(fallbackDirs = emptyList()).locate())
    }

    @Test
    fun `nothing found is null and kept, also when dot appears later, and no check runs`() {
        var lookups = 0
        val locator = GraphvizLocator({ lookups++.let { null } }, listOf(temp.resolve("later")))

        val log =
            LogCapture(
                GraphvizLocator::class.java.name,
            ).use { assertNull(locator.locate()).let { _ -> it.messages() } }
        dot("later")

        assertNull(locator.locate())
        assertEquals(1, lookups)
        // Not found is no failed version check.
        assertEquals(emptyList(), log)
    }

    @Test
    fun `a found dot is kept although it is removed later`() {
        val found = dot("first")
        var lookups = 0
        val locator = GraphvizLocator({ lookups++.let { found.toString() } }, emptyList())

        assertEquals(found, locator.locate())
        Files.delete(found)

        assertEquals(found, locator.locate())
        assertEquals(1, lookups)
    }

    private companion object {
        /** What Graphviz 2.43's `dot -V` prints (to stderr), as a stand-in script. */
        const val ANSWERS = "#!/bin/sh\necho 'dot - graphviz version 2.43.0 (0)' >&2\n"
    }
}
