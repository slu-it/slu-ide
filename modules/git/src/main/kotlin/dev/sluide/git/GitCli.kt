package dev.sluide.git

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val log = System.getLogger(GitCli::class.java.name)

/**
 * Runs the `git` command line tool (ADR 0013). Whether it is installed is checked once, when the bean is created at
 * startup; without it, [status] returns `null` and Thoreau shows no git information.
 */
@Component
class GitCli(
    /** The git executable, looked up on the `PATH` unless it is a path. */
    private val executable: String,
    /** The environment of the started processes. */
    private val environment: Map<String, String>,
) {
    @Autowired
    constructor() : this("git", System.getenv())

    /** Whether `git --version` ran successfully at startup. */
    val available: Boolean =
        run(listOf("--version"), directory = null, timeout = 3.seconds).let { version ->
            if (version == null) {
                log.log(System.Logger.Level.INFO, "git is not available; git information is not shown")
            } else {
                log.log(System.Logger.Level.INFO, "Using ${version.trim()}")
            }
            version != null
        }

    /**
     * The output of `git status --porcelain=v2 -z --ignored` for the working tree at [root] (see [GitStatusParser]),
     * or `null` if git is not available, fails or takes longer than 10 s.
     */
    internal fun status(root: Path): String? {
        if (!available) return null
        // Without optional locks git does not rewrite .git/index, which would trigger our own watch of .git.
        val arguments = listOf("--no-optional-locks", "status", "--porcelain=v2", "-z", "--ignored")
        return run(arguments, root, 10.seconds)
    }

    /**
     * The content of [file] at `HEAD` in the repository at [root], with the filters (line endings, smudge) a checkout
     * would apply, or `null` if git is not available, fails (e.g. the file is not in `HEAD`) or takes longer than 3 s.
     */
    internal fun headContent(
        root: Path,
        file: Path,
    ): String? {
        if (!available) return null
        val path = relativePath(root, file) ?: return null
        return run(listOf("cat-file", "--filters", "HEAD:$path"), root, 3.seconds)
    }

    /**
     * The output of `git diff -U0` between `HEAD` and the working tree for [file] in the repository at [root] (see
     * [GitDiffParser]), or `null` if git is not available, fails or takes longer than 3 s.
     */
    internal fun diffToHead(
        root: Path,
        file: Path,
    ): String? {
        if (!available) return null
        val path = relativePath(root, file) ?: return null
        val arguments =
            listOf(
                "--no-optional-locks",
                // File names are not patterns, even with * or ? in them.
                "--literal-pathspecs",
                "diff",
                "--no-color",
                "--no-ext-diff",
                "--no-textconv",
                "-U0",
                "HEAD",
                "--",
                path,
            )
        return run(arguments, root, 3.seconds)
    }

    /**
     * [file] relative to [root], `/`-separated as git wants it; `null` if it is not inside [root]. Relative paths are
     * taken from the working directory.
     */
    private fun relativePath(
        root: Path,
        file: Path,
    ): String? {
        val relative = root.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize())
        if (relative.toString().isEmpty() || relative.startsWith("..")) return null
        return relative.joinToString("/")
    }

    private fun run(
        arguments: List<String>,
        directory: Path?,
        timeout: Duration,
    ): String? {
        val process =
            try {
                ProcessBuilder(listOf(executable) + arguments)
                    .directory(directory?.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .apply {
                        environment().clear()
                        environment().putAll(this@GitCli.environment)
                        environment()["GIT_OPTIONAL_LOCKS"] = "0"
                        // Never wait for credentials or an editor.
                        environment()["GIT_TERMINAL_PROMPT"] = "0"
                    }.start()
            } catch (e: IOException) {
                log.log(System.Logger.Level.DEBUG, "Cannot run $executable", e)
                return null
            }
        process.outputStream.close()
        // Read while git runs, so a large output cannot fill the pipe and block it.
        val output = ByteArrayOutputStream()
        val reader = Thread.ofVirtual().start { process.inputStream.use { it.transferTo(output) } }
        try {
            if (!process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
                stop(process)
                log.log(
                    System.Logger.Level.WARNING,
                    "$executable ${arguments.joinToString(" ")} took longer than $timeout",
                )
                return null
            }
            reader.join()
        } catch (_: InterruptedException) {
            // E.g. the window was closed during a run: stop git too, and keep the interrupt for the caller.
            stop(process)
            Thread.currentThread().interrupt()
            return null
        }
        return if (process.exitValue() == 0) output.toString(Charsets.UTF_8) else null
    }

    /** Kills git and whatever it started (hooks, helpers); the descendants first, as they are lost with git. */
    private fun stop(process: Process) {
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
    }
}
