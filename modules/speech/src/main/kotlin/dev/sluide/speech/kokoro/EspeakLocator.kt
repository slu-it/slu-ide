package dev.sluide.speech.kokoro

import dev.sluide.terminal.loginShellPrints
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/**
 * Finds the `espeak-ng` executable. Order: the path from Settings → Speech if it is an executable file, then the login
 * shell's `command -v` (an app started from the Finder has no Homebrew `PATH`), then the usual install folders
 * (Homebrew, `/usr/local`, MacPorts, `/usr/bin`). The result, also "not found", is kept until [invalidate].
 *
 * [locate] blocks (the shell lookup takes up to 10 s): never call it on the FX thread. [invalidate] never blocks.
 */
internal class EspeakLocator(
    private val configuredPath: () -> String,
    private val loginShellLookup: () -> String? = { loginShellPrints("command -v espeak-ng", System.getenv()) },
    private val fallbackDirs: List<Path> =
        listOf("/opt/homebrew/bin", "/usr/local/bin", "/opt/local/bin", "/usr/bin").map(Path::of),
) {
    private class Cached(
        val generation: Int,
        val path: Path?,
    )

    private val lock = Any()
    private val generation = AtomicInteger()

    @Volatile private var cached: Cached? = null

    fun locate(): Path? {
        cached?.takeIf { it.generation == generation.get() }?.let { return it.path }
        synchronized(lock) {
            val current = generation.get()
            cached?.takeIf { it.generation == current }?.let { return it.path }
            val found = find()
            // An invalidate() during the lookup leaves the stale generation, so the next locate() looks again.
            cached = Cached(current, found)
            return found
        }
    }

    /**
     * Forgets the result, so the next [locate] looks again (the setting changed, or the user installed espeak-ng).
     * Lock-free, so it is safe on the FX thread even while a [locate] is running.
     */
    fun invalidate() {
        generation.incrementAndGet()
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
            } catch (_: InvalidPathException) {
                return null
            }
        return file.takeIf { Files.isRegularFile(it) && Files.isExecutable(it) }
    }
}
