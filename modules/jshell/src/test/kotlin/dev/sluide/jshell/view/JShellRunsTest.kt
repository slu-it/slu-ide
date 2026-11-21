package dev.sluide.jshell.view

import dev.sluide.java.Jdk
import dev.sluide.jshell.FakeAgent
import dev.sluide.jshell.JShellJdk
import dev.sluide.jshell.JShellStartException
import dev.sluide.jshell.Recordings
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The session life and notes of [JShellRuns] on the fake agent's recordings; the test thread plays the FX thread. */
class JShellRunsTest {
    private val fixture = RunsFixture()
    private val runs get() = fixture.runs

    private val start = "note: JShell 25.0.5 (the project's JDK)"

    companion object {
        /** No JDK with JShell found, and the JDK not looked up yet. */
        @JvmStatic
        fun noJdk() = listOf(JShellJdk(null, "none", "No JDK"), null).map { Arguments.of(it) }
    }

    @Test
    fun `a run starts the session, notes it, evaluates and ends with Finished`() {
        fixture.nextAgent(FakeAgent.of("value"))

        runs.run("int x = 5;", 0, 0)

        assertFalse(runs.canRun)
        assertFalse(runs.canStop, "the session is starting")
        assertTrue(runs.running)
        fixture.pumpUntilIdle()
        assertEquals(
            listOf(
                start,
                "start: int x = 5;",
                "event: SnippetStarted(start=0, end=10, line=0)",
                "event: Value(feedback=x ==> 5)",
                "event: Finished",
            ),
            fixture.log,
        )
        assertTrue(runs.canRun)
        assertFalse(runs.canStop)
        // evaluating, starting, started, finished
        assertEquals(4, fixture.changes)
    }

    @Test
    fun `a second run uses the same session`() {
        fixture.nextAgent(FakeAgent.of("compile-error"))

        runs.run("// setup\nint y = \"s\";\n", 9, 9)
        fixture.pumpUntilIdle()
        runs.run("String s = \"x\"\nint z = s.lenth();\n", 0, 34)
        fixture.pumpUntilIdle()

        assertEquals(1, fixture.starts)
        assertEquals(1, fixture.log.count { it.startsWith("note:") })
        assertTrue(fixture.log.any { it.startsWith("event: Diagnostic(start=23") })
    }

    @Test
    fun `Run and Run All do nothing while a run is on, Stop stops it`() {
        // stop.jsonl: the lines of `while (true) {}` and of its stop
        val agent = fixture.nextAgent(FakeAgent.of("stop", only = listOf(1, 6, 7, 12, 13, 14)))

        runs.run("while (true) {}", 0, 0)
        fixture.pumpUntil("the snippet to start") { "event: SnippetStarted(start=0, end=15, line=0)" in fixture.log }
        assertTrue(runs.canStop)
        assertFalse(runs.canRun)
        runs.run("other", 0, 0)
        runs.runAll("other")
        runs.stop()
        fixture.pumpUntilIdle()

        assertEquals(1, fixture.log.count { it.startsWith("start:") })
        assertEquals(listOf("event: Stopped", "event: Finished"), fixture.log.takeLast(2))
        assertFalse(runs.canStop)
        assertTrue(runs.canRun)
        assertEquals(emptyList(), agent.problems.toList())
    }

    @Test
    fun `Stop without a running snippet does nothing`() {
        runs.stop()
        assertFalse(runs.canStop)
        assertEquals(0, fixture.starts)
    }

    @Test
    fun `System exit notes the end with its status and the next run starts a new session`() {
        fixture.nextAgent(FakeAgent.of("system-exit"))
        runs.run("int x = 7;", 0, 0)
        fixture.pumpUntilIdle()
        runs.run("System.out.println(\"bye\"); System.exit(3);", 0, 42)
        fixture.pumpUntil("the end") { "note: Session ended (exit 3)" in fixture.log }

        assertFalse(runs.running)
        assertTrue(runs.canRun)
        fixture.nextAgent(FakeAgent.of("value"))
        runs.run("int x = 5;", 0, 0)
        fixture.pumpUntilIdle()
        assertEquals(2, fixture.starts)
        assertEquals(2, fixture.log.count { it == start })
    }

    @Test
    fun `System exit without a status notes the end without one`() {
        fixture.nextAgent(FakeAgent.of("halt"))
        runs.run("Runtime.getRuntime().halt(5);", 0, 0)
        fixture.pumpUntil("the end") { "note: Session ended" in fixture.log }

        assertFalse(runs.running)
    }

