package dev.sluide.speech.session

import dev.sluide.speech.audio.AudioSink
import dev.sluide.speech.engine.Gender
import dev.sluide.speech.engine.SetupProblem
import dev.sluide.speech.engine.SpeechChunk
import dev.sluide.speech.engine.SpeechEngine
import dev.sluide.speech.engine.SpeechException
import dev.sluide.speech.engine.SynthesizedChunk
import dev.sluide.speech.engine.Voice
import dev.sluide.speech.engine.WordTiming
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.fail

internal const val RATE = 24_000

internal val VOICE = Voice("af_heart", "Heart", "en-US", Gender.FEMALE, "A", "fake")

/** What [FakeEngine] answers to one synthesize call. */
internal sealed interface Answer {
    /** [frames] samples of audio, taking [seconds] of synthesis time on the fake clock. */
    data class Audio(
        val frames: Int,
        val seconds: Double = 0.0,
        val words: List<WordTiming> = emptyList(),
    ) : Answer

    data class Fails(
        val error: Exception,
    ) : Answer
}

/**
 * An engine whose every synthesize call waits for an [answer] from the test; [calls] receives each chunk as the call
 * starts. Advances [clock] by the answer's synthesis time.
 */
internal class FakeEngine(
    private val clock: AtomicLong,
) : SpeechEngine {
    override val id = "fake"
    override val voices = listOf(VOICE)
    val calls = LinkedBlockingQueue<SpeechChunk>()
    val chunks = CopyOnWriteArrayList<SpeechChunk>()
    val threads = CopyOnWriteArrayList<Thread>()
    val interrupted = CountDownLatch(1)
    private val answers = LinkedBlockingQueue<Answer>()

    override fun problems(): List<SetupProblem> = emptyList()

    override fun synthesize(
        chunk: SpeechChunk,
        voice: Voice,
        speed: Double,
    ): SynthesizedChunk {
        threads += Thread.currentThread()
        chunks += chunk
        calls.put(chunk)
        val answer =
            try {
                answers.take()
            } catch (e: InterruptedException) {
                interrupted.countDown()
                throw e
            }
        return when (answer) {
            is Answer.Audio -> {
                clock.addAndGet((answer.seconds * 1e9).toLong())
                SynthesizedChunk(chunk, FloatArray(answer.frames) { 0.25f }, RATE, answer.words)
            }

            is Answer.Fails -> {
                throw answer.error
            }
        }
    }

    /** Waits for the next synthesize call and returns its chunk. */
    fun nextCall(): SpeechChunk = calls.poll(WAIT_SECONDS, TimeUnit.SECONDS) ?: fail("no synthesize call")

    fun answer(answer: Answer) = answers.put(answer)
}

/** A sink that plays nothing: the test sets [playedFrames]; [drain] waits for [releaseDrain], a flush or close. */
internal class FakeSink : AudioSink {
    val calls = CopyOnWriteArrayList<String>()
    val written = AtomicLong()
    val closes = AtomicInteger()
    val closed = CountDownLatch(1)
    private val drainEnds = CountDownLatch(1)

    @Volatile
    var openThread: Thread? = null

    @Volatile
    var openError: SpeechException? = null

    @Volatile
    override var playedFrames = 0L

    override fun open(sampleRate: Int) {
        openThread = Thread.currentThread()
        calls += "open $sampleRate"
        openError?.let { throw it }
    }

    override fun write(samples: FloatArray) {
        written.addAndGet(samples.size.toLong())
    }

    override fun start() {
        calls += "start"
    }

    override fun pause() {
        calls += "pause"
    }

    override fun flush() {
        calls += "flush"
        drainEnds.countDown()
    }

    override fun drain() {
        calls += "drain"
        drainEnds.await()
    }

    override fun close() {
        calls += "close"
        closes.incrementAndGet()
        closed.countDown()
        drainEnds.countDown()
    }

    fun releaseDrain() = drainEnds.countDown()

    fun awaitClosed() {
        if (!closed.await(WAIT_SECONDS, TimeUnit.SECONDS)) fail("sink not closed")
    }
}

/** Generous for slow runners (docs/testing.md, "Time limits"); only reached when a test fails. */
internal const val WAIT_SECONDS = 10L

/** Polls [condition] until it holds or the wait times out. */
internal fun eventually(
    what: String,
    condition: () -> Boolean,
) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
    while (!condition()) {
        if (System.nanoTime() > deadline) fail("timed out waiting for $what")
        Thread.sleep(2)
    }
}

/** A chunk of one sentence at [start]. */
internal fun chunk(
    start: Int,
    text: String,
) = SpeechChunk(start, start + text.length, start, start + text.length, text)
