package dev.sluide.jshell

import dev.sluide.java.Jdk
import dev.sluide.java.Jdks
import dev.sluide.jshell.EvalEvent.Finished
import dev.sluide.jshell.EvalEvent.SnippetStarted
import dev.sluide.jshell.EvalEvent.Stopped
import dev.sluide.jshell.EvalEvent.Value
import dev.sluide.jshell.SessionEvent.Ended
import dev.sluide.jshell.engine.AgentFiles
import dev.sluide.jshell.engine.ProcessAgentLauncher
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The session with the real agent on real JDKs: `SLUIDE_TEST_JSHELL` names JDK homes (separated like a class path,
 * e.g. `/usr/lib/jvm/java-25-openjdk-arm64:/usr/lib/jvm/java-21-openjdk-arm64`); skipped without it. The agent files
 * are installed into a temporary folder; each test starts its own agent and execution JVM and checks they are gone.
 */
class JShellRealTest {
    companion object {
        @JvmStatic
        fun jdks(): List<String> =
            System.getenv("SLUIDE_TEST_JSHELL")?.split(File.pathSeparator)?.filter(String::isNotBlank) ?: listOf("")
    }

    private val fixture = SessionFixture()

    @TempDir
    lateinit var agentFiles: Path

    private fun jdk(home: String): Jdk {
        assumeTrue(home.isNotEmpty(), "SLUIDE_TEST_JSHELL names no JDK")
        val jdk = Jdks.read(Path.of(home)) ?: fail("no JDK at $home")
        assertTrue(Jdks.hasJShell(jdk), "$home has JShell")
        return jdk
    }

    private fun launcher(vararg jvmOptions: String) =
        ProcessAgentLauncher(classPath = { AgentFiles.install(agentFiles) }, jvmOptions = jvmOptions.toList())

    /** The processes this test JVM started since [before]: the agent and its execution JVM. */
    private fun started(before: Set<ProcessHandle>): List<ProcessHandle> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (true) {
            val now =
                ProcessHandle
                    .current()
                    .descendants()
                    .toList()
                    .filter { it !in before && it.isAlive }
            if (now.size >= 2 || System.nanoTime() > deadline) return now
            Thread.sleep(20)
        }
    }

    private fun awaitGone(processes: List<ProcessHandle>) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (processes.any { it.isAlive } && System.nanoTime() < deadline) Thread.sleep(20)
        assertEquals(emptyList(), processes.filter { it.isAlive }.map { it.info().commandLine().orElse("?") })
    }

    @ParameterizedTest
    @MethodSource("jdks")
    fun `a value, a stopped endless loop and the kept state`(home: String) {
        val jdk = jdk(home)
        val before =
            ProcessHandle
                .current()
                .descendants()
                .toList()
                .toSet()
        val session = JShellSession.start(jdk, { fixture.sessionEvents.put(it) }, launcher())
        val processes = started(before)
        assertEquals(2, processes.size, "the agent and the execution JVM")
        assertTrue(session.javaVersion.startsWith(jdk.version.substringBefore('.')), session.javaVersion)
        assertEquals(
            listOf(SnippetStarted(0, 10, 0), Value("x ==> 5"), Finished),
            fixture.run(session, "int x = 5;", 0),
        )
        val events = LinkedBlockingQueue<EvalEvent>()
        session.evaluate("while (true) {}", 0, 0) { events.put(it) }
        assertEquals(SnippetStarted(0, 15, 0), events.poll(10, TimeUnit.SECONDS))
        session.stop()
        assertEquals(listOf(Stopped, Finished), fixture.until(events, Finished))
        assertEquals(Value("x ==> 5"), fixture.run(session, "x", 0)[1])
        session.close()
        assertEquals(Ended("closed", null, null), fixture.nextSessionEvent())
        awaitGone(processes)
    }

    @ParameterizedTest
    @MethodSource("jdks")
    fun `System exit ends the session with its status`(home: String) {
        val jdk = jdk(home)
        val before =
            ProcessHandle
                .current()
                .descendants()
                .toList()
                .toSet()
        val session = JShellSession.start(jdk, { fixture.sessionEvents.put(it) }, launcher())
        val processes = started(before)
        assertEquals(Finished, fixture.run(session, "System.exit(3);", 0).last())
        assertEquals(Ended("exit", 3, null), fixture.nextSessionEvent())
        assertFalse(session.alive)
        assertFailsWith<IllegalStateException> { session.evaluate("1", 0, 0) {} }
        awaitGone(processes)
        session.close()
    }

    @ParameterizedTest
    @MethodSource("jdks")
    fun `a killed agent leaves no execution JVM`(home: String) {
        val jdk = jdk(home)
        val before =
            ProcessHandle
                .current()
                .descendants()
                .toList()
                .toSet()
        val session = JShellSession.start(jdk, { fixture.sessionEvents.put(it) }, launcher())
        val processes = started(before)
        val agent = processes.single { it.parent().map(ProcessHandle.current()::equals).orElse(false) }
        // a snippet keeps the execution JVM busy, as in the spike's measurement of the orphan
        session.evaluate("while (true) {}", 0, 0) {}
        agent.destroyForcibly()
        val ended = fixture.nextSessionEvent()
        assertEquals("crash", (ended as Ended).reason)
        awaitGone(processes)
        session.close()
    }

    @ParameterizedTest
    @MethodSource("jdks")
    fun `a runtime without JShell fails the start with the agent's error`(home: String) {
        val jdk = jdk(home)
        val failure =
            assertFailsWith<JShellStartException> {
                JShellSession.start(jdk, {}, launcher("--limit-modules", "java.base"))
            }
        assertTrue(failure.stderrTail.contains("java.lang.NoClassDefFoundError: jdk/jshell/JShell"), failure.stderrTail)
    }
}