    @Test
    fun `a crash notes the last 10 lines of the agent's standard error`() {
        // a blank line between the lines is not shown
        val stderr = ((1..11).map { "err $it" } + "" + "err 12").joinToString("") { "$it\n" }
        fixture.nextAgent(FakeAgent.of("killed-running", stderrText = stderr))

        runs.run("while (true) {}", 0, 0)
        fixture.pumpUntil("the crash") { fixture.log.any { it.startsWith("note: JShell stopped") } }

        assertEquals(
            "note: JShell stopped unexpectedly\n" + (4..12).joinToString("\n") { "err $it" },
            fixture.log.last(),
        )
        assertFalse(runs.running)
        assertTrue(runs.canRun)
        assertFalse(runs.canStop)
    }

    @Test
    fun `a failed start notes the message and the last 10 lines of the error and leaves no session`() {
        // a blank line between the lines is not shown
        val stderr = ((1..11).map { "err $it" } + "" + "err 12").joinToString("\n")
        fixture.nextStartFails(JShellStartException("JShell could not start on JDK 25: no luck", stderr))

        runs.run("1", 0, 0)
        fixture.pumpUntilIdle()

        // evaluating, starting, the failed run, the failed start
        assertEquals(4, fixture.changes)
        assertEquals(1, fixture.starts, "a failure of the current start is not tried again")
        assertEquals(
            listOf("note: JShell could not start on JDK 25: no luck\n" + (4..12).joinToString("\n") { "err $it" }),
            fixture.log,
        )
        assertTrue(runs.canRun)
        fixture.nextAgent(FakeAgent.of("value"))
        runs.run("int x = 5;", 0, 0)
        fixture.pumpUntilIdle()
        assertEquals(2, fixture.starts)
    }

    @Test
    fun `a start that fails after dispose notes nothing`() {
        fixture.nextStartFails(JShellStartException("no luck", "err"))
        runs.run("1", 0, 0)

        runs.dispose()
        fixture.pumpQuiet()

        assertEquals(emptyList(), fixture.log)
    }

    @Test
    fun `a run on a session that ended unnoticed ends at once, and its end is noted afterwards`() {
        fixture.nextAgent(FakeAgent.of("system-exit"))
        runs.run("int x = 7;", 0, 0)
        fixture.pumpUntilIdle()
        runs.run("System.out.println(\"bye\"); System.exit(3);", 0, 42)
        fixture.pumpUntil("the end of the run") { fixture.log.count { it == "event: Finished" } == 2 }
        // the session's end is queued for the FX thread but not handled yet: the view still has the session
        fixture.waitQueued()
        val before = fixture.changes

        runs.run("int y = 1;", 0, 0)

        assertFalse(runs.running)
        // evaluating, the refused evaluation
        assertEquals(before + 2, fixture.changes)
        fixture.pumpUntil("the note") { "note: Session ended (exit 3)" in fixture.log }
        assertEquals(before + 3, fixture.changes, "the session is gone")
    }

    @Test
    fun `a start that fails otherwise notes its message`() {
        fixture.nextStartFails(IllegalStateException("boom"))

        runs.run("1", 0, 0)
        fixture.pumpUntilIdle()

        assertEquals(listOf("note: JShell could not start: boom"), fixture.log)
    }

    @Test
    fun `Run All without a session evaluates the whole text with no restart note`() {
        fixture.nextAgent(FakeAgent.of("compile-error", only = listOf(1, 6, 7, 8, 9, 10, 11)))

        runs.runAll("String s = \"x\"\nint z = s.lenth();\n")
        fixture.pumpUntilIdle()

        assertEquals(start, fixture.log.first())
        assertEquals(1, fixture.log.count { it.startsWith("note:") })
        assertTrue(fixture.log.any { it.startsWith("event: Diagnostic") })
    }

    @Test
    fun `Run All closes the session, notes the restart and the new start, and evaluates the whole text`() {
        val first = fixture.nextAgent(FakeAgent.of("value"))
        runs.run("int x = 5;", 0, 0)
        fixture.pumpUntilIdle()
        val second = fixture.nextAgent(FakeAgent.of("compile-error", only = listOf(1, 6, 7, 8, 9, 10, 11)))
        fixture.log.clear()

        runs.runAll("String s = \"x\"\nint z = s.lenth();\n")
        fixture.pumpUntilIdle()

        assertTrue(first.waitFor(10_000), "the first agent was closed")
        assertEquals(listOf("note: Session restarted on JDK 25", start), fixture.log.take(2))
        assertTrue(fixture.log.any { it.startsWith("event: Diagnostic") })
        assertEquals(emptyList(), second.problems.toList())
    }

