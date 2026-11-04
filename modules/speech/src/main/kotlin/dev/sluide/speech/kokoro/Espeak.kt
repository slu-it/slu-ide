package dev.sluide.speech.kokoro

import dev.sluide.speech.engine.SpeechException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import kotlin.time.Duration.Companion.seconds

/**
 * Phonemes through the `espeak-ng` command line, one process per distinct word (the word goes to stdin, so it can never
 * become an option), converted to Kokoro's alphabet by [EspeakToKokoro]. Thread-safe; at most [MAX_PROCESSES] processes
 * run at a time; results are cached per language and word. Blocking: never call it on the FX thread.
 */
internal class Espeak(
    private val executable: () -> Path?,
    private val runner: ProcessRunner = ProcessRunner.Default,
) {
    private val cache = ConcurrentHashMap<Pair<String, String>, String>()
    private val permits = Semaphore(MAX_PROCESSES)

    /** Kokoro phonemes of one [word] for `en-US` or `en-GB`. */
    @Throws(SpeechException::class, InterruptedException::class)
    fun phonemes(
        word: String,
        language: String,
    ): String {
        if (word.isBlank()) return ""
        val key = language to word
        cache[key]?.let { return it }
        val british = language.equals("en-GB", ignoreCase = true)
        val exe =
            executable()
                ?: throw SpeechException("espeak-ng not found")
        val command = listOf(exe.toString(), "-q", "--ipa", "--tie=^", "-v", if (british) "en-gb" else "en-us")
        permits.acquire()
        val result =
            try {
                runner.run(command, word, TIMEOUT)
            } finally {
                permits.release()
            }
        if (result.timedOut) throw SpeechException("espeak-ng failed: no answer within ${TIMEOUT.inWholeSeconds} s")
        if (result.exitCode != 0) throw SpeechException("espeak-ng failed: exit code ${result.exitCode}")
        val ipa =
            result.stdout
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(" ")
        val phonemes = EspeakToKokoro.convert(ipa, british)
        if (cache.size >= MAX_CACHED) cache.clear()
        cache[key] = phonemes
        return phonemes
    }

    companion object {
        const val MAX_PROCESSES = 4
        const val MAX_CACHED = 50_000
        val TIMEOUT = 5.seconds
    }
}
