package dev.sluide.diagnostics

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.backgroundtasks.NamedThreads
import javafx.application.Platform
import javafx.beans.property.ReadOnlyBooleanProperty
import javafx.beans.property.ReadOnlyBooleanWrapper
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

private val log = System.getLogger(Diagnostics::class.java.name)

/** The name of a capture's row in the window's background tasks. */
internal const val TASK_NAME = "Capturing diagnostics…"

/** Lines of the log file at the end of a report. */
private const val LOG_LINES = 200

/** What [Diagnostics.captureNow] wrote: `report.txt`, and whether the sampling was cancelled. */
internal data class Captured(
    val report: Path,
    val cancelled: Boolean,
)

/**
 * Captures what burns CPU or slows the UI from inside the running app (ADR 0127): an always-on JFR ring buffer
 * ([startRecorder]) and, on request, a sampled report of the threads, CPU, GC and the JavaFX thread ([capture]),
 * written to a folder of its own in [folder]. One capture runs at a time, app-wide.
 *
 * Seams with the real values by default: [window] is how long a capture samples, every [interval] a dump of all
 * threads, every [pingInterval] a ping to the JavaFX thread; [keep] capture folders are kept. JFR keeps its chunks in
 * `.jfr` in [folder]; [threadDump] writes `threads.txt`.
 */