    @Test
    fun `Restart closes the session and notes it, the next run starts a new one`() {
        val first = fixture.nextAgent(FakeAgent.of("value"))
        runs.run("int x = 5;", 0, 0)
        fixture.pumpUntilIdle()
        fixture.log.clear()

        val before = fixture.changes
        runs.restart()

        assertEquals(listOf("note: Session restarted"), fixture.log)
        assertEquals(before + 1, fixture.changes)
        assertTrue(first.waitFor(10_000))
        fixture.nextAgent(FakeAgent.of("value"))
        runs.run("int x = 5;", 0, 0)
        fixture.pumpUntilIdle()
        assertEquals(2, fixture.starts)
        assertEquals(start, fixture.log[1])
    }

    @Test
    fun `Restart without a session does nothing`() {
        runs.restart()

        assertEquals(emptyList(), fixture.log)
        assertEquals(0, fixture.changes)
    }

    @Test
    fun `Restart during a run ends it, and what the old session reports later is dropped, also after a new start`() {
        val agent = fixture.nextAgent(FakeAgent.of("stop", only = listOf(1, 6, 7, 12, 13, 14)))
        runs.run("while (true) {}", 0, 0)
        fixture.pumpUntil("the snippet to start") { "event: SnippetStarted(start=0, end=15, line=0)" in fixture.log }
        // Stopped and Finished are on their way when the session is restarted: the fake keeps them back until released.
        agent.hold()
        runs.stop()

        runs.restart()

        assertFalse(runs.running)
        assertTrue(runs.canRun)
        fixture.nextAgent(FakeAgent.of("value"))
        runs.run("int x = 5;", 0, 0)
        fixture.pumpUntilIdle()
        agent.release()
        assertTrue(agent.waitFor(10_000))
        fixture.pumpQuiet()
        assertEquals(emptyList(), fixture.log.filter { it == "event: Stopped" })
        assertEquals(1, fixture.log.count { it == "event: Finished" }, "only the new run's")
    }

    @Test
    fun `late output of a session that was restarted is dropped`() {
        val agent = fixture.nextAgent(FakeAgent.of("late-output"))
        // the answer to the run, and the late output after it, wait in the fake until the session is restarted
        agent.hold()
        val text = Recordings.load("late-output").first { it.incoming }.message["text"] as String
        runs.run(text, 0, 0)
        fixture.pumpUntil("the start") { start in fixture.log }

        runs.restart()
        agent.release()
        assertTrue(agent.waitFor(10_000))
        fixture.pumpQuiet()

        assertEquals(listOf(start, "start: $text", "note: Session restarted"), fixture.log)
    }

    @Test
    fun `a JDK change to another home closes the session, the same home keeps it`() {
        val agent = fixture.nextAgent(FakeAgent.of("value"))
        runs.run("int x = 5;", 0, 0)
        fixture.pumpUntilIdle()

        val before = fixture.changes
        runs.jdkChanged()
        // no signal for "nothing happened": the close would take a few ms at most
        assertFalse(agent.waitFor(300), "the same JDK keeps the session")
        assertEquals(before + 1, fixture.changes, "the buttons are told: the JDK may have changed what runs")

        fixture.resolved = JShellJdk(Jdk(Path.of("/jdk-21"), "21.0.1", 21), "the project's JDK", null)
        runs.jdkChanged()
        assertTrue(agent.waitFor(10_000), "another JDK closes the session")
        fixture.nextAgent(FakeAgent.of("value"))
        fixture.log.clear()
        runs.run("int x = 5;", 0, 0)
        fixture.pumpUntilIdle()
        assertEquals(listOf("note: Session restarted on JDK 21", start), fixture.log.take(2))
    }

    @Test
    fun `a JDK change during the start replaces that start, and the run asked for runs on the new JDK`() {
        val first = fixture.nextAgent(FakeAgent.of("value"))
        val second = fixture.nextAgent(FakeAgent.of("value"))
        runs.run("int x = 5;", 0, 0)

        fixture.resolved = JShellJdk(Jdk(Path.of("/jdk-21"), "21.0.1", 21), "the project's JDK", null)
        runs.jdkChanged()
        fixture.pumpUntilIdle()

        assertTrue(first.waitFor(10_000), "the session on JDK 25 was closed")
        assertEquals(listOf(Path.of("/jdk-25"), Path.of("/jdk-21")), fixture.startedOn)
        // no restart note: no session was shown
        assertEquals(
            listOf(
                start,
                "start: int x = 5;",
                "event: SnippetStarted(start=0, end=10, line=0)",
                "event: Value(feedback=x ==> 5)",
                "event: Finished",
            ),
            fixture.log,
        )
        assertEquals(emptyList(), second.problems.toList())
    }

