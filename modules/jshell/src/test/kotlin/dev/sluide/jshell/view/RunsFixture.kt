package dev.sluide.jshell.view

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.java.Jdk
import dev.sluide.jshell.EvalEvent
import dev.sluide.jshell.FakeAgent
import dev.sluide.jshell.JShellJdk
import dev.sluide.jshell.JShellSession
import dev.sluide.jshell.SessionEvent
import dev.sluide.jshell.ui.RunOutput
import java.nio.file.Path
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.fail

/**
 * A [JShellRuns] on fake agents. The test thread is the FX thread: what the session reports is queued and runs in
 * [pumpUntil]. The agents are taken in order, one per started session; a start with none left fails.
 */
internal class RunsFixture(
    inlineWorker: Boolean = false,
) {
    val jdk25 = Jdk(Path.of("/jdk-25"), "25.0.5", 25)
    val project = JShellJdk(jdk25, "the project's JDK", null)
    var resolved: JShellJdk? = project

    val agents = LinkedBlockingQueue<Any>()
    var starts = 0
        private set

    /** What the output got, in order: `note: …`, `start: <text>`, `event: …`, `late: …`. */
    val log = mutableListOf<String>()

    /** How often [JShellRuns] said that its states changed. */
    var changes = 0

    /** The homes of the JDKs the sessions were started on, in order. */
    val startedOn = mutableListOf<Path>()

    private val queued = LinkedBlockingQueue<() -> Unit>()
    private val worker: Executor = if (inlineWorker) Executor { it.run() } else NamedThreads.single("runs-test-worker")

    val runs: JShellRuns =
        JShellRuns(
            object : RunOutput {
                override fun startEvaluation(text: String) {
                    log += "start: $text"
                }

                override fun accept(event: EvalEvent) {
                    log += "event: $event"
                }

                override fun lateOutput(
                    text: String,
                    error: Boolean,
                ) {
                    log += if (error) "late(error): $text" else "late: $text"
                }

                override fun note(text: String) {
                    log += "note: $text"
                }
            },
            { resolved },
            { jdk, onEvent -> startSession(jdk, onEvent) },
            worker,
            { queued.put(it) },
            { changes++ },
        )

    /** Queues a fake agent for the next start: a recording, or an exception to fail it. */
    fun nextAgent(agent: FakeAgent): FakeAgent = agent.also { agents.put(it) }

    fun nextStartFails(error: Exception) = agents.put(error)

    private fun startSession(
        jdk: Jdk,
        onEvent: (SessionEvent) -> Unit,
    ): JShellSession {
        starts++
        startedOn.add(jdk.home)
        return when (val next = agents.poll()) {
            is FakeAgent -> JShellSession.start(jdk, onEvent, { next }, 10_000, 2_000)
            is Exception -> throw next
            else -> fail("no agent queued for start $starts")
        }
    }

    /** Runs what the sessions queued for the FX thread until [done] holds. */
    fun pumpUntil(
        what: String,
        done: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!done()) {
            val task = queued.poll(50, TimeUnit.MILLISECONDS)
            task?.invoke()
            if (System.nanoTime() > deadline) fail("$what did not happen within 10 s; log: $log")
        }
    }

    /** Waits until something is queued for the FX thread, without running it. */
    fun waitQueued() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (queued.isEmpty()) {
            if (System.nanoTime() > deadline) fail("nothing was queued within 10 s; log: $log")
            Thread.onSpinWait()
        }
    }

    /** Runs the queued tasks until none comes within 300 ms (a negative check has no signal to wait for). */
    fun pumpQuiet() {
        while (true) (queued.poll(300, TimeUnit.MILLISECONDS) ?: return).invoke()
    }

    fun pumpUntilIdle() = pumpUntil("the run to end") { !runs.running }
}
