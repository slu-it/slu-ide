package dev.sluide.ide.logging

import javafx.application.Platform
import java.util.concurrent.TimeUnit

private val log = System.getLogger(FxWatchdog::class.java.name)

private const val FX_THREAD = "JavaFX Application Thread"

/**
 * Logs when the JavaFX thread does not answer (ADR 0060): a daemon thread posts a ping to it every [interval]; a ping
 * that is not handled within [threshold] gets one WARNING with the stack trace of the JavaFX thread (what blocks it),
 * and an INFO line once the thread answers again. The timing decisions are in [check], which tests call step by step.
 */
internal class FxWatchdog(
    private val dispatch: (Runnable) -> Unit = Platform::runLater,
    private val clock: () -> Long = System::nanoTime,
    private val stack: () -> Array<StackTraceElement>? = ::fxThreadStack,
    private val interval: Long = 250,
    private val threshold: Long = 1_000,
    private val sink: (System.Logger.Level, String, Throwable?) -> Unit = { level, message, error ->
        if (error == null) log.log(level, message) else log.log(level, message, error)
    },
) {
    private val lock = Any()

    /** When the unanswered ping was posted ([clock] nanoseconds), or `null` if none is outstanding. */
    private var pingSentAt: Long? = null
    private var reported = false

    @Volatile private var thread: Thread? = null

    fun start() {
        if (thread != null) return
        thread =
            Thread.ofPlatform().name("sluide-fx-watchdog").daemon().start {
                try {
                    var failed = false
                    while (true) {
                        try {
                            check()
                        } catch (e: RuntimeException) {
                            // Keep watching; the first failure is logged, not one every 250 ms.
                            if (!failed) {
                                failed = true
                                try {
                                    sink(System.Logger.Level.ERROR, "The JavaFX watchdog failed in one step", e)
                                } catch (_: RuntimeException) {
                                    // Nothing left to do.
                                }
                            }
                        }
                        Thread.sleep(interval)
                    }
                } catch (_: InterruptedException) {
                    // stop()
                }
            }
    }

    fun stop() {
        thread?.let {
            it.interrupt()
            it.join(1_000)
        }
        thread = null
    }

    /** One step of the loop: posts a ping if none is outstanding, reports one that is overdue (once). */
    fun check() {
        val now = clock()
        val sent =
            synchronized(lock) {
                val sentAt = pingSentAt
                if (sentAt == null) {
                    pingSentAt = now
                    null
                } else if (reported || millis(now - sentAt) < threshold) {
                    return
                } else {
                    sentAt
                }
            }
        if (sent == null) {
            try {
                dispatch(Runnable { answered(sentAt = now) })
            } catch (e: RuntimeException) {
                // No ping went out, so none is outstanding: it must not be reported as a stall.
                synchronized(lock) { if (pingSentAt == now) pingSentAt = null }
                throw e
            }
            return
        }
        val trace = stack()?.takeIf { it.isNotEmpty() } ?: return
        synchronized(lock) {
            if (pingSentAt != sent || reported) return
            reported = true
        }
        val stall = Throwable("$FX_THREAD stack").apply { stackTrace = trace }
        sink(System.Logger.Level.WARNING, "JavaFX thread blocked for ${millis(now - sent)} ms", stall)
    }

    /** The JavaFX thread handled the ping posted at [sentAt]. */
    private fun answered(sentAt: Long) {
        val now = clock()
        val wasReported =
            synchronized(lock) {
                if (pingSentAt != sentAt) return
                pingSentAt = null
                reported.also { reported = false }
            }
        if (wasReported) {
            sink(
                System.Logger.Level.INFO,
                "JavaFX thread responsive again after ${millis(now - sentAt)} ms blocked",
                null,
            )
        }
    }

    private fun millis(nanos: Long) = TimeUnit.NANOSECONDS.toMillis(nanos)
}

private fun fxThreadStack(): Array<StackTraceElement>? =
    Thread
        .getAllStackTraces()
        .entries
        .firstOrNull { it.key.name == FX_THREAD }
        ?.value