    @Test
    fun `a start on the old JDK that fails after the JDK changed is replaced too, with no failure noted`() {
        fixture.nextStartFails(JShellStartException("JShell could not start on JDK 25: no luck", "err"))
        val second = fixture.nextAgent(FakeAgent.of("value"))
        runs.run("int x = 5;", 0, 0)

        fixture.resolved = JShellJdk(Jdk(Path.of("/jdk-21"), "21.0.1", 21), "the project's JDK", null)
        runs.jdkChanged()
        fixture.pumpUntilIdle()

        assertEquals(2, fixture.starts)
        assertEquals(listOf(start, "start: int x = 5;"), fixture.log.take(2))
        assertEquals(emptyList(), fixture.log.filter { it.contains("could not start") })
        // evaluating, starting, the JDK change, the replaced start, the started session, finished
        assertEquals(6, fixture.changes)
        assertEquals(emptyList(), second.problems.toList())
    }

    @Test
    fun `a start on the old JDK that fails after the JDK went away ends the run with no note`() {
        fixture.nextStartFails(JShellStartException("JShell could not start on JDK 25: no luck", "err"))
        runs.run("int x = 5;", 0, 0)

        fixture.resolved = JShellJdk(null, "none", "No JDK")
        runs.jdkChanged()
        fixture.pumpUntilIdle()

        assertEquals(emptyList(), fixture.log)
        assertEquals(1, fixture.starts)
        assertFalse(runs.canRun)
    }

    @ParameterizedTest
    @MethodSource("noJdk")
    fun `a JDK change during the start that leaves no JDK cancels the run`(none: JShellJdk?) {
        val first = fixture.nextAgent(FakeAgent.of("value"))
        runs.run("int x = 5;", 0, 0)

        fixture.resolved = none
        runs.jdkChanged()
        fixture.pumpUntilIdle()

        assertTrue(first.waitFor(10_000), "the session was closed")
        assertEquals(emptyList(), fixture.log)
        assertEquals(1, fixture.starts)
        assertFalse(runs.canRun)
    }

    @Test
    fun `Run All after a JDK change on an idle session notes the restart, then the start`() {
        fixture.nextAgent(FakeAgent.of("value"))
        runs.run("int x = 5;", 0, 0)
        fixture.pumpUntilIdle()
        fixture.nextAgent(FakeAgent.of("compile-error", only = listOf(1, 6, 7, 8, 9, 10, 11)))
        fixture.resolved = JShellJdk(Jdk(Path.of("/jdk-21"), "21.0.1", 21), "the project's JDK", null)
        runs.jdkChanged()
        fixture.log.clear()

        runs.runAll("String s = \"x\"\nint z = s.lenth();\n")
        fixture.pumpUntilIdle()

        assertEquals(listOf("note: Session restarted on JDK 21", start), fixture.log.take(2))
        assertEquals(1, fixture.log.count { it.startsWith("note: Session restarted") })
    }

    @ParameterizedTest
    @MethodSource("noJdk")
    fun `a change to no JDK during a run closes the session after the snippet`(none: JShellJdk?) {
        // with the recording's close (24, 25), so that the agent lives on after the stop
        val agent = fixture.nextAgent(FakeAgent.of("stop", only = listOf(1, 6, 7, 12, 13, 14, 24, 25)))
        runs.run("while (true) {}", 0, 0)
        fixture.pumpUntil("the snippet to start") { "event: SnippetStarted(start=0, end=15, line=0)" in fixture.log }

        fixture.resolved = none
        runs.jdkChanged()
        runs.stop()
        fixture.pumpUntilIdle()

        assertTrue(agent.waitFor(10_000), "the session ended with the snippet")
        assertFalse(runs.canRun)
    }

