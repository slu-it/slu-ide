package dev.sluide.plantuml

import dev.sluide.plantuml.engine.DotProcesses
import dev.sluide.plantuml.engine.Drawing
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The diagrams of one PlantUML source, in source order. */
data class Rendering(
    /** One per page of every `@start…@end…` block (`newpage` starts a page); empty without a block. */
    val diagrams: List<Diagram>,
    /**
     * Graphviz was not found and at least one diagram needed a layout engine, so PlantUML's built-in one (Smetana)
     * drew it: the view says so above the diagrams.
     */
    val builtInLayout: Boolean,
)

/** One page of a block: drawn, or why not. */
sealed interface Diagram {
    /** The SVG in PlantUML's light colours and in its dark mode, from the same parsed diagram. */
    data class Drawn(
        val lightSvg: String,
        val darkSvg: String,
    ) : Diagram

    /** PlantUML's message, and the 1-based line in the caller's numbering, `null` when no line of the source fits. */
    data class Failed(
        val message: String,
        val line: Int?,
    ) : Diagram
}

/**
 * Draws PlantUML sources with the bundled library, in-process (ADR 0193).
 *
 * Thread rules: [render] may be called on any thread and never blocks. Renders run one at a time, in call order, on
 * one render thread (PlantUML keeps global state); the thread ends when nothing is queued and the next render starts
 * a new one. A render that takes longer than [timeout] is given up: the `dot` processes this JVM started are killed,
 * its thread is abandoned (interrupts do not stop PlantUML, a hung `dot` blocks forever) and starts no further export,
 * the renders queued behind it continue on a fresh thread, and its future completes with one [Diagram.Failed]
 * ("Drawing took longer than …"). Whatever the abandoned thread returns later, often cut short, is never delivered:
 * the abandoned mark, set under the lock, decides which side completes the future. The futures complete on the
 * render thread or on the watchdog's thread: callers that touch the UI hop to their own thread.
 */
