package dev.sluide.jshell.view

import dev.sluide.java.Jdk
import dev.sluide.jshell.FakeAgent
import dev.sluide.jshell.JShellJdk
import dev.sluide.jshell.JShellStartException
import dev.sluide.jshell.Recordings
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Completion in [JShellRuns] and the sessions it starts: the start it shares with runs, the notes the first run writes,
 * and a session no run used (unseen) that Run All, Restart, a JDK change or a crash ends; on the fake agent's
 * recordings, the test thread playing the FX thread. The rest of the session life is `JShellRunsTest`'s.
 */
class JShellRunsCompletionTest {
    private val fixture = RunsFixture()
    private val runs get() = fixture.runs

    private val start = "note: JShell 25.0.5 (the project's JDK)"

    @Test
    fun `complete while the session starts waits for the same start, and Run stays on meanwhile`() {
        fixture.nextAgent(FakeAgent.of("completion"))

        // CompletableFuture runs what waits on one future last-in first-out, and the recording has its requests in the
        // other order: the one the recording has first is asked last
        val second = runs.complete("\"abc\".len", 9)
        val first = runs.complete("\"abc\".", 6)

        assertTrue(runs.canRun, "a run would wait for the same start")
        assertFalse(runs.canStop)
        fixture.pumpUntil("both answers") { first.isDone && second.isDone }
        assertEquals(1, fixture.starts)
    }

    @Test
    fun `complete after dispose starts no session`() {
        runs.dispose()

        val answer = runs.complete("x", 1)

        assertEquals(0, fixture.starts)
        assertIs<IllegalStateException>(assertFailsWith<ExecutionException> { answer.get(10, TimeUnit.SECONDS) }.cause)
    }

    @Test
    fun `complete starts the session and answers`() {
        fixture.nextAgent(FakeAgent.of("completion"))

        val answer = runs.complete("\"abc\".", 6)
        fixture.pumpUntil("the completions") { answer.isDone }

        assertEquals(6, answer.get().anchor)
        assertEquals(
            "charAt(",
            answer
                .get()
                .items
                .first()
                .text,
        )
        assertEquals(emptyList(), fixture.log, "no note before a run")
    }

    @Test
    fun `a run while completion starts the session waits for that start and runs once on it, after its note`() {
        val lines = Recordings.load("completion").associateBy { it.number }
        // CompletableFuture runs what waits on one future last-in first-out: the run's request goes first. The agent
        // answers each request on its own, so the recorded exchanges are replayed in the order they are sent.
        fixture.nextAgent(FakeAgent(listOf(1, 6, 7, 8, 9, 2, 3, 16, 17).map { lines.getValue(it) }))
        val answer = runs.complete("\"abc\".", 6)

        runs.run("String greeting = \"hi\";", 0, 0)
        fixture.pumpUntil("the run and the answer") { !runs.running && answer.isDone }

        assertEquals(1, fixture.starts)
        assertEquals(
            listOf(
                start,
                "start: String greeting = \"hi\";",
                "event: SnippetStarted(start=0, end=23, line=0)",
                "event: Value(feedback=greeting ==> \"hi\")",
                "event: Finished",
            ),
            fixture.log,
        )
        assertEquals(6, answer.get().anchor)
    }

    @Test
    fun `after a JDK change completion starts the new session quietly, and the next run notes the restart and start`() {
        fixture.nextAgent(FakeAgent.of("value"))
        runs.run("int x = 5;", 0, 0)
        fixture.pumpUntilIdle()
        fixture.nextAgent(FakeAgent.of("completion", only = listOf(1, 2, 3, 6, 7, 8, 9, 16, 17)))
        fixture.resolved = JShellJdk(Jdk(Path.of("/jdk-21"), "21.0.1", 21), "the project's JDK", null)
        runs.jdkChanged()
        fixture.log.clear()

        val answer = runs.complete("\"abc\".", 6)
        fixture.pumpUntil("the answer") { answer.isDone }
        assertEquals(emptyList(), fixture.log, "completion writes no note")

        runs.run("String greeting = \"hi\";", 0, 0)
        fixture.pumpUntilIdle()
        assertEquals(
            listOf("note: Session restarted on JDK 21", start, "start: String greeting = \"hi\";"),
            fixture.log.take(3),
        )
        assertEquals(2, fixture.starts)
    }

    /** Completion starts a session on [agent] and answers; no run has used that session. */
    private fun completed(agent: FakeAgent = FakeAgent.of("completion", only = listOf(1, 2, 3, 16, 17))) {
        fixture.nextAgent(agent)
        val answer = runs.complete("\"abc\".", 6)
        fixture.pumpUntil("the completions") { answer.isDone }
    }