    @Test
    fun `what a restarted session reports later is dropped, however the generations move on after`() {
        val old = fixture.nextAgent(FakeAgent.of("late-output"))
        // the late output of the first session waits in the fake until after the restart and the JDK changes
        old.hold()
        val text = Recordings.load("late-output").first { it.incoming }.message["text"] as String
        runs.run(text, 0, 0)
        fixture.pumpUntil("the start") { start in fixture.log }
        runs.restart()
        fixture.nextAgent(FakeAgent.of("value"))
        runs.run("int x = 5;", 0, 0)
        fixture.nextAgent(FakeAgent.of("value"))
        val jdk21 = JShellJdk(Jdk(Path.of("/jdk-21"), "21.0.1", 21), "the project's JDK", null)
        val jdk17 = JShellJdk(Jdk(Path.of("/jdk-17"), "17.0.1", 17), "the project's JDK", null)

        // three changes: the generation moves on three times while the start is on, and once more for its replacement
        fixture.resolved = jdk21
        runs.jdkChanged()
        fixture.resolved = jdk17
        runs.jdkChanged()
        fixture.resolved = jdk21
        runs.jdkChanged()
        fixture.pumpUntilIdle()
        old.release()
        assertTrue(old.waitFor(10_000))
        fixture.pumpQuiet()

        assertEquals(emptyList(), fixture.log.filter { it.startsWith("late") })
    }

    @Test
    fun `a JDK change while a snippet runs closes the session when the snippet ends`() {
        // stop.jsonl: the lines of `while (true) {}` and of its stop
        val first = fixture.nextAgent(FakeAgent.of("stop", only = listOf(1, 6, 7, 12, 13, 14)))
        runs.run("while (true) {}", 0, 0)
        fixture.pumpUntil("the snippet to start") { "event: SnippetStarted(start=0, end=15, line=0)" in fixture.log }

        fixture.resolved = JShellJdk(Jdk(Path.of("/jdk-21"), "21.0.1", 21), "the project's JDK", null)
        runs.jdkChanged()
        // no signal for "nothing happened": a close would take a few ms at most
        assertFalse(first.waitFor(300), "the snippet keeps its session")
        assertTrue(runs.running)
        runs.stop()
        fixture.pumpUntilIdle()

        assertTrue(first.waitFor(10_000), "the session closes once the snippet ended")
        fixture.nextAgent(FakeAgent.of("value"))
        fixture.log.clear()
        runs.run("int x = 5;", 0, 0)
        fixture.pumpUntilIdle()
        assertEquals(listOf("note: Session restarted on JDK 21", start), fixture.log.take(2))
    }

    @Test
    fun `a deferred JDK change that was undone keeps the session`() {
        // with the recording's close (24, 25), so that the agent lives on after the stop
        val first = fixture.nextAgent(FakeAgent.of("stop", only = listOf(1, 6, 7, 12, 13, 14, 24, 25)))
        runs.run("while (true) {}", 0, 0)
        fixture.pumpUntil("the snippet to start") { "event: SnippetStarted(start=0, end=15, line=0)" in fixture.log }
        val project = fixture.resolved
        fixture.resolved = JShellJdk(Jdk(Path.of("/jdk-21"), "21.0.1", 21), "the project's JDK", null)
        runs.jdkChanged()
        fixture.resolved = project
        runs.jdkChanged()

        runs.stop()
        fixture.pumpUntilIdle()

        // no signal for "nothing happened": a close would take a few ms at most
        assertFalse(first.waitFor(300), "the JDK is the session's again")
    }

    @Test
    fun `a JDK change during the start to the same JDK keeps the session`() {
        val agent = fixture.nextAgent(FakeAgent.of("value"))
        runs.run("int x = 5;", 0, 0)

        runs.jdkChanged()
        fixture.pumpUntilIdle()

        assertEquals(listOf(start, "start: int x = 5;"), fixture.log.take(2))
        assertEquals(1, fixture.starts)
        assertFalse(agent.waitFor(300), "the session lives")
    }

    @Test
    fun `a JDK that is the session's again by the end of the snippet keeps it, also when nobody said so`() {
        // with the recording's close (24, 25), so that the agent lives on after the stop
        val first = fixture.nextAgent(FakeAgent.of("stop", only = listOf(1, 6, 7, 12, 13, 14, 24, 25)))
        runs.run("while (true) {}", 0, 0)
        fixture.pumpUntil("the snippet to start") { "event: SnippetStarted(start=0, end=15, line=0)" in fixture.log }
        val project = fixture.resolved
        fixture.resolved = JShellJdk(Jdk(Path.of("/jdk-21"), "21.0.1", 21), "the project's JDK", null)
        runs.jdkChanged()
        fixture.resolved = project

        runs.stop()
        fixture.pumpUntilIdle()

        // no signal for "nothing happened": a close would take a few ms at most
        assertFalse(first.waitFor(300), "the JDK is the session's again")
    }

