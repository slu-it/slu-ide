package dev.sluide.speech.session

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.speech.engine.SpeechChunk
import dev.sluide.speech.engine.SpeechException
import dev.sluide.speech.engine.WordTiming
import dev.sluide.speech.text.Sentences
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Timeout
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

@Timeout(60)
class ReadingSessionTest {
    private val clock = AtomicLong()
    private val engine = FakeEngine(clock)
    private val sink = FakeSink()
    private val events = LinkedBlockingQueue<Pair<ReadingState, String?>>()
    private val executors = CopyOnWriteArrayList<Pair<String, ExecutorService>>()
    private val listenerThreads = CopyOnWriteArrayList<Thread>()
    private var listener: (ReadingState, String?) -> Unit = { state, message ->
        listenerThreads += Thread.currentThread()
        events.add(state to message)
    }
    private var session: ReadingSession? = null

    private val first = chunk(0, "One.")
    private val second = chunk(5, "Two.")
    private val third = chunk(10, "Three.")

    @AfterEach
    fun tearDown() {
        session?.stop()
        executors.forEach { it.second.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS) }
    }

    private fun session(
        chunks: List<SpeechChunk> = listOf(first, second, third),
        startChunk: Int = 0,
        lookaheadSeconds: Double = 120.0,
        maxBufferSeconds: Double = StartRule.MAX_BUFFER_SECONDS,
    ) = ReadingSession(
        engine,
        VOICE,
        1.0,
        chunks,
        startChunk,
        sink,
        { state, message -> listener(state, message) },
        clock::get,
        { name -> NamedThreads.single(name).also { executors += name to it } },
        lookaheadSeconds,
        maxBufferSeconds,
        Duration.ofMillis(5),
    ).also { session = it }

    private fun nextEvent(): Pair<ReadingState, String?> =
        events.poll(WAIT_SECONDS, TimeUnit.SECONDS) ?: fail("no event")

    private fun assertEvents(vararg states: ReadingState) {
        states.forEach { assertEquals(it, nextEvent().first) }
    }

    /** Waits until every thread of the session ended: no listener call can come after. */
    private fun awaitThreadsEnded() {
        executors.forEach { (name, executor) ->
            assertTrue(executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS), "$name still running")
        }
    }

    @Test
    fun `reads the start chunk split for a fast start, then the chunks after it, mapping positions back`() {
        val long =
            chunk(
                5,
                "This first sentence is rather long, and it goes on with more and more words until it is " +
                    "well past the limit of a fast start.",
            )
        val (head, tail) = Sentences.splitForFastStart(long)
        val reading = session(listOf(first, long, third), startChunk = 1)

        reading.start()

        assertEvents(ReadingState.PREPARING)
        assertEquals(head, engine.nextCall())
        assertNull(reading.position())
        engine.answer(Answer.Audio(2400, words = listOf(WordTiming(head.start, head.start + 4, 0, 1200))))
        assertEvents(ReadingState.SPEAKING)
        assertEquals(tail, engine.nextCall())
        // An underrun: the first piece has played, the next is not there yet. The reading keeps speaking, at the end
        // of the first piece.
        eventually("the first piece written") { sink.written.get() == 2400L }
        sink.playedFrames = 2400
        assertEquals(ReadingPosition(1, 5, long.end, null, null), reading.position())
        assertEquals(ReadingState.SPEAKING, reading.state)
        engine.answer(Answer.Audio(2400, words = listOf(WordTiming(tail.start, tail.start + 3, 0, 2400))))
        assertEquals(third, engine.nextCall())
        engine.answer(Answer.Audio(2400))
        eventually("all audio written") { sink.written.get() == 7200L }

        sink.playedFrames = 100
        assertEquals(ReadingPosition(1, 5, long.end, head.start, head.start + 4), reading.position())
        sink.playedFrames = 2410
        assertEquals(ReadingPosition(1, 5, long.end, tail.start, tail.start + 3), reading.position())
        sink.playedFrames = 4801
        assertEquals(ReadingPosition(2, third.start, third.end, null, null), reading.position())
        val synthesis = engine.threads.toSet()
        assertEquals(listOf(ReadingSession.SYNTHESIS_THREAD), synthesis.map { it.name })
        val player = requireNotNull(sink.openThread)
        assertEquals(ReadingSession.PLAYER_THREAD, player.name)
        assertTrue((synthesis + player).none { it.isVirtual } && (synthesis + player).all { it.isDaemon })

        sink.releaseDrain()

        assertEvents(ReadingState.FINISHED)
        assertEquals(listOf("open $RATE", "start", "drain", "close"), sink.calls)
        assertEquals(ReadingState.FINISHED, reading.state)
        assertNull(reading.position())
        awaitThreadsEnded()
        assertTrue(events.isEmpty())
        assertEquals(setOf(ReadingSession.EVENT_THREAD), listenerThreads.map { it.name }.toSet())
    }

    @Test
    fun `starts speaking only once the start rule allows`() {
        val chunks = listOf(chunk(0, "a".repeat(100)), chunk(101, "b".repeat(100)), chunk(202, "c".repeat(100)))
        val reading = session(chunks)
        reading.start()
        assertEvents(ReadingState.PREPARING)
        engine.nextCall()

        // 1 s of audio in 2 s (r = 2), 2 s still to synthesize: 2.4 s needed buffered.
        engine.answer(Answer.Audio(RATE, seconds = 2.0))
        engine.nextCall()
        // No signal for "the player decided not to start": give it time to do it wrongly.
        Thread.sleep(100)
        assertEquals(ReadingState.PREPARING, reading.state)
        assertFalse("start" in sink.calls)

        // 2 s of audio in 2.5 s (r = 1.25), 1 s left: 0.3 s needed, 2 s buffered.
        engine.answer(Answer.Audio(RATE, seconds = 0.5))

        assertEvents(ReadingState.SPEAKING)
        assertTrue("start" in sink.calls)
    }

    @Test
    fun `pause and resume while speaking pause and start the sink`() {
        val reading = session()
        reading.start()
        engine.nextCall()
        engine.answer(Answer.Audio(2400))
        assertEvents(ReadingState.PREPARING, ReadingState.SPEAKING)

        reading.pause()
        reading.pause()

        assertEvents(ReadingState.PAUSED)
        assertEquals(ReadingState.PAUSED, reading.state)
        reading.resume()
        reading.resume()
        assertEvents(ReadingState.SPEAKING)
        assertEquals(listOf("open $RATE", "start", "pause", "start"), sink.calls)
    }

    @Test
    fun `paused while preparing, the reading starts only after resume`() {
        val reading = session()
        reading.start()
        engine.nextCall()
        reading.pause()
        assertEvents(ReadingState.PREPARING, ReadingState.PAUSED)

        engine.answer(Answer.Audio(2400))
        engine.nextCall()
        // No signal for "the player did not start": give it time to do it wrongly.
        Thread.sleep(100)
        assertEquals(ReadingState.PAUSED, reading.state)
        assertFalse("start" in sink.calls)

        reading.resume()

        assertEvents(ReadingState.PREPARING, ReadingState.SPEAKING)
        assertEquals(listOf("open $RATE", "start"), sink.calls)
    }

    @Test
    fun `stop interrupts a synthesis in progress and closes the sink`() {
        val reading = session()
        reading.start()
        engine.nextCall()

        reading.stop()
        reading.stop()

        assertTrue(engine.interrupted.await(WAIT_SECONDS, TimeUnit.SECONDS))
        sink.awaitClosed()
        assertEquals(ReadingState.STOPPED, reading.state)
        awaitThreadsEnded()
        assertEquals(listOf(ReadingState.PREPARING to null, ReadingState.STOPPED to null), events.toList())
        assertEquals(1, sink.closes.get())
    }

    @Test
    fun `stop while speaking drops the audio and ends without later events`() {
        val reading = session()
        reading.start()
        engine.nextCall()
        engine.answer(Answer.Audio(2400))
        assertEvents(ReadingState.PREPARING, ReadingState.SPEAKING)
        engine.nextCall()

        reading.stop()
        reading.pause()
        reading.resume()
        reading.start()

        sink.awaitClosed()
        assertNull(reading.position())
        awaitThreadsEnded()
        assertEquals(listOf(ReadingState.STOPPED to null), events.toList())
        // stop() flushes on its caller while the player closes the sink: in either order.
        assertEquals(listOf("open $RATE", "start"), sink.calls.take(2))
        assertEquals(setOf("flush", "close"), sink.calls.drop(2).toSet())
        assertEquals(4, sink.calls.size)
    }

    @Test
    fun `stop before start ends the reading without threads`() {
        val reading = session()

        reading.stop()
        reading.start()

        assertEvents(ReadingState.STOPPED)
        sink.awaitClosed()
        assertEquals(listOf(ReadingSession.EVENT_THREAD), executors.map { it.first })
        assertTrue(engine.calls.isEmpty())
    }

    @Test
    fun `a speech exception fails the reading with its message`() {
        session().start()
        engine.nextCall()

        engine.answer(Answer.Fails(SpeechException("The model is damaged")))

        assertEvents(ReadingState.PREPARING)
        assertEquals(ReadingState.FAILED to "The model is damaged", nextEvent())
        sink.awaitClosed()
        awaitThreadsEnded()
        assertTrue(events.isEmpty())
    }

    @Test
    fun `another exception fails the reading with its class`() {
        session().start()
        engine.nextCall()
        engine.answer(Answer.Audio(2400))
        assertEvents(ReadingState.PREPARING, ReadingState.SPEAKING)
        engine.nextCall()

        engine.answer(Answer.Fails(IllegalStateException("bug")))

        assertEquals(ReadingState.FAILED to "Reading failed: IllegalStateException", nextEvent())
        sink.awaitClosed()
        awaitThreadsEnded()
    }

    @Test
    fun `a sink that cannot open fails the reading`() {
        sink.openError = SpeechException("No audio output: no line")
        session().start()
        engine.nextCall()

        engine.answer(Answer.Audio(2400))

        assertEvents(ReadingState.PREPARING)
        assertEquals(ReadingState.FAILED to "No audio output: no line", nextEvent())
        sink.awaitClosed()
        awaitThreadsEnded()
        assertTrue(events.isEmpty())
    }

    @Test
    fun `synthesis waits while the lookahead is full and goes on as audio plays`() {
        val reading = session(lookaheadSeconds = 1.0)
        reading.start()
        engine.nextCall()
        engine.answer(Answer.Audio(RATE))
        assertEvents(ReadingState.PREPARING, ReadingState.SPEAKING)
        engine.nextCall()
        engine.answer(Answer.Audio(RATE))

        // 2 s synthesized, none played: over the lookahead of 1 s. No signal for "synthesis waits".
        Thread.sleep(100)
        assertTrue(engine.calls.isEmpty())

        sink.playedFrames = RATE.toLong()

        assertEquals(third, engine.nextCall())
        assertEquals(ReadingState.SPEAKING, reading.state)
    }

    @Test
    fun `the lookahead does not hold back the audio the start rule still needs`() {
        val chunks = listOf(chunk(0, "a".repeat(100)), chunk(101, "b".repeat(100)), chunk(202, "c".repeat(100)))
        session(chunks, lookaheadSeconds = 0.5).start()
        engine.nextCall()

        // r = 2: the start rule needs 2.4 s buffered, more than the lookahead.
        engine.answer(Answer.Audio(RATE, seconds = 2.0))

        assertEquals(chunks[1], engine.nextCall())
        assertEvents(ReadingState.PREPARING)
    }

    @Test
    fun `on a slow machine the buffer before the start and the lookahead are capped`() {
        val chunks = (0 until 5).map { chunk(it * 101, "x".repeat(100)) }
        val reading = session(chunks, lookaheadSeconds = 1.0, maxBufferSeconds = 2.0)
        reading.start()
        assertEvents(ReadingState.PREPARING)
        engine.nextCall()

        // r = 10: a gap-free start would need 43 s buffered after the first second, the cap asks 2 s.
        engine.answer(Answer.Audio(RATE, seconds = 10.0))
        engine.nextCall()
        engine.answer(Answer.Audio(RATE, seconds = 10.0))
        assertEvents(ReadingState.SPEAKING)
        // 2 s unplayed is within the capped lookahead of 2 s; 3 s is not.
        engine.nextCall()
        engine.answer(Answer.Audio(RATE, seconds = 10.0))
        // No signal for "synthesis waits": give it time to go on wrongly.
        Thread.sleep(100)
        assertTrue(engine.calls.isEmpty())

        sink.playedFrames = RATE.toLong()

        assertEquals(chunks[3], engine.nextCall())
    }

    @Test
    fun `concurrent stop, pause and resume end in one stop`() {
        val reading = session()
        reading.start()
        engine.nextCall()
        engine.answer(Answer.Audio(2400))
        assertEvents(ReadingState.PREPARING, ReadingState.SPEAKING)
        val barrier = CyclicBarrier(8)
        val callers =
            (0 until 8).map { i ->
                Thread {
                    barrier.await()
                    repeat(50) {
                        when ((i + it) % 3) {
                            0 -> reading.pause()
                            1 -> reading.resume()
                            else -> reading.stop()
                        }
                    }
                }.apply { start() }
            }

        callers.forEach { it.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS)) }

        sink.awaitClosed()
        awaitThreadsEnded()
        val states = events.toList().map { it.first }
        assertEquals(ReadingState.STOPPED, states.last())
        assertEquals(1, states.count { it == ReadingState.STOPPED })
        assertEquals(ReadingState.STOPPED, reading.state)
        assertEquals(1, sink.closes.get())
    }
}
