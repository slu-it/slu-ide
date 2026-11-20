package dev.sluide.jshell

import dev.sluide.java.Jdk
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.fail

/** Sessions on a [FakeAgent], with their events collected. */
internal class SessionFixture {
    val jdk = Jdk(Path.of("/jdk-25"), "25.0.5", 25)
    val sessionEvents = LinkedBlockingQueue<SessionEvent>()

    fun start(
        agent: FakeAgent,
        startTimeoutMillis: Long = 10_000,
        closeGraceMillis: Long = 2_000,
    ): JShellSession =
        JShellSession.start(jdk, { sessionEvents.put(it) }, { agent }, startTimeoutMillis, closeGraceMillis)

    /** Evaluates and returns the events up to Finished. */
    fun run(
        session: JShellSession,
        text: String,
        start: Int,
        end: Int = start,
    ): List<EvalEvent> {
        val events = LinkedBlockingQueue<EvalEvent>()
        session.evaluate(text, start, end) { events.put(it) }
        return until(events, EvalEvent.Finished)
    }

    fun <T> until(
        events: LinkedBlockingQueue<T>,
        last: T,
    ): List<T> {
        val taken = mutableListOf<T>()
        while (taken.lastOrNull() != last) {
            taken += events.poll(10, TimeUnit.SECONDS) ?: fail("no event within 10 s after $taken")
        }
        return taken
    }

    fun nextSessionEvent(): SessionEvent =
        sessionEvents.poll(10, TimeUnit.SECONDS) ?: fail("no session event within 10 s")

    fun assertNoProblems(agent: FakeAgent) = assertEquals(emptyList(), agent.problems.toList())
}