    @Test
    fun `a JDK that differs at the end of the snippet without a change told keeps the session`() {
        val first = fixture.nextAgent(FakeAgent.of("stop", only = listOf(1, 6, 7, 12, 13, 14, 24, 25)))
        runs.run("while (true) {}", 0, 0)
        fixture.pumpUntil("the snippet to start") { "event: SnippetStarted(start=0, end=15, line=0)" in fixture.log }
        fixture.resolved = JShellJdk(Jdk(Path.of("/jdk-21"), "21.0.1", 21), "the project's JDK", null)

        runs.stop()
        fixture.pumpUntilIdle()

        // the view tells every change; one that was not told waits for the next
        assertFalse(first.waitFor(300), "no change was told")
    }

    @Test
    fun `dispose before any run closes nothing`() {
        // the worker runs inline: a close of no session would throw here
        val inline = RunsFixture(inlineWorker = true)

        inline.runs.dispose()

        assertEquals(emptyList(), inline.log)
    }

    @Test
    fun `a start exception that names the failure is noted as it is`() {
        fixture.nextStartFails(JShellStartException("JShell could not start on JDK 25 (/jdk-25): no luck", "err"))

        runs.run("1", 0, 0)
        fixture.pumpUntilIdle()

        assertEquals(listOf("note: JShell could not start on JDK 25 (/jdk-25): no luck\nerr"), fixture.log)
    }

    @ParameterizedTest
    @MethodSource("noJdk")
    fun `a JDK change that leaves no JDK with JShell closes the session`(none: JShellJdk?) {
        val agent = fixture.nextAgent(FakeAgent.of("value"))
        runs.run("int x = 5;", 0, 0)
        fixture.pumpUntilIdle()

        fixture.resolved = none
        runs.jdkChanged()

        assertTrue(agent.waitFor(10_000))
        assertFalse(runs.canRun)
    }

    @Test
    fun `a JDK change while there is no session changes nothing`() {
        runs.jdkChanged()

        assertEquals(0, fixture.starts)
        assertEquals(0, fixture.changes)
    }

    @ParameterizedTest
    @MethodSource("noJdk")
    fun `no run without a JDK with JShell, or before it is known`(none: JShellJdk?) {
        fixture.resolved = none

        runs.run("1", 0, 0)
        runs.runAll("1")

        assertFalse(runs.canRun)
        assertEquals(0, fixture.starts)
        assertEquals(emptyList(), fixture.log)
        assertFalse(runs.running)
    }

    @Test
    fun `output from a thread of the snippet comes as late output`() {
        fixture.nextAgent(FakeAgent.of("late-output"))
        val text = Recordings.load("late-output").first { it.incoming }.message["text"] as String

        runs.run(text, 0, 0)
        fixture.pumpUntil("the late output") { "late: late\n" in fixture.log }

        assertEquals("event: Finished", fixture.log[fixture.log.indexOf("late: late\n") - 1])
        assertEquals(emptyList(), fixture.log.filter { it.startsWith("late(error)") })
    }

    @Test
    fun `dispose closes the session without a note and drops what comes later`() {
        val agent = fixture.nextAgent(FakeAgent.of("value"))
        runs.run("int x = 5;", 0, 0)
        fixture.pumpUntilIdle()
        fixture.log.clear()

        runs.dispose()

        assertTrue(agent.waitFor(10_000))
        fixture.pumpQuiet()
        assertEquals(emptyList(), fixture.log)
        assertFalse(runs.canRun)
    }

    @Test
    fun `dispose while the session starts closes the session that comes out of it`() {
        val agent = fixture.nextAgent(FakeAgent.of("value"))
        runs.run("int x = 5;", 0, 0)

        runs.dispose()
        val before = fixture.changes
        fixture.pumpQuiet()

        assertTrue(agent.waitFor(10_000), "the session that was starting is closed")
        assertEquals(emptyList(), fixture.log)
        assertEquals(before + 2, fixture.changes, "the cancelled run and the dropped start are told")
    }

    @Test
    fun `a start dropped by dispose cancels what waited for it`() {
        fixture.nextAgent(FakeAgent.of("value"))
        val answer = runs.complete("x", 1)

        runs.dispose()
        fixture.pumpUntil("the end of the start") { answer.isDone }

        assertIs<CancellationException>(assertFailsWith<ExecutionException> { answer.get() }.cause)
    }
}
