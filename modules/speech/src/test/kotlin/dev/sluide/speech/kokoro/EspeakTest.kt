package dev.sluide.speech.kokoro

import dev.sluide.speech.engine.SpeechException
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class EspeakTest {
    private val exe = Path.of("/usr/bin/espeak-ng")

    private class Call(
        val command: List<String>,
        val stdin: String,
        val timeout: Duration,
    )

    private class FakeRunner(
        private val reply: (Call) -> ProcessResult = { ProcessResult("", 0) },
    ) : ProcessRunner {
        val calls = java.util.Collections.synchronizedList(mutableListOf<Call>())

        override fun run(
            command: List<String>,
            stdin: String,
            timeout: Duration,
        ): ProcessResult {
            val call = Call(command, stdin, timeout)
            calls.add(call)
            return reply(call)
        }
    }

    @Test
    fun `command line and word on stdin`() {
        val runner = FakeRunner { ProcessResult("həlˈo^ʊ\n", 0) }
        val result = Espeak({ exe }, runner).phonemes("hello", "en-US")
        assertEquals("həlˈO", result)
        val call = runner.calls.single()
        assertEquals(listOf("/usr/bin/espeak-ng", "-q", "--ipa", "--tie=^", "-v", "en-us"), call.command)
        assertEquals("hello", call.stdin)
        assertEquals(5.seconds, call.timeout)
    }

    @Test
    fun `british voice uses en-gb and the british conversion`() {
        val runner = FakeRunner { ProcessResult("həlˈə^ʊ", 0) }
        assertEquals("həlˈQ", Espeak({ exe }, runner).phonemes("hello", "en-GB"))
        assertEquals(
            listOf("-v", "en-gb"),
            runner.calls
                .single()
                .command
                .takeLast(2),
        )
    }

    @Test
    fun `a word starting with a dash is never an argument`() {
        val runner = FakeRunner()
        Espeak({ exe }, runner).phonemes("--version", "en-US")
        val call = runner.calls.single()
        assertEquals("--version", call.stdin)
        assertTrue("--version" !in call.command)
    }

    @Test
    fun `non blank lines are joined with a space`() {
        val runner = FakeRunner { ProcessResult("ab\n\n  cd  \n", 0) }
        assertEquals("ab cd", Espeak({ exe }, runner).phonemes("x", "en-US"))
    }

    @Test
    fun `a blank word needs no process`() {
        val runner = FakeRunner()
        assertEquals("", Espeak({ exe }, runner).phonemes("  ", "en-US"))
        assertTrue(runner.calls.isEmpty())
    }

    @Test
    fun `timeout is a speech exception`() {
        val espeak = Espeak({ exe }, FakeRunner { ProcessResult("", -1, timedOut = true) })
        val e = assertFailsWith<SpeechException> { espeak.phonemes("x", "en-US") }
        assertTrue(e.message!!.startsWith("espeak-ng failed"), e.message)
    }

    @Test
    fun `non zero exit is a speech exception`() {
        val espeak = Espeak({ exe }, FakeRunner { ProcessResult("", 3) })
        val e = assertFailsWith<SpeechException> { espeak.phonemes("x", "en-US") }
        assertTrue(e.message!!.startsWith("espeak-ng failed") && "3" in e.message!!, e.message)
    }

    @Test
    fun `missing executable is a speech exception`() {
        val runner = FakeRunner()
        assertFailsWith<SpeechException> { Espeak({ null }, runner).phonemes("x", "en-US") }
        assertTrue(runner.calls.isEmpty())
    }

    @Test
    fun `results are cached per language and word, failures are not`() {
        val runner = FakeRunner { ProcessResult("a", 0) }
        val espeak = Espeak({ exe }, runner)
        espeak.phonemes("x", "en-US")
        espeak.phonemes("x", "en-US")
        assertEquals(1, runner.calls.size)
        espeak.phonemes("x", "en-GB")
        espeak.phonemes("y", "en-US")
        assertEquals(3, runner.calls.size)

        val failing = FakeRunner { ProcessResult("", 1) }
        val other = Espeak({ exe }, failing)
        repeat(2) { assertFailsWith<SpeechException> { other.phonemes("z", "en-US") } }
        assertEquals(2, failing.calls.size)
    }

    @Test
    fun `at most four processes run at once`() {
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val four = CountDownLatch(4)
        val release = CountDownLatch(1)
        val runner =
            FakeRunner {
                peak.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                four.countDown()
                release.await(10, TimeUnit.SECONDS)
                running.decrementAndGet()
                ProcessResult("a", 0)
            }
        val espeak = Espeak({ exe }, runner)
        val threads = (1..8).map { n -> Thread { espeak.phonemes("w$n", "en-US") }.apply { start() } }
        assertTrue(four.await(10, TimeUnit.SECONDS))
        // negative check without a signal: give a fifth process time to start if the limit were missing
        Thread.sleep(200)
        assertEquals(4, peak.get())
        release.countDown()
        threads.forEach { it.join(10_000) }
        assertEquals(8, runner.calls.size)
        assertEquals(4, peak.get())
    }

    private fun realExecutable(): Path? {
        val found = EspeakLocator({ "" }, loginShellLookup = { null }).locate()
        assumeTrue(found != null, "espeak-ng is not installed")
        return found
    }

    // Recorded with espeak-ng 1.52.0: the word on stdin, `-q --ipa --tie=^ -v <voice>`.
    private val recorded =
        listOf(
            Triple("hello", "en-US", "həlˈo^ʊ"),
            Triple("hello", "en-GB", "həlˈə^ʊ"),
            Triple("world", "en-US", "wˈɜːld"),
            Triple("reading", "en-US", "ɹˈiːdɪŋ"),
            Triple("the", "en-US", "ðˈə"),
            Triple("1984", "en-US", "nˈa^ɪntiːnhˈʌndɹɪd ˈe^ɪɾi fˈɔː^ɹ"),
            Triple("don't", "en-US", "dˈo^ʊnt"),
        )

    @Test
    fun `real espeak-ng prints the recorded IPA`() {
        val real = realExecutable() ?: return
        for ((word, language, ipa) in recorded) {
            val voice = if (language == "en-GB") "en-gb" else "en-us"
            val result =
                ProcessRunner.Default.run(
                    listOf(real.toString(), "-q", "--ipa", "--tie=^", "-v", voice),
                    word,
                    5.seconds,
                )
            assertEquals(0, result.exitCode)
            assertEquals(ipa, result.stdout.trim(), "$word ($language)")
        }
    }

    @Test
    fun `real espeak-ng with a word starting with a dash does not fail`() {
        val real = realExecutable() ?: return
        val espeak = Espeak({ real })
        assertTrue(espeak.phonemes("-x", "en-US").isNotEmpty())
    }

    @Test
    fun `real espeak-ng phonemes are converted`() {
        val real = realExecutable() ?: return
        val espeak = Espeak({ real })
        for ((word, language, ipa) in recorded) {
            assertEquals(
                EspeakToKokoro.convert(ipa, language == "en-GB"),
                espeak.phonemes(word, language),
                "$word ($language)",
            )
        }
        assertTrue(Files.isExecutable(real))
    }
}