@Component
class Diagnostics internal constructor(
    /** Where captures are written, each into a folder named by its time: `~/.sluide/diagnostics`. */
    val folder: Path,
    private val recorder: FlightRecorderBuffer = FlightRecorderBuffer(folder.resolve(".jfr")),
    private val probe: () -> FxProbe = { JavaFxProbe() },
    private val source: ThreadSource = ManagementThreadSource(),
    private val window: Duration = Duration.ofSeconds(10),
    private val interval: Duration = Duration.ofMillis(250),
    private val pingInterval: Duration = Duration.ofMillis(100),
    private val keep: Int = 10,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val dispatch: (Runnable) -> Unit = Platform::runLater,
    private val executor: Executor = NamedThreads.single("sluide-diagnostics"),
    private val threadDump: (Path) -> Unit = ::writeThreadDump,
) : DisposableBean {
    @Autowired
    constructor() : this(Path.of(System.getProperty("user.home"), ".sluide", "diagnostics"))

    private val capturingWrapper = ReadOnlyBooleanWrapper(false)

    /** Whether a capture runs; read and changed on the FX thread only. */
    val capturing: ReadOnlyBooleanProperty = capturingWrapper.readOnlyProperty

    /** Ends the sampling of the running capture early; null when none runs. */
    @Volatile private var cancel: CountDownLatch? = null

    /** Starts the JFR ring buffer; any thread, returns at once (JFR takes a few hundred ms to start). */
    fun startRecorder() {
        executor.execute { recorder.start() }
    }

    /**
     * Captures diagnostics; FX thread. No-op while capturing. Shows a row in [tasks] named "Capturing diagnostics…"
     * whose cancel stops sampling early; [done] gets `report.txt`, [failed] the error, both on the FX thread. [info]
     * heads the report (version, Java, OS, paths), the last lines of [logFile] end it.
     */
    fun capture(
        info: List<Pair<String, String>>,
        logFile: Path?,
        tasks: BackgroundTasks,
        done: (report: Path) -> Unit,
        failed: (Throwable) -> Unit,
    ) {
        if (capturingWrapper.get()) return
        capturingWrapper.set(true)
        val stop = CountDownLatch(1)
        cancel = stop
        val task = tasks.start(TASK_NAME) { stop.countDown() }
        val end = { outcome: () -> Unit ->
            cancel = null
            onFxThread {
                capturingWrapper.set(false)
                outcome()
            }
        }
        try {
            executor.execute {
                try {
                    val captured = captureNow(info, logFile, stop)
                    if (captured.cancelled) task.close("cancelled") else task.close()
                    end { done(captured.report) }
                } catch (t: Throwable) {
                    task.failed(t)
                    end { failed(t) }
                }
            }
        } catch (e: RuntimeException) {
            task.failed(e)
            cancel = null
            capturingWrapper.set(false)
            failed(e)
        }
    }

    /** Stops the recorder and cancels a running capture, which still writes what it sampled. */
    fun stop() {
        cancel?.countDown()
        recorder.stop()
    }

    /** Spring closes the app: [stop]. */
    override fun destroy() = stop()

    private fun onFxThread(action: () -> Unit) {
        try {
            dispatch(Runnable(action))
        } catch (e: IllegalStateException) {
            // The toolkit has exited: nobody is left to tell.
            log.log(System.Logger.Level.DEBUG, "Diagnostics result dropped, JavaFX is not running", e)
        }
    }

    /**
     * One capture on the calling thread: the folder, the sampling until [window] has passed or [stop] is counted
     * down, `threads.txt`, the JFR dump (so it holds the window and the time before it), `report.txt`, then the
     * retention. Throws on failure, keeping what was written.
     */
    internal fun captureNow(
        info: List<Pair<String, String>>,
        logFile: Path?,
        stop: CountDownLatch,
    ): Captured {
        val started = System.nanoTime()
        val time = ZonedDateTime.now(clock)
        val target = createCaptureFolder(folder, time)

        val fx = probe()
        val sampler = ThreadSampler(source, Thread.currentThread().threadId())
        fx.begin()
        sampler.begin()
        val cancelledAfter = sample(sampler, fx, stop)
        val responsiveness = fx.end()
        val sampling = sampler.end()

        val threadsMissing = writeThreads(target.resolve("threads.txt"))
        val jfr = recorder.dump(target.resolve("recording.jfr"))
        val jfrMissing = if (jfr == null) recorder.unavailable ?: "writing it failed, see the log" else null

        val report =
            Report(
                time = time,
                folder = target,
                info = info,
                windowMillis = window.toMillis(),
                intervalMillis = interval.toMillis(),
                pingMillis = pingInterval.toMillis(),
                cancelledAfterNanos = cancelledAfter,
                jfr = jfr?.fileName?.toString(),
                jfrMissing = jfrMissing,
                jfrMaxAge = recorder.maxAge,
                threads = if (threadsMissing == null) "threads.txt" else null,
                threadsMissing = threadsMissing,
                sampling = sampling,
                fx = responsiveness,
                log = logTail(logFile),
            )
        val file = target.resolve("report.txt")
        writeAtomically(file, reportText(report))
        applyRetention(folder, keep)
        log.log(
            System.Logger.Level.INFO,
            "Diagnostics captured to $target in " +
                String.format(Locale.ROOT, "%.1f", (System.nanoTime() - started) / 1e9) + " s",
        )
        return Captured(file, cancelledAfter != null)
    }

    /** Writes all threads, virtual ones too, to [target]; returns why not if that failed (WARNING), else null. */
    private fun writeThreads(target: Path): String? =
        try {
            threadDump(target)
            null
        } catch (e: Exception) {
            log.log(System.Logger.Level.WARNING, "The thread dump could not be written to $target", e)
            e.message ?: e.javaClass.simpleName
        }

    /** Samples until [window] has passed; returns how long it sampled if [stop] ended it early, else null. */
    private fun sample(
        sampler: ThreadSampler,
        fx: FxProbe,
        stop: CountDownLatch,
    ): Long? {
        val begin = System.nanoTime()
        val deadline = begin + window.toNanos()
        var nextPing = begin
        var nextSample = begin + interval.toNanos()
        while (true) {
            var now = System.nanoTime()
            if (stop.count == 0L) return now - begin
            if (now >= deadline) return null
            if (now >= nextPing) {
                fx.ping()
                nextPing = next(nextPing, pingInterval, now)
            }
            if (now >= nextSample) {
                sampler.sample()
                nextSample = next(nextSample, interval, now)
            }
            now = System.nanoTime()
            val wait = minOf(deadline, nextPing, nextSample) - now
            if (wait > 0) stop.await(wait, TimeUnit.NANOSECONDS)
        }
    }

    /** The time after [due] one [step] on, or a step from [now] when that has passed already (fell behind). */
    private fun next(
        due: Long,
        step: Duration,
        now: Long,
    ): Long = (due + step.toNanos()).let { if (it > now) it else now + step.toNanos() }

    private fun logTail(file: Path?): LogTail {
        if (file == null) return LogTail(null, emptyList())
        return try {
            LogTail(file, tailLines(file, LOG_LINES))
        } catch (e: IOException) {
            LogTail(file, emptyList(), e.message ?: e.javaClass.simpleName)
        }
    }
}
