package dev.sluide.git

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
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

    /**
     * The paths git lists with `git ls-files -z` and [option] in the repository at [root], relative to it, `/`-separated
     * and not quoted; `null` if git is not available, fails or takes longer than 10 s.
     */
    private fun listFiles(
        root: Path,
        vararg option: String,
    ): List<String>? {
        if (!available) return null
        val arguments = listOf("--no-optional-locks", "ls-files", "-z") + option
        return run(arguments, root, 10.seconds)?.split('\u0000')?.filter(String::isNotEmpty)
    }

    /** The paths in the index of the repository at [root] (ADR 0048); `null` on failure. */
    internal fun indexedPaths(root: Path): List<String>? = listFiles(root)

    /** The untracked files, not ignored, one by one also inside untracked folders (ADR 0048); `null` on failure. */
    internal fun untrackedPaths(root: Path): List<String>? = listFiles(root, "--others", "--exclude-standard")

    /**
     * Every file on disk that is not in the index, the ignored ones too, one by one also inside untracked and ignored
     * folders (which may be many: only for the few paths the JVM cannot name); `null` on failure.
     */
    internal fun otherPaths(root: Path): List<String>? = listFiles(root, "--others")

    /**
     * The paths of the repository at [root] whose index differs from `HEAD` ([staged]) or whose working-tree file
     * differs from the index (not [staged]): `git diff [--cached HEAD] --name-only -z --no-renames`, each path of a
     * rename on its own, without unmerged paths (`--diff-filter=u`, their stages come from commits) and without
     * submodules that differ only by changes inside their checkout (`--ignore-submodules=dirty`); `null` if git is not
     * available, fails (also without a `HEAD` commit for [staged]) or takes longer than 10 s.
     */
    internal fun changedPaths(
        root: Path,
        staged: Boolean,
    ): Set<String>? {
        if (!available) return null
        val arguments =
            buildList {
                addAll(listOf("--no-optional-locks", "diff", "--name-only", "-z", "--no-renames", "--no-ext-diff"))
                addAll(listOf("--diff-filter=u", "--ignore-submodules=dirty"))
                if (staged) addAll(listOf("--cached", "HEAD"))
                add("--")
            }
        return run(arguments, root, 10.seconds)?.split('\u0000')?.filter(String::isNotEmpty)?.toSet()
    }

    /**
     * How the index of the repository at [root] differs from [commit] (a full hash): `git diff-index --cached --raw -z
     * --no-renames`, records `:<commit's mode> <index's mode> <object> <object> <status>` NUL `<path>` NUL, the status
     * `D` for a path only [commit] has, `A` for one only the index has, `U` for an unmerged one; `null` if git is not
     * available, fails or takes longer than 10 s.
     */
    internal fun indexChanges(
        root: Path,
        commit: String,
    ): String? {
        if (!available || commit.startsWith('-')) return null
        val arguments =
            listOf("--no-optional-locks", "diff-index", "--cached", "--raw", "-z", "--no-renames", commit, "--")
        return run(arguments, root, 10.seconds)
    }

    /**
     * The tree that merging [theirs] into [ours] (commits, or trees with git 2.44 or later) with [base] as their merge
     * base gives in the repository at [root], as `git cherry-pick` and `git revert` merge: `git merge-tree
     * --write-tree --merge-base=<base>` (git 2.40 or later), also with conflicts (their files with conflict markers).
     * It stores the merged files' objects, unreferenced, and changes neither the index nor files; merge drivers of the
     * configuration run, and a partial clone may fetch missing objects. `null` if git is not available, fails or takes
     * longer than 60 s.
     */
    internal fun mergeTree(
        root: Path,
        base: String,
        ours: String,
        theirs: String,
    ): String? {
        if (!available || listOf(base, ours, theirs).any { it.startsWith('-') }) return null
        val arguments =
            listOf(
                "--no-optional-locks",
                "merge-tree",
                "--write-tree",
                "--no-messages",
                "--merge-base=$base",
                ours,
                theirs,
            )
        val execution = execute(arguments, root, 60.seconds, null, write = false) ?: return null
        // 1: conflicts, with the tree all the same.
        if (execution.exitValue !in 0..1 || !execution.complete) return null
        return execution.output
            .toString(Charsets.UTF_8)
            .lineSequence()
            .first()
            .trim()
            .takeIf { it.isNotEmpty() && it.all { char -> char in '0'..'9' || char in 'a'..'f' } }
    }

    internal fun run(
        arguments: List<String>,
        directory: Path?,
        timeout: Duration,
    ): String? = runBytes(arguments, directory, timeout)?.toString(Charsets.UTF_8)

    /**
     * Runs git with [arguments] in [directory], [input] on its standard input; its output if it succeeds in time and is
     * not stopped by [cancellation]. Reads take no optional locks and drop what git says on its standard error.
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
     * editor (`GIT_EDITOR=false`: git fails at once where it would open one). The result has git's own message: its
     * standard error, or its standard output if that is empty, trimmed and without git's `hint:` lines, which point at
     * command lines (unless there is nothing else). A command that [writesFiles] (a checkout, a cherry-pick, a hard
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
     * [environment] is added to git's. A [cancellation] stops git as the timeout does (ADR 0043), for commands that
     * may be stopped halfway: a fetch, a commit (SIGTERM removes git's lock files, and the ref is updated last);
     * cancelled before git started, git is not run.
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
     * What git is in the middle of in the repository at [root] (ADR 0042): a rebase (`rebase-merge`, `rebase-apply`),
     * `git am` (`rebase-apply/applying`), a merge (`MERGE_HEAD`) or a cherry-pick or revert sequence (`sequencer`),
     * found at their `git rev-parse --git-path` (per worktree), else a single cherry-pick or revert (`CHERRY_PICK_HEAD`,
     * `REVERT_HEAD`) with the paths still unmerged; `null` for nothing. A failure if git is not available or fails.
     */
    internal fun inProgress(root: Path): Result<GitInProgress?> {
        val unreadable = Result.failure<GitInProgress?>(IllegalStateException("git could not be read"))
        if (!available) return unreadable
        val (rebaseMerge, rebaseApply, sequencer) =
            gitPaths(root, listOf("rebase-merge", "rebase-apply", "sequencer")) ?: return unreadable
        val operation =
            when {
                Files.isDirectory(rebaseMerge) -> GitOperation.REBASE
                Files.exists(rebaseApply.resolve("applying")) -> GitOperation.AM
                Files.isDirectory(rebaseApply) -> GitOperation.REBASE
                commitOfRevision(root, "MERGE_HEAD") != null -> GitOperation.MERGE
                Files.isDirectory(sequencer) -> sequenceOperation(sequencer.resolve("todo"))
                else -> null
            }
        if (operation != null) return Result.success(GitPendingOperation(operation))
        val (kind, commit) =
            commitOfRevision(root, "CHERRY_PICK_HEAD")?.let { GitPick.CHERRY_PICK to it }
                ?: commitOfRevision(root, "REVERT_HEAD")?.let { GitPick.REVERT to it }
                ?: return Result.success(null)
        val conflicts = unmergedPaths(root) ?: return unreadable
        return Result.success(GitPendingPick(kind, commit, conflicts))
    }

    /** A cherry-pick or a revert sequence, as the first instruction of its [todo] file says. */
    private fun sequenceOperation(todo: Path): GitOperation {
        val first =
            try {
                Files
                    .readAllLines(todo)
                    .map(String::trim)
                    .firstOrNull { it.isNotEmpty() && !it.startsWith('#') }
            } catch (_: IOException) {
                null
            }
        return when (first?.substringBefore(' ')) {
            "pick", "p" -> GitOperation.CHERRY_PICK_SEQUENCE
            "revert" -> GitOperation.REVERT_SEQUENCE
            else -> GitOperation.SEQUENCE
        }
    }

    /**
     * Where the files [names] of the repository at [root] are: `git rev-parse --git-path`, which knows linked
     * worktrees; `null` if git is not available or fails, or if a path cannot be a path in the JVM's locale.
     */
    internal fun gitPaths(
        root: Path,
        names: List<String>,
    ): List<Path>? {
        if (!available) return null
        val arguments = listOf("--no-optional-locks", "rev-parse") + names.flatMap { listOf("--git-path", it) }
        val lines = run(arguments, root, 3.seconds)?.lines()?.filter(String::isNotEmpty) ?: return null
        if (lines.size != names.size) return null
        return try {
            lines.map { root.resolve(it) }
        } catch (_: InvalidPathException) {
            null
        }
    }

    /**
     * The unmerged entries in the index of the repository at [root] (conflicts), one per stage, in the index's order:
     * `git ls-files -u -z`; `null` if git is not available, fails or takes longer than 10 s.
     */
    internal fun unmergedEntries(root: Path): List<UnmergedEntry>? {
        if (!available) return null
        val output = run(listOf("--no-optional-locks", "ls-files", "--unmerged", "-z"), root, 10.seconds) ?: return null
        // <mode> <object> <stage>\t<path>
        return output.split('\u0000').filter { '\t' in it }.map { record ->
            val info = record.substringBefore('\t').split(' ')
            if (info.size != 3) return null
            UnmergedEntry(record.substringAfter('\t'), info[2].toIntOrNull() ?: return null, info[0], info[1])
        }
    }

    /** The paths of the [unmergedEntries], relative to [root], each once; `null` on failure. */
    internal fun unmergedPaths(root: Path): List<String>? = unmergedEntries(root)?.map { it.path }?.distinct()

    /**
     * The tracked paths of the repository at [root] whose file is missing from the working tree (deleted), also those
     * with unmerged entries: `git ls-files -d -z`; `null` if git is not available, fails or takes longer than 10 s.
     */
    internal fun missingFiles(root: Path): Set<String>? {
        if (!available) return null
        return run(listOf("--no-optional-locks", "ls-files", "--deleted", "-z"), root, 10.seconds)
            ?.split('\u0000')
            ?.filter(String::isNotEmpty)
            ?.toSet()
    }

    /**
     * The values of the git attributes [names] of the [paths] in the repository at [root] ("unspecified", "set",
     * "unset" or a value), by path and name: `git check-attr --stdin -z`, the paths on its standard input; `null` if git
     * is not available, fails or takes longer than 10 s.
     */
    internal fun attributes(
        root: Path,
        paths: List<String>,
        names: List<String>,
    ): Map<String, Map<String, String>>? {
        if (!available) return null
        if (paths.isEmpty()) return emptyMap()
        val input = paths.joinToString("") { "$it\u0000" }.toByteArray(Charsets.UTF_8)
        val arguments = listOf("--no-optional-locks", "check-attr", "--stdin", "-z") + names
        val output = runBytes(arguments, root, 10.seconds, input)?.toString(Charsets.UTF_8) ?: return null
        // <path> NUL <attribute> NUL <value> NUL
        val fields = output.split('\u0000')
        return (0 until fields.size / 3)
            .map { Triple(fields[it * 3], fields[it * 3 + 1], fields[it * 3 + 2]) }
            .groupBy({ it.first }, { it.second to it.third })
            .mapValues { (_, values) -> values.toMap() }
    }

    /**
     * Whether the index of the repository at [root] has the tree of `HEAD`: `git diff-index --cached --quiet HEAD`;
     * `null` if git is not available, fails (also without a `HEAD` commit) or takes longer than 10 s.
     */
    internal fun indexMatchesHead(root: Path): Boolean? {
        if (!available) return null
        val arguments = listOf("--no-optional-locks", "diff-index", "--cached", "--quiet", "HEAD", "--")
        return when (execute(arguments, root, 10.seconds, null, write = false)?.exitValue) {
            0 -> true
            1 -> false
            else -> null
        }
    }

    /**
     * Runs git with [arguments] in [directory], [input] on its standard input, and waits up to [timeout] for it; `null`
     * if it cannot be started or the calling thread is interrupted (git is stopped then, and the interrupt kept). A
     * [write] keeps git's standard error and may take optional locks; a read drops the one and takes none of the other.
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
        val process =
            try {
                ProcessBuilder(listOf(executable) + guard + arguments)
                    .directory(directory?.toFile())
                    .apply {
                        if (!write) redirectError(ProcessBuilder.Redirect.DISCARD)
                        environment().clear()
                        environment().putAll(this@GitCli.environment)
                        environment().putAll(environment)
                        // Reads only: git status would otherwise rewrite .git/index, which our .git watch reports.
                        if (!write) environment()["GIT_OPTIONAL_LOCKS"] = "0"
                        // Never wait for credentials or an editor.
                        environment()["GIT_TERMINAL_PROMPT"] = "0"
                        if (write) environment()["GIT_EDITOR"] = "false"
                    }.start()
            } catch (e: IOException) {
                log.log(System.Logger.Level.DEBUG, "Cannot run $executable", e)
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
                    return Execution(null, ByteArray(0), ByteArray(0), complete = false, cancelled = true)
                }
                log.log(
                    System.Logger.Level.WARNING,
                    "$executable ${arguments.joinToString(" ")} took longer than $timeout",
                )
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
        } catch (_: InterruptedException) {
            // E.g. the window was closed during a run: stop git too, and keep the interrupt for the caller.
            stop(process, write)
            Thread.currentThread().interrupt()
            return null
        }
        // Copies, synchronized with readers that may still be writing.
        return Execution(
            process.exitValue(),
            output.toByteArray(),
            errors.toByteArray(),
            readers.none(Thread::isAlive),
            overflowed = overflowed.get(),
        )
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
                process.destroy()
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

/** An unmerged index entry (a conflict): the [path]'s version of [stage] 1 (base), 2 (ours) or 3 (theirs). */
internal data class UnmergedEntry(
    val path: String,
    val stage: Int,
    val mode: String,
    val objectName: String,
)
