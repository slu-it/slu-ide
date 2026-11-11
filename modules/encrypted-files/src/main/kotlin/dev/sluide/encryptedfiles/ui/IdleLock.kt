package dev.sluide.encryptedfiles.ui

import javafx.animation.PauseTransition
import java.time.Duration
import javafx.util.Duration as FxDuration

/** A task scheduled to run once; [cancel] stops it if it has not run. */
internal fun interface Pending {
    fun cancel()
}

/** Runs [task] once after [delay] on the thread that uses the [IdleLock] (the FX thread). */
internal typealias IdleScheduler = (delay: Duration, task: Runnable) -> Pending

private val fxScheduler: IdleScheduler = { delay, task ->
    val pause = PauseTransition(FxDuration.millis(delay.toMillis().toDouble()))
    pause.setOnFinished { task.run() }
    pause.play()
    Pending { pause.stop() }
}

/**
 * Fires [onIdle] when [minutes] (read at every decision, 0 = off) of [unit] passed without [activity] while it
 * [start]ed and was not [stop]ped, without JavaFX (ADR 0084). After [onIdle] it counts a full period again as long as it
 * was not stopped, so a lock that did not happen is tried again one period later. Not thread-safe: the FX thread.
 *
 * Idle time is wall-clock time from [clock] (milliseconds), so time the computer slept counts: the [scheduler]'s
 * timers stop during sleep, so they only tick, at most [tick] apart, and each tick, [activity] and [check] (e.g. when
 * the window gets the focus back) fire at once when the idle time already reached the period.
 */
internal class IdleLock(
    private val minutes: () -> Int,
    private val onIdle: () -> Unit,
    private val unit: Duration = Duration.ofMinutes(1),
    private val clock: () -> Long = System::currentTimeMillis,
    private val scheduler: IdleScheduler = fxScheduler,
    private val tick: Duration = Duration.ofSeconds(15),
) {
    private var running = false
    private var lastActivity = 0L
    private var pending: Pending? = null

    /** Whether a timer is waiting. */
    val armed: Boolean get() = pending != null

    /** Starts counting from now. */
    fun start() {
        running = true
        lastActivity = clock()
        arm()
    }

    /** Stops counting; [activity] does nothing until the next [start]. */
    fun stop() {
        running = false
        disarm()
    }

    /**
     * A key press, mouse press or scroll in the tab: counts from now, unless the idle time already passed (the
     * computer slept before the tick came), which fires instead.
     */
    fun activity() {
        if (!running) return
        if (idleReached()) {
            fire()
        } else {
            lastActivity = clock()
        }
    }

    /** Fires if the idle time passed, e.g. when the tab's window gets the focus back after the computer slept. */
    fun check() {
        if (running && idleReached()) fire()
    }

    /** The setting changed: counts again by the new value, from the last activity. */
    fun settingChanged() {
        if (!running) return
        disarm()
        arm()
    }

    private fun period(): Duration? = minutes().takeIf { it > 0 }?.let { unit.multipliedBy(it.toLong()) }

    /** The wall-clock time since the last activity; a clock set back counts from its new time. */
    private fun idle(): Duration {
        val now = clock()
        if (now < lastActivity) lastActivity = now
        return Duration.ofMillis(now - lastActivity)
    }

    private fun idleReached(): Boolean = period()?.let { idle() >= it } == true

    private fun arm() {
        val period = period() ?: return
        val remaining = period.minus(idle()).let { if (it.isNegative) Duration.ZERO else it }
        pending = scheduler(minOf(remaining, tick)) { onTick() }
    }

    private fun disarm() {
        pending?.cancel()
        pending = null
    }

    private fun onTick() {
        pending = null
        if (!running) return
        if (idleReached()) fire() else arm()
    }

    private fun fire() {
        disarm()
        onIdle()
        if (running && pending == null) {
            lastActivity = clock()
            arm()
        }
    }
}
