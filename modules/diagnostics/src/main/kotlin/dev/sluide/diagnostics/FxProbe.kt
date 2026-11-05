package dev.sluide.diagnostics

import javafx.application.Platform
import javafx.scene.Scene
import javafx.stage.Stage
import javafx.stage.Window
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** Layout pulses of one window while its listener was installed. */
internal data class WindowPulses(
    val label: String,
    val pulses: Long,
    val nanos: Long,
)

/** How the JavaFX thread answered over a capture's window. */
internal data class FxResponsiveness(
    /** Why nothing was measured (JavaFX not running), else null. */
    val unavailable: String? = null,
    /** How long each answered ping waited for the JavaFX thread. */
    val latencies: List<Long> = emptyList(),
    val unanswered: Int = 0,
    /** How long the oldest unanswered ping has waited at the end, 0 if none. */
    val oldestUnansweredNanos: Long = 0,
    /** How long after [FxProbe.begin] the pulse listeners were installed; null if never in the window. */
    val installedAfterNanos: Long? = null,
    val windows: List<WindowPulses> = emptyList(),
)

/**
 * Measures the JavaFX thread during a capture: [begin] installs pulse counters, [ping] posts a ping whose wait is
 * recorded, [end] removes the counters and tells what was measured. Called from the sampling thread, in that order;
 * never waits for the JavaFX thread.
 */
internal interface FxProbe {
    fun begin()

    fun ping()

    fun end(): FxResponsiveness
}

/** The [FxProbe] of the running app: [Platform.runLater] pings and `Scene.addPostLayoutPulseListener` counters. */
internal class JavaFxProbe(
    private val dispatch: (Runnable) -> Unit = Platform::runLater,
    private val clock: () -> Long = System::nanoTime,
) : FxProbe {
    private class Counter(
        val label: String,
        val scene: Scene,
        val since: Long,
    ) {
        val pulses = AtomicLong()
        val listener = Runnable { pulses.incrementAndGet() }
    }

    @Volatile private var unavailable: String? = null

    @Volatile private var ended = false
    private var begunAt = 0L

    @Volatile private var installedAt: Long? = null
    private val counters = CopyOnWriteArrayList<Counter>()
    private val latencies = CopyOnWriteArrayList<Long>()

    /** The send times of pings not answered yet. */
    private val outstanding = ConcurrentHashMap.newKeySet<Long>()

    override fun begin() {
        begunAt = clock()
        post { install() }
    }

    override fun ping() {
        if (unavailable != null || ended) return
        val sentAt = clock()
        outstanding += sentAt
        post {
            if (!ended && outstanding.remove(sentAt)) latencies += clock() - sentAt
        }
    }

    override fun end(): FxResponsiveness {
        ended = true
        val now = clock()
        unavailable?.let { return FxResponsiveness(unavailable = it) }
        val windows = counters.map { WindowPulses(it.label, it.pulses.get(), now - it.since) }
        // Removed whenever the JavaFX thread gets to it; a late install sees `ended` and installs nothing.
        post { removeListeners() }
        val waiting = outstanding.toList()
        return FxResponsiveness(
            latencies = latencies.toList(),
            unanswered = waiting.size,
            oldestUnansweredNanos = waiting.minOrNull()?.let { now - it } ?: 0,
            installedAfterNanos = installedAt?.let { it - begunAt },
            windows = windows,
        )
    }

    /** The pulses counted so far, over all windows; also after [end], which removes the counters. */
    internal fun countedPulses(): Long = counters.sumOf { it.pulses.get() }

    /** FX thread: a pulse counter on the scene of every showing window. */
    private fun install() {
        if (ended) return
        val now = clock()
        Window.getWindows().filter { it.isShowing && it.scene != null }.forEachIndexed { index, window ->
            val title = (window as? Stage)?.title?.takeIf { it.isNotBlank() }
            val counter = Counter(title ?: "Window ${index + 1}", window.scene, now)
            window.scene.addPostLayoutPulseListener(counter.listener)
            counters += counter
        }
        installedAt = now
    }

    private fun removeListeners() {
        for (counter in counters) counter.scene.removePostLayoutPulseListener(counter.listener)
    }

    /** Runs [action] on the JavaFX thread; without a running toolkit the probe becomes unavailable. */
    private fun post(action: () -> Unit) {
        if (unavailable != null) return
        try {
            dispatch(Runnable(action))
        } catch (_: IllegalStateException) {
            unavailable = "JavaFX is not running"
        }
    }
}
