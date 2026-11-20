package dev.sluide.jshell

import dev.sluide.jshell.EvalEvent.Finished
import dev.sluide.jshell.EvalEvent.Output
import dev.sluide.jshell.EvalEvent.SnippetStarted
import dev.sluide.jshell.EvalEvent.Value
import dev.sluide.jshell.SessionEvent.Ended
import dev.sluide.testsupport.LogCapture
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Starting, ending and closing a session, against the recorded agent (each test names its recording). */
class JShellSessionLifecycleTest {
    private val fixture = SessionFixture()

    @Test
    fun `an agent that dies before ready fails the start with the tail of its standard error`() {
        val stderr = FakeAgent.stderrOf("no-jshell.stderr")
        val agent = FakeAgent(emptyList(), lateStderr = stderr)
        val failure = assertFailsWith<JShellStartException> { fixture.start(agent) }
        assertEquals("JShell could not start on JDK 25 (/jdk-25)", failure.message)
        assertEquals(stderr, failure.stderrTail)
        assertTrue(agent.killed)
        assertNull(fixture.sessionEvents.poll(), "no session event for a session that never started")
    }

    @Test
    fun `an agent that does not get ready in time is killed`() {
        val agent = FakeAgent(emptyList(), "Picked up JAVA_TOOL_OPTIONS: x\n", exits = false, lateStderr = "late\n")
        val failure = assertFailsWith<JShellStartException> { fixture.start(agent, startTimeoutMillis = 100) }
        assertEquals("JShell did not start within 0.1 s on JDK 25 (/jdk-25)", failure.message)
        assertEquals("Picked up JAVA_TOOL_OPTIONS: x\nlate\n", failure.stderrTail)
        assertTrue(agent.killed)
    }

