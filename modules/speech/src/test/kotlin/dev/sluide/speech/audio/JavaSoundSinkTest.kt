package dev.sluide.speech.audio

import dev.sluide.speech.engine.SpeechException
import dev.sluide.speech.session.WAIT_SECONDS
import dev.sluide.speech.session.eventually
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.abort
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.sound.sampled.LineUnavailableException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Timeout(60)
class JavaSoundSinkTest {
    private var line: FakeLine? = null
    private val sink = JavaSoundSink { format, bytes -> FakeLine(format, bytes).also { line = it } }
    private val callers = mutableListOf<Thread>()

    @AfterEach
    fun tearDown() {
        sink.close()
        callers.forEach { it.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS)) }
    }

    /** Opens the sink at [FAKE_RATE]: the fake line holds 0.3 s, [CAPACITY] frames. */
    private fun openFake(): FakeLine {
        sink.open(FAKE_RATE)
        return requireNotNull(line)
    }

    private fun inBackground(task: () -> Unit) =
        Thread(task)
            .apply {
                isDaemon = true
            }.also { callers += it }
            .apply { start() }

    /** Waits until [thread] waits: in the sink, as nothing here blocks the line yet. */
    private fun awaitWaiting(thread: Thread) = eventually("$thread to wait") { thread.state == Thread.State.WAITING }

    private fun awaitEnded(thread: Thread) {
        thread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
        assertFalse(thread.isAlive, "still running")
    }

    @Test
    fun `a write blocks while paused, also when the line returns early, and goes on when started`() {
        val fake = openFake()
        val writer = inBackground { sink.write(FloatArray(2 * CAPACITY)) }
        awaitWaiting(writer)
        // The stopped line got what fits: its whole buffer, CAPACITY frames of two bytes.
        assertEquals(2 * CAPACITY, fake.bufferedBytes)

        sink.start()
        eventually("the line to block the write") { fake.blocked == 1 }
        // The line returns the write early when stopped: the sink waits for the start instead.
        sink.pause()
        eventually("the sink to hold the write") { fake.blocked == 0 && writer.state == Thread.State.WAITING }

        sink.start()
        eventually("the write to end") {
            fake.play(CAPACITY / 4)
            !writer.isAlive
        }
        eventually("all frames played") {
            fake.play(CAPACITY)
            sink.playedFrames == 2L * CAPACITY
        }
    }

    @Test
    fun `flush and close wake a blocked write`() {
        openFake()
        val flushed = inBackground { sink.write(FloatArray(2 * CAPACITY)) }
        awaitWaiting(flushed)

        sink.flush()

        awaitEnded(flushed)
        assertEquals(0, sink.playedFrames)
        val closed = inBackground { sink.write(FloatArray(2 * CAPACITY)) }
        awaitWaiting(closed)

        sink.close()

        awaitEnded(closed)
    }

    @Test
    fun `close wakes a blocked drain`() {
        val fake = openFake()
        sink.start()
        sink.write(FloatArray(CAPACITY / 2))
        val drainer = inBackground { sink.drain() }
        eventually("the drain to block") { fake.blocked == 1 }

        sink.close()

        awaitEnded(drainer)
    }

    @Test
    fun `a drain waits through a pause and ends once the audio has played`() {
        val fake = openFake()
        sink.start()
        sink.write(FloatArray(CAPACITY / 2))
        val drainer = inBackground { sink.drain() }
        eventually("the drain to block") { fake.blocked == 1 }

        // The line ends its drain early when stopped.
        sink.pause()
        eventually("the sink to hold the drain") { fake.blocked == 0 && drainer.state == Thread.State.WAITING }
        sink.start()
        eventually("the drain to block again") { fake.blocked == 1 }
        fake.play(CAPACITY / 2)

        awaitEnded(drainer)
        assertEquals(CAPACITY / 2L, sink.playedFrames)
    }

    @Test
    fun `a pause and start during a drain make it drain again`() {
        val fake = openFake()
        sink.start()
        sink.write(FloatArray(CAPACITY / 2))
        // The line's drain is cut short by a pause, and the sink is started again before the drain returns.
        fake.onDrain = {
            sink.pause()
            sink.start()
        }

        val drainer = inBackground { sink.drain() }

        eventually("the drain to block again") { fake.blocked == 1 }
        assertTrue(drainer.isAlive)
        fake.play(CAPACITY / 2)
        awaitEnded(drainer)
    }

    @Test
    fun `played frames count from the last flush`() {
        val fake = openFake()
        sink.start()
        sink.write(FloatArray(200))
        fake.play(150)
        assertEquals(150, sink.playedFrames)

        sink.flush()

        assertEquals(0, sink.playedFrames)
        sink.write(FloatArray(50))
        fake.play(80)
        assertEquals(50, sink.playedFrames)
    }

    @Test
    fun `a line that cannot open is no audio output`() {
        val failing = JavaSoundSink { _, _ -> throw LineUnavailableException("device busy") }

        val error = assertFailsWith<SpeechException> { failing.open(FAKE_RATE) }

        assertEquals("No audio output: device busy", error.message)
    }

    @Test
    fun `close does not wait for an open in progress, which then closes its line`() {
        val opening = CountDownLatch(1)
        val release = CountDownLatch(1)
        var opened: FakeLine? = null
        val slow =
            JavaSoundSink { format, bytes ->
                opening.countDown()
                release.await()
                FakeLine(format, bytes).also { opened = it }
            }
        val opener = inBackground { slow.open(FAKE_RATE) }
        assertTrue(opening.await(WAIT_SECONDS, TimeUnit.SECONDS))

        // Would hang (and time out) if open held the sink's lock while opening.
        slow.close()

        release.countDown()
        awaitEnded(opener)
        assertFalse(requireNotNull(opened).isOpen)
        assertEquals(0, slow.playedFrames)
    }

    @Test
    fun `writes and drains after close return at once`() {
        openFake()
        sink.close()

        sink.write(FloatArray(2 * CAPACITY))
        sink.drain()
        sink.flush()

        assertEquals(0, sink.playedFrames)
    }

    @Test
    fun `plays silence to its end on the default output`() {
        // Opening a line needs an audio device: the test tasks point ALSA at a null device where there is no sound
        // card (docs/testing.md, "Media playback"); where even that is missing, this is skipped.
        val real = JavaSoundSink()
        try {
            real.open(RATE)
        } catch (e: SpeechException) {
            abort<Unit>("no audio output here: ${e.message}")
        }
        real.use {
            assertEquals(0, it.playedFrames)

            it.start()
            it.write(FloatArray(RATE / 5))
            it.drain()

            assertEquals(RATE / 5L, it.playedFrames)
        }
        assertEquals(0, real.playedFrames)
    }

    private companion object {
        const val RATE = 24_000
        const val FAKE_RATE = 1000

        /** The fake line's buffer at [FAKE_RATE]: 0.3 s. */
        const val CAPACITY = 300
    }
}