class PlantUml internal constructor(
    private val locator: GraphvizLocator,
    private val timeout: Duration,
    private val threads: ThreadFactory,
    private val draw: (source: String, firstLine: Int, dot: Path?, givenUp: () -> Boolean) -> Rendering,
    private val dotKiller: (dot: Path) -> DotProcesses.Kill = DotProcesses::killer,
    private val watchdog: ScheduledExecutorService = sharedWatchdog,
    /** For tests: runs on the watchdog's thread between its locked step and its delivery of the timeout. */
    private val beforeTimeoutDelivered: (() -> Unit)? = null,
) {
    // A lambda, not `Drawing::draw`: that reference loads Drawing, and with it PlantUML, on the caller's thread (the FX
    // thread through `shared`). PlantUML's class initialisers start AWT and can fail; they run on the render thread.
    constructor(locator: GraphvizLocator = GraphvizLocator(), timeout: Duration = 20.seconds) :
        this(locator, timeout, renderThreads, { source, firstLine, dot, givenUp ->
            Drawing.draw(source, firstLine, dot, givenUp)
        })

    /**
     * Draws every block of [source]; [firstLine] is the caller's number of the source's first line, so that errors
     * point into the caller's text. Thoreau's callers all pass 1 and shift error lines themselves (a fence's drawing
     * then does not depend on where the fence is, ADR 0193). Any thread. Never completes exceptionally: problems
     * are [Diagram.Failed] diagrams.
     */
    fun render(
        source: String,
        firstLine: Int = 1,
    ): CompletableFuture<Rendering> {
        val job = Job(source, firstLine)
        synchronized(lock) {
            queue.addLast(job)
            if (worker == null) startWorker()
        }
        return job.future
    }

    private class Job(
        val source: String,
        val firstLine: Int,
    ) {
        val future = CompletableFuture<Rendering>()
    }

    private val lock = Any()

    /** Whether PlantUML's failure to start was logged already. */
    private val startFailureLogged = AtomicBoolean()

    /** Renders not started yet, in call order. Guarded by [lock], as are [worker] and the workers' fields. */
    private val queue = ArrayDeque<Job>()

    /** The worker that takes the next job; `null` when none runs. */
    private var worker: Worker? = null

    /** Called with [lock] held. */
    private fun startWorker() {
        val next = Worker()
        worker = next
        threads.newThread(next).start()
    }

    private inner class Worker : Runnable {
        /** The job this worker is drawing; `null` between jobs. */
        var current: Job? = null

        /** Set by the watchdog: this worker's thread is given up and takes no more jobs. */
        var abandoned = false

        override fun run() {
            // Before the first job's timer: the login shell lookup can take seconds, once per app run.
            val dot = locator.locate()
            while (true) {
                val job =
                    synchronized(lock) {
                        if (abandoned) return
                        val next = queue.removeFirstOrNull()
                        if (next == null) {
                            worker = null
                            return
                        }
                        current = next
                        next
                    }
                val timer =
                    watchdog.schedule({ timedOut(this, job, dot) }, timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                val rendering =
                    try {
                        draw(job.source, job.firstLine, dot) { synchronized(lock) { abandoned } }
                    } catch (failure: LinkageError) {
                        // PlantUML's classes failed to initialise (AWT on a headless JDK): the first time with the
                        // cause, then as NoClassDefFoundError on every render. Logged once.
                        if (!startFailureLogged.getAndSet(true)) {
                            log.log(System.Logger.Level.WARNING, "PlantUML cannot start", failure)
                        }
                        Rendering(listOf(Diagram.Failed("PlantUML cannot start: $failure", null)), false)
                    } catch (failure: Throwable) {
                        log.log(System.Logger.Level.WARNING, "PlantUML failed", failure)
                        Rendering(listOf(Diagram.Failed("PlantUML failed: $failure", null)), false)
                    }
                // Else the timer would keep the job, its source and its SVGs for the whole timeout.
                timer.cancel(false)
                // Exactly one side delivers: after a timeout the watchdog does, and this late, perhaps cut-short
                // result is dropped.
                val deliver =
                    synchronized(lock) {
                        current = null
                        !abandoned
                    }
                if (deliver) job.future.complete(rendering)
            }
        }
    }

    /** On the watchdog's thread, [timeout] after [job] started; does nothing if the job finished in time. */
    private fun timedOut(
        worker: Worker,
        job: Job,
        dot: Path?,
    ) {
        // The process table is read before the lock, so that render() never waits for it.
        val kill = dot?.let(dotKiller)
        synchronized(lock) {
            if (worker.current !== job) return
            worker.abandoned = true
            this.worker = null
            // A dot that keeps its output open blocks the abandoned thread forever. Killed before a fresh worker can
            // start a dot of its own; the abandoned draw starts no further export once it sees it was given up.
            kill?.now()
            if (queue.isNotEmpty()) startWorker()
        }
        // Outside the lock: completing runs the callers' callbacks on this thread.
        beforeTimeoutDelivered?.invoke()
        job.future.complete(
            Rendering(listOf(Diagram.Failed("Drawing took longer than ${timeout.inWholeSeconds} s", null)), false),
        )
    }

    companion object {
        /** The app-wide engine; created on first use. */
        val shared: PlantUml by lazy { PlantUml() }

        private val log = System.getLogger(PlantUml::class.java.name)

        internal val renderThreads = ThreadFactory { Thread(it, "PlantUML render").apply { isDaemon = true } }

        /** One daemon thread for the timers of all engines. */
        private val sharedWatchdog = watchdogExecutor()

        /** A scheduler for the timers: one daemon thread. */
        internal fun watchdogExecutor() =
            ScheduledThreadPoolExecutor(1) { Thread(it, "PlantUML watchdog").apply { isDaemon = true } }.apply {
                removeOnCancelPolicy = true
            }
    }
}