    @Test
    fun `Run All runs on a session only completion used, without a restart`() {
        completed(FakeAgent.of("complete-and-run-all"))

        runs.runAll("\"abc\".length()")
        fixture.pumpUntilIdle()

        assertEquals(1, fixture.starts)
        assertEquals(
            listOf(
                start,
                "start: \"abc\".length()",
                "event: SnippetStarted(start=0, end=14, line=0)",
                "event: Value(feedback=\$1 ==> 3)",
                "event: Finished",
            ),
            fixture.log,
        )
    }

    @Test
    fun `Restart of a session only completion used notes nothing`() {
        completed()
        runs.restart()
        fixture.nextAgent(FakeAgent.of("value"))

        runs.run("int x = 5;", 0, 0)
        fixture.pumpUntilIdle()

        assertEquals(2, fixture.starts)
        assertEquals(listOf(start, "start: int x = 5;"), fixture.log.take(2))
    }

    @Test
    fun `a JDK change that closes a session only completion used notes no restart`() {
        completed()
        fixture.resolved = JShellJdk(Jdk(Path.of("/jdk-21"), "21.0.1", 21), "the project's JDK", null)
        runs.jdkChanged()
        fixture.nextAgent(FakeAgent.of("value"))

        runs.run("int x = 5;", 0, 0)
        fixture.pumpUntilIdle()

        assertEquals(listOf(Path.of("/jdk-25"), Path.of("/jdk-21")), fixture.startedOn)
        assertEquals(listOf(start, "start: int x = 5;"), fixture.log.take(2))
    }

    @Test
    fun `a restart owed for a used session is still noted after a JDK change closed a session only completion used`() {
        fixture.nextAgent(FakeAgent.of("value"))
        runs.run("int x = 5;", 0, 0)
        fixture.pumpUntilIdle()
        fixture.resolved = JShellJdk(Jdk(Path.of("/jdk-21"), "21.0.1", 21), "the project's JDK", null)
        runs.jdkChanged()
        completed()
        fixture.resolved = fixture.project
        runs.jdkChanged()
        fixture.nextAgent(FakeAgent.of("value"))
        fixture.log.clear()

        runs.run("int x = 5;", 0, 0)
        fixture.pumpUntilIdle()

        assertEquals(listOf("note: Session restarted on JDK 25", start), fixture.log.take(2))
    }

    @Test
    fun `a restart owed for a used session is still noted after Restart closed a session only completion used`() {
        fixture.nextAgent(FakeAgent.of("value"))
        runs.run("int x = 5;", 0, 0)
        fixture.pumpUntilIdle()
        fixture.resolved = JShellJdk(Jdk(Path.of("/jdk-21"), "21.0.1", 21), "the project's JDK", null)
        runs.jdkChanged()
        completed()
        runs.restart()
        fixture.nextAgent(FakeAgent.of("value"))
        fixture.log.clear()

        runs.run("int x = 5;", 0, 0)
        fixture.pumpUntilIdle()

        assertEquals(listOf("note: Session restarted on JDK 21", start), fixture.log.take(2))
    }

    @Test
    fun `the crash of a session only completion used notes nothing`() {
        // the recording ends after the answer: the agent's output ends without `ended`, a crash
        completed(FakeAgent.of("completion", only = listOf(1, 2, 3)))

        // nothing to wait for: the crash only drops the session
        fixture.pumpQuiet()

        assertEquals(emptyList(), fixture.log)
    }

    @ParameterizedTest
    @MethodSource("dev.sluide.jshell.view.JShellRunsTest#noJdk")
    fun `complete without a JDK fails`(none: JShellJdk?) {
        fixture.resolved = none

        val answer = runs.complete("x", 1)

        assertIs<IllegalStateException>(assertFailsWith<ExecutionException> { answer.get(10, TimeUnit.SECONDS) }.cause)
    }

    @Test
    fun `complete fails when the session cannot start, and notes nothing`() {
        fixture.nextStartFails(JShellStartException("no luck", ""))

        val answer = runs.complete("x", 1)
        fixture.pumpUntil("the failure") { answer.isDone }

        assertIs<JShellStartException>(assertFailsWith<ExecutionException> { answer.get() }.cause)
        assertEquals(emptyList(), fixture.log, "completion asks again with every key; a run notes the failure")
    }

    @Test
    fun `a run waiting on a start completion began notes its failure once`() {
        fixture.nextStartFails(JShellStartException("no luck", ""))
        val answer = runs.complete("x", 1)

        runs.run("x", 0, 0)
        fixture.pumpUntil("the failure") { answer.isDone && !runs.running }

        assertEquals(listOf("note: no luck"), fixture.log)
    }
}
