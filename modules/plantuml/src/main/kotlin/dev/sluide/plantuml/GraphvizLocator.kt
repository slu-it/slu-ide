package dev.sluide.plantuml

import dev.sluide.terminal.loginShellPrints
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Finds Graphviz's `dot`: the login shell's `command -v dot` first (an app started from the Finder has no Homebrew
 * `PATH`), then the usual install folders (Homebrew, `/usr/local`, MacPorts, `/usr/bin`); only an executable file
 * counts, and only if it answers `dot -V` with a Graphviz version within [versionTimeout]. There is no setting and no
 * invalidation: PlantUML keeps what it learnt about `dot` for the whole JVM, so the result, also "not found", is kept
 * for the app run (ADR 0193).
 *
 * [locate] blocks (the shell lookup takes up to 10 s, the check up to [versionTimeout]): never call it on the FX
 * thread.
 */
class GraphvizLocator internal constructor(
    private val loginShellLookup: () -> String?,
    private val fallbackDirs: List<Path>,
    private val versionTimeout: Duration,
    /** Where `dot -V` writes its answer; deleted after the check. */
    private val answerFile: () -> Path,
) {
    constructor(
        loginShellLookup: () -> String? = { loginShellPrints("command -v dot", System.getenv()) },
        fallbackDirs: List<Path> =
            listOf("/opt/homebrew/bin", "/usr/local/bin", "/opt/local/bin", "/usr/bin").map(Path::of),
        versionTimeout: Duration = 5.seconds,
    ) : this(loginShellLookup, fallbackDirs, versionTimeout, { Files.createTempFile("sluide-dot-version", ".txt") })

    private val found: Path? by lazy { find()?.takeIf(::answersVersion) }

    fun locate(): Path? = found

    /**
     * Whether [dot] answers `-V` as Graphviz does. PlantUML keeps the answer of the first `dot -V` of the JVM for good
     * and lays out with Smetana, without a word, when it had no version: a `dot` that fails here is not used, so that
     * the note about the built-in layout shows.
     */
    private fun answersVersion(dot: Path): Boolean {
        // A throw here would end the render thread, which locates outside its draw's guard.
        val problem =
            try {
                versionProblem(dot)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                "interrupted"
            } catch (failure: Exception) {
                "it failed: $failure"
            } ?: return true
        log.log(
            System.Logger.Level.WARNING,
            "$dot fails the check `dot -V` ($problem): drawing with the built-in layout",
        )
        return false
    }

    /** What is wrong with the answer of `dot -V`, `null` if nothing. Graphviz prints the version to stderr. */
    private fun versionProblem(dot: Path): String? {
        val answer =
            try {
                answerFile()
            } catch (failure: IOException) {
                return "no file for its answer: ${failure.message}"
            }
        try {
            val process =
                try {
                    ProcessBuilder(
                        dot.toString(),
                        "-V",
                    ).redirectErrorStream(true).redirectOutput(answer.toFile()).start()
                } catch (_: IOException) {
                    return "it cannot be started"
                }
            if (!process.waitFor(versionTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                return "no answer within $versionTimeout"
            }
            if (process.exitValue() != 0) return "exit code ${process.exitValue()}"
            val text =
                try {
                    Files.newInputStream(answer).use { String(it.readNBytes(MAX_ANSWER), Charsets.UTF_8) }
                } catch (failure: IOException) {
                    return "its answer cannot be read: ${failure.message}"
                }
            return if ("graphviz version" in text) null else "no Graphviz version in its answer"
        } finally {
            answer.toFile().delete()
        }
    }

    private fun find(): Path? {
        val line =
            lookUp()?.let { printed ->
                printed.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            }
        executable(line)?.let { return it }
        return fallbackDirs.firstNotNullOfOrNull { executable(it.resolve("dot").toString()) }
    }

    /**
     * The login shell's answer, `null` if it failed: the lookup starts a pseudo terminal through pty4j, which can throw
     * or miss its native library, and a throw here would end the render thread.
     */
    private fun lookUp(): String? =
        try {
            loginShellLookup()
        } catch (failure: Exception) {
            logFailure(failure)
            null
        } catch (failure: LinkageError) {
            logFailure(failure)
            null
        }

    private fun logFailure(failure: Throwable) {
        log.log(System.Logger.Level.WARNING, "Looking up dot through the login shell failed", failure)
    }

    private companion object {
        val log: System.Logger = System.getLogger(GraphvizLocator::class.java.name)

        /** `dot -V` prints one line; more is not read. */
        const val MAX_ANSWER = 4096
    }

    private fun executable(path: String?): Path? {
        val file =
            try {
                Path.of(path ?: return null)
            } catch (_: InvalidPathException) {
                return null
            }
        return file.takeIf { Files.isRegularFile(it) && Files.isExecutable(it) }
    }
}
