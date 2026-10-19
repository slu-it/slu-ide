package dev.sluide.ide.logging

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