    @Test
    fun `an interrupted start kills the agent and keeps the interrupt`() {
        val agent = FakeAgent(emptyList(), exits = false)
        Thread.currentThread().interrupt()
        try {
            val failure = assertFailsWith<JShellStartException> { fixture.start(agent) }
            assertEquals("JShell's start on JDK 25 (/jdk-25) was interrupted", failure.message)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
        assertTrue(agent.killed)
    }

    @Test
    fun `a process that cannot start fails the start with its reason`() {
        val failure =
            assertFailsWith<JShellStartException> {
                JShellSession.start(fixture.jdk, {}, { throw IOException("Cannot run program \"java\"") })
            }
        assertEquals("JShell could not start on JDK 25 (/jdk-25): Cannot run program \"java\"", failure.message)
        assertEquals("", failure.stderrTail)
    }

    @Test
    fun `System exit ends the evaluation and then the session with its status`() {
        val agent = FakeAgent.of("system-exit")
        val session = fixture.start(agent)
        fixture.run(session, "int x = 7;", 0)
        val text = "System.out.println(\"bye\"); System.exit(3);"
        assertEquals(
            listOf(SnippetStarted(0, 26, 0), Output("bye\n", false), SnippetStarted(27, 42, 0), Finished),
            fixture.run(session, text, 0, text.length),
        )
        assertEquals(Ended("exit", 3, null), fixture.nextSessionEvent())
        assertFalse(session.alive)
        val refused = assertFailsWith<IllegalStateException> { session.evaluate("1", 0, 0) {} }
        assertEquals("The JShell session has ended", refused.message)
        val completion = session.complete("x", 1)
        assertIs<IllegalStateException>(
            assertFailsWith<ExecutionException> { completion.get(10, TimeUnit.SECONDS) }.cause,
        )
        // The agent's output ended after `ended`; nothing signals that the session read that end too.
        assertNull(fixture.sessionEvents.poll(200, TimeUnit.MILLISECONDS), "one end only")
        assertFalse(agent.killed, "the agent ended by itself")
        session.close()
        fixture.assertNoProblems(agent)
    }

    @Test
    fun `an exit without a logged status ends the session without one`() {
        val agent = FakeAgent.of("halt")
        val session = fixture.start(agent)
        assertEquals(Finished, fixture.run(session, "Runtime.getRuntime().halt(5);", 0).last())
        assertEquals(Ended("exit", null, null), fixture.nextSessionEvent())
        fixture.assertNoProblems(agent)
    }

    @Test
    fun `close ends the agent through its input and the session reports it`() {
        val agent = FakeAgent.of("stdin-eof")
        val session = fixture.start(agent)
        assertEquals(Value("x ==> 1"), fixture.run(session, "int x = 1;", 0)[1])
        session.close()
        assertFalse(session.alive)
        val refused = assertFailsWith<IllegalStateException> { session.evaluate("1", 0, 0) {} }
        assertEquals("The JShell session has ended", refused.message)
        assertNull(refused.cause, "refused before anything was written")
        assertEquals(Ended("closed", null, null), fixture.nextSessionEvent())
        assertFalse(agent.killed)
        session.close()
        // A second close has nothing to signal either.
        assertNull(fixture.sessionEvents.poll(200, TimeUnit.MILLISECONDS), "one end only")
        fixture.assertNoProblems(agent)
    }

    @Test
    fun `output after the end of the session is dropped`() {
        // Hostile order, no agent sends it: value.jsonl with output after its `ended`.
        val late = RecordedLine(0, false, mapOf("id" to null, "event" to "output", "text" to "late", "error" to false))
        val agent = FakeAgent(Recordings.load("value") + late)
        val session = fixture.start(agent)
        fixture.run(session, "int x = 5;", 0)
        session.close()
        assertEquals(Ended("closed", null, null), fixture.nextSessionEvent())
        // The output follows `ended` at once; nothing signals that the session read it.
        assertNull(fixture.sessionEvents.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `an agent that does not end after its input closed is killed`() {
        val agent = FakeAgent.of("value", endsWithInput = false)
        val session = fixture.start(agent, closeGraceMillis = 100)
        session.close()
        assertTrue(agent.killed)
        assertEquals(Ended("closed", null, null), fixture.nextSessionEvent())
    }

    @Test
    fun `an agent that dies while idle ends the session as a crash with its standard error`() {
        val agent = FakeAgent.of("killed", stderrText = "Picked up JAVA_TOOL_OPTIONS: x\n", lateStderr = "late\n")
        val session = fixture.start(agent)
        assertEquals(
            listOf(SnippetStarted(0, 10, 0), Value("x ==> 1"), Finished),
            fixture.run(session, "int x = 1;", 0),
        )
        assertEquals(Ended("crash", null, "Picked up JAVA_TOOL_OPTIONS: x\nlate\n"), fixture.nextSessionEvent())
        assertFalse(session.alive)
        assertTrue(agent.killed, "whatever is left of it")
        fixture.assertNoProblems(agent)
    }

    @Test
    fun `an agent that dies during an evaluation ends the session without Finished`() {
        val agent = FakeAgent.of("killed-running")
        val session = fixture.start(agent)
        val events = LinkedBlockingQueue<EvalEvent>()
        session.evaluate("while (true) {}", 0, 0) { events.put(it) }
        assertEquals(Ended("crash", null, ""), fixture.nextSessionEvent())
        assertEquals(listOf<EvalEvent>(SnippetStarted(0, 15, 0)), events.toList())
        assertFalse(session.running)
        assertFalse(session.alive)
        fixture.assertNoProblems(agent)
    }

    @Test
    fun `a completion waiting when the session ends fails`() {
        val agent = FakeAgent.of("killed-running")
        val session = fixture.start(agent)
        agent.hold()
        session.evaluate("while (true) {}", 0, 0) {}
        val completion = session.complete("x", 1)
        agent.release()
        assertIs<IllegalStateException>(
            assertFailsWith<ExecutionException> { completion.get(10, TimeUnit.SECONDS) }.cause,
        )
    }

    @Test
    fun `a broken input fails the requests and a stop says nothing`() {
        val agent = FakeAgent.of("value")
        fixture.start(agent).use { session ->
            agent.hold()
            val events = LinkedBlockingQueue<EvalEvent>()
            session.evaluate("int x = 5;", 0, 0) { events.put(it) }
            // from now on as a pipe whose reader is gone
            agent.inputBroken = true
            session.stop()
            agent.release()
            assertEquals(Finished, fixture.until(events, Finished).last())
            val refused = assertFailsWith<IllegalStateException> { session.evaluate("int x = 5;", 0, 0) {} }
            assertEquals("The JShell session has ended", refused.message)
            assertIs<IOException>(refused.cause)
            assertFalse(session.running)
            val completion = session.complete("x", 1)
            assertIs<IllegalStateException>(
                assertFailsWith<ExecutionException> { completion.get(10, TimeUnit.SECONDS) }.cause,
            )
        }
    }

    @Test
    fun `an output that breaks off ends the session as a crash`() {
        val agent = FakeAgent.of("value")
        LogCapture(JShellSession::class.java.name).use { log ->
            val session = fixture.start(agent)
            agent.breakOutput()
            assertEquals("crash", (fixture.nextSessionEvent() as Ended).reason)
            assertFalse(session.alive)
            assertEquals(listOf("The JShell agent's output broke off"), log.messages())
            assertEquals(listOf("Broken pipe"), log.thrown.map { it.message })
        }
    }

    @Test
    fun `a session listener that throws is logged`() {
        val agent = FakeAgent.of("late-output")
        LogCapture(JShellSession::class.java.name).use { log ->
            val ended = LinkedBlockingQueue<SessionEvent>()
            val session =
                JShellSession.start(fixture.jdk, {
                    ended.put(it)
                    throw IllegalStateException("session listener broke")
                }, { agent }, 10_000, 2_000)
            val text =
                // a snippet's text, no sleep of the test
                "new Thread(() -> { try { Thread.sleep(500); } catch (Exception e) {} " +
                    "System.out.println(\"late\"); }).start()"
            fixture.run(session, text, 0)
            session.close()
            assertEquals(Ended("closed", null, null), fixture.until(ended, Ended("closed", null, null)).last())
            assertEquals(listOf("session listener broke", "session listener broke"), log.thrown.map { it.message })
        }
    }
}
