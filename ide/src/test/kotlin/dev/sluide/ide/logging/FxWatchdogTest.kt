package dev.sluide.ide.logging

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FxWatchdogTest {
    private var nowMillis = 0L
    private val queue = ArrayDeque<Runnable>()
    private val lines = mutableListOf<Triple<System.Logger.Level, String, Throwable?>>()
    private var stack: Array<StackTraceElement>? = Thread.currentThread().stackTrace

    private val watchdog =
        FxWatchdog(
            dispatch = { queue.addLast(it) },
            clock = { TimeUnit.MILLISECONDS.toNanos(nowMillis) },
            stack = { stack },
            sink = { level, message, error -> lines += Triple(level, message, error) },
        )

    private fun advance(millis: Long) {
        nowMillis += millis
        watchdog.check()
    }

    private fun handleQueued() {
        while (queue.isNotEmpty()) queue.removeFirst().run()
    }

    @Test
    fun `nothing is logged while pings are handled`() {
        repeat(20) {
            advance(250)
            handleQueued()
        }
        assertTrue(lines.isEmpty())
    }

    @Test
    fun `a stall is reported once with the stack, and recovery is logged`() {
        advance(0)
        repeat(10) { advance(250) }

        val warnings = lines.filter { it.first == System.Logger.Level.WARNING }
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().second.startsWith("JavaFX thread blocked for 1000 ms"), warnings.single().second)
        assertTrue(
            warnings
                .single()
                .third!!
                .stackTrace
                .isNotEmpty(),
        )

        advance(250)
        handleQueued()
        val info = lines.single { it.first == System.Logger.Level.INFO }
        assertTrue(info.second.startsWith("JavaFX thread responsive again after 2750 ms"), info.second)

        // A new stall is reported anew.
        advance(250)
        repeat(5) { advance(250) }
        assertEquals(2, lines.count { it.first == System.Logger.Level.WARNING })
    }

    @Test
    fun `a stall without an FX thread is not reported`() {
        stack = null
        advance(0)
        repeat(10) { advance(250) }
        handleQueued()
        assertTrue(lines.isEmpty())
    }

    @Test
    fun `a dispatch that throws gives one error and no false stall`() {
        val logged = java.util.concurrent.CopyOnWriteArrayList<Pair<System.Logger.Level, String>>()
        val failing =
            FxWatchdog(
                dispatch = { throw IllegalStateException("toolkit down") },
                interval = 10,
                threshold = 100,
                sink = { level, message, _ -> logged += level to message },
            )
        // Each step throws and posts again, without a stall report.
        repeat(2) { assertFailsWith<IllegalStateException> { failing.check() } }
        assertTrue(logged.isEmpty())

        failing.start()
        val deadline = System.nanoTime() + 5_000_000_000L
        while (logged.none { it.first == System.Logger.Level.ERROR } && System.nanoTime() < deadline) Thread.sleep(10)
        // Long enough for steps past the threshold (100 ms), which would report a stall or a second error.
        Thread.sleep(300)
        failing.stop()

        assertEquals(1, logged.count { it.first == System.Logger.Level.ERROR })
        assertTrue(logged.none { it.first == System.Logger.Level.WARNING })
    }
}
