package dev.sluide.speech.kokoro

import dev.sluide.speech.engine.SpeechException
import dev.sluide.terminal.loginShellPrints
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** What a finished (or timed out) process printed. */
internal class ProcessResult(
    val stdout: String,
    val exitCode: Int,
    val timedOut: Boolean = false,
)

/** Runs one process with [stdin] as its input; the seam that lets tests do without espeak-ng. */
internal fun interface ProcessRunner {
    /** Blocks; kills the process after [timeout] and reports `timedOut`. Throws [InterruptedException] when interrupted. */
    @Throws(InterruptedException::class)
    fun run(
        command: List<String>,
        stdin: String,
        timeout: Duration,
    ): ProcessResult

    companion object {
        val Default =
            ProcessRunner { command, stdin, timeout ->
                val process =
                    try {
                        ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                    } catch (e: java.io.IOException) {
                        throw SpeechException("espeak-ng cannot be started: ${e.message}", e)
                    }
                var output = ByteArray(0)
                val reader =
                    Thread({ output = process.inputStream.readAllBytes() }, "espeak-output").apply {
                        isDaemon =
                            true
                    }
                try {
                    reader.start()
                    process.outputStream.use { it.write(stdin.toByteArray(Charsets.UTF_8)) }
                    val finished = process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                    if (!finished) {
                        process.destroyForcibly()
                        process.waitFor(1, TimeUnit.SECONDS)
                        return@ProcessRunner ProcessResult("", -1, timedOut = true)
                    }
                    reader.join(2000)
                    ProcessResult(String(output, Charsets.UTF_8), process.exitValue())
                } catch (e: java.io.IOException) {
                    process.destroyForcibly()
                    ProcessResult("", -1)
                } catch (e: InterruptedException) {
                    process.destroyForcibly()
                    throw e
                }
            }
    }
}

/**
 * Finds the `espeak-ng` executable: the path from Settings → Speech if it is an executable file, else the login shell's
 * `command -v` (the app started from the Finder has no Homebrew `PATH`), else the usual install folders. The result,
 * also "not found", is kept until [invalidate]. Blocking (the shell lookup): never call it on the FX thread.
 */
internal class EspeakLocator(
    private val configuredPath: () -> String,
    private val loginShellLookup: () -> String? = { loginShellPrints("command -v espeak-ng", System.getenv()) },
    private val fallbackDirs: List<Path> = listOf("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin").map(Path::of),
) {
    private val lock = Any()
    private var located = false
    private var cached: Path? = null

    fun locate(): Path? =
        synchronized(lock) {
            if (!located) {
                cached = find()
                located = true
            }
            cached
        }

    /** Forgets the result, so the next [locate] looks again (the setting changed, or the user installed espeak-ng). */
    fun invalidate() {
        synchronized(lock) {
            located = false
            cached = null
        }
    }

    private fun find(): Path? {
        executable(configuredPath().trim())?.let { return it }
        val line =
            loginShellLookup()
                ?.lineSequence()
                ?.map { it.trim() }
                ?.firstOrNull { it.isNotEmpty() }
        executable(line)?.let { return it }
        return fallbackDirs.firstNotNullOfOrNull { executable(it.resolve("espeak-ng").toString()) }
    }

    private fun executable(path: String?): Path? {
        if (path.isNullOrEmpty()) return null
        val file =
            try {
                Path.of(path)
            } catch (_: java.nio.file.InvalidPathException) {
                return null
            }
        return file.takeIf { Files.isRegularFile(it) && Files.isExecutable(it) }
    }
}

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
