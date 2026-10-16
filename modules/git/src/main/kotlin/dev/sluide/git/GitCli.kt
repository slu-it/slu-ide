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
     * The output of `git status --porcelain=v2 --branch -z --ignored` for the working tree at [root] (see
     * [GitStatusParser]), or `null` if git is not available, fails or takes longer than 10 s.
     */
    internal fun status(root: Path): String? {
        if (!available) return null
        // Without optional locks git does not rewrite .git/index, which would trigger our own watch of .git.
        val arguments = listOf("--no-optional-locks", "status", "--porcelain=v2", "--branch", "-z", "--ignored")
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
     * The output of `git log` for the repository at [root] (see [GitLogParser]): the commits of all branches, remote
     * branches, tags and `HEAD` in date order, after the first [skip], at most [limit]; or `null` if git is not
     * available, fails or takes longer than 10 s. Empty for a repository without commits.
     */
    internal fun log(
        root: Path,
        skip: Int,
        limit: Int,
    ): String? {
        if (!available) return null
        val arguments =
            listOf(
                "--no-optional-locks",
                "log",
                // Not --all, which would add stash and notes commits.
                "--branches",
                "--remotes",
                "--tags",
                // An unborn HEAD (no commits yet) would otherwise make git fail.
                "--ignore-missing",
                "HEAD",
                "--date-order",
                "-z",
                "--no-color",
                "--no-show-signature",
                // The output is read as UTF-8, whatever i18n.logOutputEncoding says.
                "--encoding=UTF-8",
                "--skip=$skip",
                "--max-count=$limit",
                // %aN and %aE: name and email as mapped by .mailmap.
                "--format=%H%x1f%P%x1f%aN%x1f%aE%x1f%at%x1f%s",
                // HEAD is a revision, even if a file of that name exists.
                "--",
            )
        return run(arguments, root, 10.seconds)
    }

    /**
     * The output of `git for-each-ref` for the branches, remote branches and tags of the repository at [root] (see
     * [GitRefsParser]), or `null` if git is not available, fails or takes longer than 10 s.
     */
    internal fun refs(root: Path): String? {
        if (!available) return null
        val arguments =
            listOf(
                "--no-optional-locks",
                "for-each-ref",
                // %(*objectname): the commit an annotated tag points at, empty for other refs; %(symref): the target
                // of a symbolic ref such as refs/remotes/origin/HEAD, which is skipped. Records end with a newline.
                "--format=%(objectname)%1f%(*objectname)%1f%(refname)%1f%(symref)",
                "refs/heads",
                "refs/remotes",
                "refs/tags",
            )
        return run(arguments, root, 10.seconds)
    }

    /**
     * The full object name of the commit `HEAD` points at in the repository at [root]; `null` if there is none yet
     * (an unborn branch), or if git is not available, fails or takes longer than 3 s.
     */
    internal fun headCommit(root: Path): String? {
        if (!available) return null
        // -q: an unborn HEAD fails quietly instead of with an error message.
        val arguments = listOf("--no-optional-locks", "rev-parse", "--verify", "-q", "HEAD^{commit}")
        return run(arguments, root, 3.seconds)?.trim()?.ifEmpty { null }
    }

    /**
     * The full refname of the branch `HEAD` is attached to (`refs/heads/main`, also if it has no commits yet); `null`
     * when `HEAD` is detached, or if git is not available, fails or takes longer than 3 s.
     */
    internal fun currentBranch(root: Path): String? {
        if (!available) return null
        // -q: a detached HEAD fails quietly instead of with an error message.
        return run(listOf("--no-optional-locks", "symbolic-ref", "-q", "HEAD"), root, 3.seconds)?.trim()?.ifEmpty {
            null
        }
    }

    /**
     * The output of `git show -s` for the commit [hash] in the repository at [root] (see [GitCommitDetailsParser]),
     * or `null` if git is not available, fails (e.g. there is no such commit) or takes longer than 3 s.
     */
    internal fun show(
        root: Path,
        hash: String,
    ): String? {
        if (!available) return null
        val arguments =
            listOf(
                "--no-optional-locks",
                "show",
                // Only the commit, not its diff.
                "-s",
                "--no-color",
                "--no-show-signature",
                // The output is read as UTF-8, whatever i18n.logOutputEncoding says.
                "--encoding=UTF-8",
                // %aN, %aE, %cN and %cE as mapped by .mailmap; %B, the raw message, last.
                "--format=%H%x1f%P%x1f%aN%x1f%aE%x1f%at%x1f%cN%x1f%cE%x1f%ct%x1f%B",
                hash,
                // The hash is a revision, even if a file of that name exists.
                "--",
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
