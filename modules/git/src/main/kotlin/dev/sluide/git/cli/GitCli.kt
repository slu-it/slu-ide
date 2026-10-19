package dev.sluide.git.cli

import dev.sluide.git.GitCancellation
import dev.sluide.git.GitResult
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

private val log = System.getLogger(GitCli::class.java.name)

/**
 * Runs the `git` command line tool (ADR 0013). Whether it is installed is checked once, when the bean is created at
 * startup; without it, [status] returns `null` and Thoreau shows no git information.
 */
@Component
class GitCli(
    /** The git executable, looked up on the `PATH` unless it is a path. */
    private val executable: String,
    environment: Map<String, String>,
) {
    @Autowired
    constructor() : this("git", System.getenv())

    /**
     * The environment of the started processes: the given one without the variables that point git at another
     * repository, index or object store ([repositoryVariables]: `GIT_DIR`, `GIT_WORK_TREE`, `GIT_INDEX_FILE`, …).
     * Thoreau started from a git hook, `git rebase -x` or a shell that exported them would otherwise run every command,
     * a hard reset too, against that repository instead of the opened one. Git clears these variables when it switches
     * repositories (submodules), except configuration given in the environment, which it keeps, and so does Thoreau
     * ([CONFIGURATION_VARIABLES]).
     */
    internal val environment: Map<String, String> = environment - repositoryVariables(executable, environment)

    /** By folder, the options that keep reads there from running a `core.fsmonitor` hook ([fsmonitorGuard]). */
    private val fsmonitorGuards = ConcurrentHashMap<Path, FsmonitorGuard>()

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

    internal fun run(
        arguments: List<String>,
        directory: Path?,
        timeout: Duration,
        cancellation: GitCancellation? = null,
    ): String? = runBytes(arguments, directory, timeout, cancellation = cancellation)?.toString(Charsets.UTF_8)

    /**
     * Runs git with [arguments] in [directory], [input] on its standard input; its output if it succeeds in time and is
     * not stopped by [cancellation]. Reads take no optional locks and keep only the start of what git says on its
     * standard error, for the log.
     */
    internal fun runBytes(
        arguments: List<String>,
        directory: Path?,
        timeout: Duration,
        input: ByteArray? = null,
        cancellation: GitCancellation? = null,
    ): ByteArray? {
        val execution =
            execute(arguments, directory, timeout, input, write = false, cancellation = cancellation) ?: return null
        // An output that may have been cut off, or of a cancelled git, is no answer.
        return if (execution.exitValue == 0 && execution.complete && !execution.cancelled) execution.output else null
    }

    /**
     * Runs git with [arguments] in the repository at [root] to change it (ADR 0041), [input] on its standard input:
     * with optional locks, unlike the reads, as git itself would run; within [timeout], as hooks may run; never with an
     * editor (`GIT_EDITOR=false`: git fails at once where it would open one; [writeWithExit] may give another that
     * ends at once, such as `:`, which keeps the message git prepared). The result has git's own message: its standard
     * error, or its standard output if that is empty, trimmed and without git's `hint:` lines, which point at command
     * lines (unless there is nothing else). A command that [writesFiles] (a checkout, a cherry-pick, a hard
     * reset) and is stopped after the timeout may have changed some of them: its message says so ([timedOut]).
     */
    internal fun write(
        root: Path,
        arguments: List<String>,
        input: ByteArray? = null,
        timeout: Duration = WRITE_TIMEOUT,
        writesFiles: Boolean = false,
    ): GitResult = writeWithExit(root, arguments, input, timeout, writesFiles = writesFiles).first

    /**
     * [write], with git's exit value; `null` if git could not be run, did not finish in time or was cancelled. Some
     * failures are told apart only by it, e.g. a revert with nothing to commit (1) from one git refused to start (128).
     * [environment] is added to git's; its `GIT_EDITOR` replaces `false`. A [cancellation] stops git as the timeout
     * does (ADR 0043), for commands that may be stopped halfway: a fetch, a commit (SIGTERM removes git's lock files,
     * and the ref is updated last); cancelled before git started, git is not run.
     */
    internal fun writeWithExit(
        root: Path,
        arguments: List<String>,
        input: ByteArray? = null,
        timeout: Duration = WRITE_TIMEOUT,
        environment: Map<String, String> = emptyMap(),
        cancellation: GitCancellation? = null,
        writesFiles: Boolean = false,
    ): Pair<GitResult, Int?> =
        writeWithOutput(root, arguments, input, timeout, environment, cancellation, writesFiles).let {
            it.result to it.exitValue
        }

    /** [writeWithExit] with git's standard output as well, for commands that report on it (`push --porcelain`). */
    internal fun writeWithOutput(
        root: Path,
        arguments: List<String>,
        input: ByteArray? = null,
        timeout: Duration = WRITE_TIMEOUT,
        environment: Map<String, String> = emptyMap(),
        cancellation: GitCancellation? = null,
        writesFiles: Boolean = false,
    ): GitWriteOutput {
        if (!available) return GitWriteOutput(GitResult(false, "git is not available."), null)
        val execution =
            execute(arguments, root, timeout, input, write = true, environment, cancellation)
                ?: return GitWriteOutput(GitResult(false, "git could not be run."), null)
        if (execution.cancelled) return GitWriteOutput(GitResult(false, CANCELLED), null)
        val exitValue =
            execution.exitValue
                ?: return GitWriteOutput(GitResult(false, timedOut(timeout, writesFiles)), null, timedOut = true)
        val output = execution.output.toString(Charsets.UTF_8)
        val message =
            withoutHints(
                execution.errors
                    .toString(Charsets.UTF_8)
                    .trim()
                    .ifEmpty { output.trim() },
            )
        val result =
            when {
                exitValue == 0 -> GitResult(true, message)
                message.isEmpty() -> GitResult(false, "git failed with exit code $exitValue.")
                else -> GitResult(false, message)
            }
        val errors =
            execution.errors
                .toString(Charsets.UTF_8)
                .lines()
                .filterNot { it.startsWith("hint:") }
                .joinToString("\n")
                .trim()
        return GitWriteOutput(result, exitValue, output, errors)
    }

    /**
     * Runs git with [arguments] in [directory], [input] on its standard input, and waits up to [timeout] for it; `null`
     * if it cannot be started or the calling thread is interrupted (git is stopped then, and the interrupt kept). A
     * [write] keeps git's standard error and may take optional locks; a read keeps only the first bytes of it, for the
     * log, and takes no optional locks.
     * The input is written before the timeout starts and the write waits while git does not read, so it must be small:
     * a few lines, well below the pipe's buffer, or read by git at once (a tag message). [environment] is added to the
     * one of the processes. A [cancellation] stops git as the timeout does, unless git has ended by then; one before git
     * starts keeps it from starting. With [maxOutput], git and what it started are killed as soon as it writes more
     * than that many bytes, and the result says it [overflowed][Execution.overflowed]. A read in a [directory] runs no
     * `core.fsmonitor` hook ([fsmonitorGuard]) unless [guardFsmonitor] is `false`.
     */
    internal fun execute(
        arguments: List<String>,
        directory: Path?,
        timeout: Duration,
        input: ByteArray?,
        write: Boolean,
        environment: Map<String, String> = emptyMap(),
        cancellation: GitCancellation? = null,
        maxOutput: Long? = null,
        guardFsmonitor: Boolean = true,
    ): Execution? {
        if (cancellation?.cancelled == true) return Execution(null, ByteArray(0), ByteArray(0), false, cancelled = true)
        val guard = if (!write && guardFsmonitor && directory != null) fsmonitorGuard(directory) else emptyList()
        val entry = GitCommandLog.start(arguments, directory, input?.size, write)
        val process =
            try {
                ProcessBuilder(listOf(executable) + guard + arguments)
                    .directory(directory?.toFile())
                    .apply {
                        environment().clear()
                        environment().putAll(this@GitCli.environment)
                        environment().putAll(environment)
                        // Reads only: git status would otherwise rewrite .git/index, which our .git watch reports.
                        if (!write) environment()["GIT_OPTIONAL_LOCKS"] = "0"
                        // Never wait for credentials or an editor.
                        environment()["GIT_TERMINAL_PROMPT"] = "0"
                        // A caller may give one that ends at once (`:`, which keeps the prepared message).
                        if (write) environment()["GIT_EDITOR"] = environment["GIT_EDITOR"] ?: "false"
                    }.start()
            } catch (e: IOException) {
                entry.startFailed(e)
                return null
            }
        // Read while git runs, so a large output cannot fill the pipe and block it.
        val output = ByteArrayOutputStream()
        val errors = ByteArrayOutputStream()
        val overflowed = AtomicBoolean()
        val readers =
            listOfNotNull(
                Thread.ofVirtual().start {
                    process.inputStream.use { stream ->
                        if (maxOutput == null) {
                            stream.transferTo(output)
                        } else if (!copyAtMost(stream, output, maxOutput)) {
                            // More than wanted: no need to wait for the rest, nor for what git started (a filter).
                            overflowed.set(true)
                            stop(process, write = false)
                        }
                    }
                },
                if (write) Thread.ofVirtual().start { process.errorStream.use { it.transferTo(errors) } } else null,
            )
        // Only for the log (ADR 0060): the first bytes of what a read says on standard error. Not part of `complete`.
        val errorReader =
            if (write) {
                null
            } else {
                Thread.ofVirtual().start {
                    process.errorStream.use {
                        GitCommandLog.keepStart(
                            it,
                            errors,
                        )
                    }
                }
            }
        try {
            process.outputStream.use { stdin -> input?.let(stdin::write) }
        } catch (e: IOException) {
            // Git ended without reading all its input; its exit value tells.
            log.log(System.Logger.Level.DEBUG, "Cannot write to $executable", e)
        }
        try {
            if (!awaitEnd(process, timeout, cancellation)) {
                stop(process, write)
                // Git did not end, it was cancelled and has been stopped: what it updated by then stays.
                if (cancellation?.cancelled == true) {
                    entry.cancelled()
                    return Execution(null, ByteArray(0), ByteArray(0), complete = false, cancelled = true)
                }
                entry.timedOut(timeout)
                return Execution(null, ByteArray(0), ByteArray(0), complete = false)
            }
            // Git has ended, but its output may not be read to its end yet; a process it left behind (e.g. started by a
            // hook) may even hold the pipes open, so that the readers never end. They get a little time, whatever the
            // timeout: a read whose output is not read to its end by then fails, a write keeps what was read.
            val readDeadline = System.nanoTime() + PIPE_GRACE.inWholeNanoseconds
            readers.forEach { reader ->
                reader.join(
                    (readDeadline - System.nanoTime()).nanoseconds.coerceAtLeast(Duration.ZERO).toJavaDuration(),
                )
            }
            // Only for the log: a child that holds just standard error open must not delay the read.
            errorReader?.join(ERROR_GRACE.toJavaDuration())
        } catch (_: InterruptedException) {
            // E.g. the window was closed during a run: stop git too, and keep the interrupt for the caller.
            stop(process, write)
            Thread.currentThread().interrupt()
            entry.interrupted()
            return null
        }
        // Copies, synchronized with readers that may still be writing.
        val outputBytes = output.toByteArray()
        val errorBytes = synchronized(errors) { errors.toByteArray() }
        val complete = readers.none(Thread::isAlive)
        entry.exited(process.exitValue(), outputBytes, errorBytes, complete, overflowed.get())
        return Execution(process.exitValue(), outputBytes, errorBytes, complete, overflowed = overflowed.get())
    }

    /**
     * The options that keep a read in the repository at [directory] from running a `core.fsmonitor` hook (ADR 0052):
     * `-c core.fsmonitor=false` if the setting is no boolean but a command, which git runs whenever it refreshes the
     * index, so that opening a downloaded repository would run whatever its own `.git/config` says; none if it is
     * unset or a boolean (`true`: git's own daemon, which runs nothing of the repository's and stays fast). Read with
     * `git config --type=bool --get` (exit 128 for a value that is no boolean), which runs nothing, and kept for the
     * folder while its configuration has the same content ([gitStamp]: also a repository extracted there anew is read
     * again); not kept without `.git`, nor when the read failed otherwise (e.g. took too long), for which the hook is
     * turned off this once. Writes, which the user starts, run hooks anyway.
     */
    private fun fsmonitorGuard(directory: Path): List<String> {
        val folder = directory.toAbsolutePath().normalize()
        val stamp = gitStamp(folder)
        fsmonitorGuards[folder]?.takeIf { stamp != null && it.stamp == stamp }?.let { return it.options }
        val arguments = listOf("--no-optional-locks", "config", "--type=bool", "--get", "core.fsmonitor")
        val read = execute(arguments, folder, 3.seconds, null, write = false, guardFsmonitor = false)
        val exit = read?.exitValue?.takeIf { read.complete }
        val options = if (exit == 0 || exit == 1) emptyList() else GUARDED
        val definite = exit == 0 || exit == 1 || exit == 128
        if (stamp != null && definite) fsmonitorGuards[folder] = FsmonitorGuard(stamp, options)
        if (exit == 128) log.log(System.Logger.Level.INFO, "Reads in $folder run without its core.fsmonitor hook")
        return options
    }

    /**
     * What the setting of the repository in [folder] is read from: the content of `.git/config`, or of a `.git` file (a
     * linked worktree, whose configuration is the main repository's), as its SHA-256 (empty without `.git/config`).
     * Content, not times or inodes: an archive extracted anew restores its files' times, and a file system reuses
     * inodes. `null` without `.git`, or if it cannot be read.
     */
    private fun gitStamp(folder: Path): String? =
        try {
            val git = folder.resolve(".git")
            val file =
                when {
                    Files.isDirectory(git, LinkOption.NOFOLLOW_LINKS) -> git.resolve("config")
                    Files.exists(git, LinkOption.NOFOLLOW_LINKS) -> git
                    else -> null
                }
            when {
                file == null -> null
                !Files.exists(file) -> ""
                else -> HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)))
            }
        } catch (_: IOException) {
            null
        }

    /** The [options] of [fsmonitorGuard] for a folder, read when its configuration had the [stamp] of [gitStamp]. */
    private class FsmonitorGuard(
        val stamp: String,
        val options: List<String>,
    )

    /**
     * Kills git and whatever it started (hooks, helpers, ssh); the descendants first, as they are lost with git: those
     * found before are killed too if they outlive it. A [write] and its descendants are asked to end first (SIGTERM, on
     * which git, also a git a hook started, removes its lock files) and killed only if they have not after a moment,
     * one shared [STOP_GRACE] for all of them.
     */
    private fun stop(
        process: Process,
        write: Boolean,
    ) {
        val descendants = process.descendants().toList()
        if (write) {
            val interrupted = Thread.interrupted()
            try {
                val deadline = System.nanoTime() + STOP_GRACE.inWholeNanoseconds
                descendants.forEach { it.destroy() }
                // Not process.destroy(): it also closes git's pipes, and what git started would die of SIGPIPE on its
                // next word to them (a shell reporting its killed child) instead of cleaning up.
                process.toHandle().destroy()
                process.waitFor(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)
                // Git ends on SIGTERM at once; what it started may take a little longer to clean up.
                descendants.forEach { descendant ->
                    try {
                        descendant.onExit().get(maxOf(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
                    } catch (_: TimeoutException) {
                        // Killed below.
                    } catch (_: ExecutionException) {
                        // Whether it ended is told by destroyForcibly below.
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
        (descendants + process.descendants().toList()).forEach { it.destroyForcibly() }
        process.destroyForcibly()
    }

    /**
     * A run of git that ended: its [exitValue] (`null` if it took too long or was [cancelled] and was stopped) and what
     * it wrote, read to its end if [complete].
     */
    internal class Execution(
        val exitValue: Int?,
        val output: ByteArray,
        val errors: ByteArray,
        val complete: Boolean,
        val cancelled: Boolean = false,
        /** Git wrote more than the most output asked for, and was killed. */
        val overflowed: Boolean = false,
    )

    internal companion object {
        /** How long a git command that changes the repository may take (ADR 0041); hooks may run. */
        val WRITE_TIMEOUT = 60.seconds

        /**
         * How long a git command that changes files or the index may take, a checkout, cherry-pick or reset (ADRs 0041,
         * 0042): filters such as LFS may download files.
         */
        val CHECKOUT_TIMEOUT = 10.minutes

        /** How long a commit may take (ADR 0048): hooks, a GPG pinentry. */
        val COMMIT_TIMEOUT = 10.minutes

        /** How long fetching one remote may take (ADR 0043): a large repository over a slow network. */
        val FETCH_TIMEOUT = 10.minutes

        /** The message of a write that was cancelled (ADR 0043). */
        const val CANCELLED = "Cancelled."

        /** How long a stopped write may take to end by itself before it is killed. */
        private val STOP_GRACE = 2.seconds

        /** How long the output of a git that has ended may take to be read to its end, whatever the timeout. */
        private val PIPE_GRACE = 2.seconds

        /** How long the standard error of a read that has ended may take to be read, for the log only. */
        private val ERROR_GRACE = 100.milliseconds

        /**
         * The most bytes written to git's standard input. They are written while git runs (its output is read in
         * parallel), but the write blocks while git does not read and is not covered by the timeout ([execute]); below
         * the smallest default pipe buffer (16 KiB on macOS) it never blocks.
         */
        const val MAX_INPUT = 15 * 1024

        /** The options that turn a `core.fsmonitor` hook off for a read. */
        private val GUARDED = listOf("-c", "core.fsmonitor=false")
    }
}
