package dev.sluide.git

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.Charset
import java.nio.file.Path
import java.util.concurrent.TimeUnit
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
     * The output of `git log` for the repository at [root] (see [GitLogParser]) with the [filter] (ADR 0039): by
     * default the commits of all branches, remote branches, tags and `HEAD` in date order; after the first [skip], at
     * most [limit]. `null` if git is not available, fails or takes longer than 10 s, or if a revision or path of the
     * filter has a line break or they are too many for git's standard input. Empty for a repository without commits.
     */
    internal fun log(
        root: Path,
        filter: GitLogFilter,
        skip: Int,
        limit: Int,
    ): String? {
        if (!available) return null
        // Revisions and paths go to git's standard input, so they reach it as UTF-8 whatever the JVM's locale.
        val input = logInput(filter.revisions, filter.paths) ?: return null
        val arguments =
            buildList {
                addAll(globalLogOptions(filter))
                add("log")
                if (filter.revisions.isEmpty()) {
                    // Not --all, which would add stash and notes commits.
                    addAll(listOf("--branches", "--remotes", "--tags"))
                    // An unborn HEAD (no commits yet) would otherwise make git fail.
                    addAll(listOf("--ignore-missing", "HEAD"))
                } else {
                    // A selected branch that was deleted meanwhile is skipped, also on the standard input.
                    add("--ignore-missing")
                }
                add(if (filter.order == GitLogOrder.TOPOLOGICAL) "--topo-order" else "--date-order")
                if (filter.firstParent) add("--first-parent")
                if (filter.noMerges) add("--no-merges")
                // %P then gives the parents as rewritten for the paths: the nearest commits shown.
                if (filter.paths.isNotEmpty()) add("--parents")
                addAll(patterns(filter.text.trim(), filter.regex, filter.matchCase, filter.users))
                if (input.isNotEmpty()) add("--stdin")
                addAll(logOutputOptions())
                add("--skip=$skip")
                add("--max-count=$limit")
                // HEAD is a revision, even if a file of that name exists.
                add("--")
            }
        return runBytes(arguments, root, 10.seconds, input.takeIf { it.isNotEmpty() })?.toString(Charsets.UTF_8)
    }

    /**
     * The output of `git log --no-walk` for the commit [hash] alone (see [GitLogParser]) if it passes the users,
     * paths and no merges of [filter] (not its text and revisions); empty if it does not. `null` if git is not
     * available, fails or takes longer than 10 s, or for paths as [log] refuses them.
     */
    internal fun logCommit(
        root: Path,
        hash: String,
        filter: GitLogFilter,
    ): String? {
        if (!available) return null
        val input = logInput(emptyList(), filter.paths) ?: return null
        val arguments =
            buildList {
                addAll(globalLogOptions(filter))
                addAll(listOf("log", "--no-walk"))
                if (filter.noMerges) add("--no-merges")
                addAll(patterns("", regex = false, matchCase = false, users = filter.users))
                if (input.isNotEmpty()) add("--stdin")
                addAll(logOutputOptions())
                add(hash)
                add("--")
            }
        return runBytes(arguments, root, 10.seconds, input.takeIf { it.isNotEmpty() })?.toString(Charsets.UTF_8)
    }

    /**
     * The full object name of the one commit [prefix] (4 to 64 hexadecimal characters) names in the repository at
     * [root]; `null` if it names none or several (ambiguous), or if git is not available, fails or takes longer than
     * 3 s.
     */
    internal fun commitOf(
        root: Path,
        prefix: String,
    ): String? {
        if (!available || !HASH_PREFIX.matches(prefix)) return null
        // -q: an unknown or ambiguous prefix fails quietly.
        val arguments = listOf("--no-optional-locks", "rev-parse", "--verify", "-q", "$prefix^{commit}")
        return run(arguments, root, 3.seconds)?.trim()?.ifEmpty { null }
    }

    /**
     * The `user.name` git uses in the repository at [root], trimmed; `null` if it is not set, or if git is not
     * available, fails or takes longer than 3 s.
     */
    internal fun userName(root: Path): String? {
        if (!available) return null
        return run(listOf("--no-optional-locks", "config", "--get", "user.name"), root, 3.seconds)
            ?.trim()
            ?.ifEmpty { null }
    }

    /**
     * The output of `git ls-tree -r -t -z` for the tree of [commit] (a full object name) in the repository at
     * [root], each record `<mode> <type> <object>\t<path>` ended by NUL, the path unquoted; `null` if git is not
     * available, fails or takes longer than 10 s.
     */
    internal fun treePaths(
        root: Path,
        commit: String,
    ): String? {
        if (!available) return null
        val arguments =
            listOf(
                "--no-optional-locks",
                "ls-tree",
                "-r",
                // Folders too, before what they hold.
                "-t",
                // Unquoted paths; a --format would quote them even with -z.
                "-z",
                commit,
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
     * The full refnames of the local branches of the repository at [root], one per line (`refs/heads/main`); `null`
     * if git is not available, fails or takes longer than 3 s.
     */
    internal fun localBranches(root: Path): String? {
        if (!available) return null
        return run(listOf("--no-optional-locks", "for-each-ref", "--format=%(refname)", "refs/heads"), root, 3.seconds)
    }

    /**
     * The commit the local branch [name] (short name) points at, [GitBranchTip.Missing] if there is no such branch, or
     * [GitBranchTip.Unknown] if git is not available, fails or takes longer than 3 s: `git for-each-ref`, which succeeds
     * without output for a missing branch.
     */
    internal fun branchTip(
        root: Path,
        name: String,
    ): GitBranchTip {
        if (!available) return GitBranchTip.Unknown
        val ref = "refs/heads/$name"
        // The pattern also matches the branches in a folder of that name (refs/heads/<name>/…): only the exact one counts.
        val arguments = listOf("--no-optional-locks", "for-each-ref", "--format=%(refname)%1f%(objectname)", ref)
        val output = run(arguments, root, 3.seconds) ?: return GitBranchTip.Unknown
        val commit =
            output
                .lineSequence()
                .map { it.split('\u001f') }
                .firstOrNull { it.size == 2 && it[0] == ref }
                ?.get(1)
        return if (commit == null) GitBranchTip.Missing else GitBranchTip.At(commit)
    }

    /**
     * The full object name of the commit `HEAD` points at in the repository at [root]; `null` if there is none yet
     * (an unborn branch), or if git is not available, fails or takes longer than 3 s.
     */
    internal fun headCommit(root: Path): String? = commitOfRevision(root, "HEAD")

    /**
     * The full object name of the commit [revision] (`HEAD`, a hash or a full refname, also of an annotated tag) names
     * in the repository at [root]; `null` if it names none, or if git is not available, fails or takes longer than 3 s.
     */
    internal fun commitOfRevision(
        root: Path,
        revision: String,
    ): String? {
        if (!available || revision.startsWith('-')) return null
        // -q: an unborn HEAD or a missing revision fails quietly instead of with an error message.
        val arguments = listOf("--no-optional-locks", "rev-parse", "--verify", "-q", "$revision^{commit}")
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
     * The output of `git diff-tree -r -z --name-status -M` for the files [commit] changed against [parent], or for a
     * root commit (`null` [parent]) against nothing (see [GitNameStatusParser]); `null` if git is not available, fails
     * or takes longer than 10 s. Both are full object names.
     */
    internal fun changedFiles(
        root: Path,
        commit: String,
        parent: String?,
    ): String? {
        if (!available) return null
        val revisions = if (parent == null) listOf("--root", commit) else listOf(parent, commit)
        val arguments =
            listOf(
                "--no-optional-locks",
                "diff-tree",
                "-r",
                "-z",
                "--no-commit-id",
                "--name-status",
                "-M",
                "--no-ext-diff",
            ) + revisions + "--"
        return run(arguments, root, 10.seconds)
    }

    /**
     * The output of `git cat-file --batch-check` for the [objects] (such as `<commit>:<path>`), one line each:
     * `<name> <type> <size>`, or `<object> missing`; `null` if git is not available, fails or takes longer than 10 s.
     * The names are given on the standard input, so paths reach git as UTF-8 whatever the JVM's locale; `null` for
     * a name with a line break, which git would read as two.
     */
    internal fun objectInfo(
        root: Path,
        objects: List<String>,
    ): String? {
        if (!available || objects.any { '\n' in it }) return null
        val input = objects.joinToString("") { "$it\n" }.toByteArray(Charsets.UTF_8)
        return runBytes(listOf("--no-optional-locks", "cat-file", "--batch-check"), root, 10.seconds, input)
            ?.toString(Charsets.UTF_8)
    }

    /**
     * The raw content of the blob [name] (a full object name), without filters; `null` if git is not available, fails
     * or takes longer than 10 s.
     */
    internal fun blob(
        root: Path,
        name: String,
    ): ByteArray? {
        if (!available) return null
        return runBytes(listOf("--no-optional-locks", "cat-file", "blob", name), root, 10.seconds)
    }

    /**
     * The output of `git diff -U0` from the blob [original] to the blob [current] (full object names, see
     * [GitDiffParser]), or `null` if git is not available, fails or takes longer than 10 s.
     */
    internal fun diffBlobs(
        root: Path,
        original: String,
        current: String,
    ): String? {
        if (!available) return null
        val arguments =
            listOf(
                "--no-optional-locks",
                "diff",
                "--no-color",
                "--no-ext-diff",
                "--no-textconv",
                "-U0",
                original,
                current,
            )
        return run(arguments, root, 10.seconds)
    }

    /**
     * Options of git itself for a log with [filter]: paths are literal paths, not patterns; `log.follow` of the
     * user's configuration would make a single path follow renames, with parents that are not rewritten.
     */
    private fun globalLogOptions(filter: GitLogFilter): List<String> =
        listOf("--no-optional-locks", "-c", "log.follow=false") +
            if (filter.paths.isEmpty()) emptyList() else listOf("--literal-pathspecs")

    /** The format of the commits [GitLogParser] reads. */
    private fun logOutputOptions() =
        listOf(
            // Authors as mapped by .mailmap, for --author too, whatever log.mailmap says.
            "--use-mailmap",
            "-z",
            "--no-color",
            "--no-show-signature",
            // The output is read as UTF-8, whatever i18n.logOutputEncoding says.
            "--encoding=UTF-8",
            // %aN and %aE: name and email as mapped by .mailmap.
            "--format=%H%x1f%P%x1f%aN%x1f%aE%x1f%at%x1f%s",
        )

    /**
     * The standard input of a log for [revisions] and [paths]: one revision per line, then `--` and one path per
     * line; empty without either. `null` if one has a line break (git would read two) or a revision starts with `-`
     * (git would read an option), or if it is larger than [MAX_INPUT] (see there).
     */
    private fun logInput(
        revisions: List<String>,
        paths: List<String>,
    ): ByteArray? {
        if ((revisions + paths).any { '\n' in it } || revisions.any { it.startsWith('-') }) return null
        if (revisions.isEmpty() && paths.isEmpty()) return ByteArray(0)
        val lines = revisions + if (paths.isEmpty()) emptyList() else listOf("--") + paths
        val input = lines.joinToString("") { "$it\n" }.toByteArray(Charsets.UTF_8)
        return input.takeIf { it.size <= MAX_INPUT }
    }

    /**
     * The pattern options of a log (ADR 0039): always extended regular expressions; [text] (if not empty) matching
     * the message, escaped unless [regex], ignoring case unless [matchCase]; for every one of the [users] its name or
     * email, exactly but ignoring case whatever [matchCase] says. Characters the JVM cannot pass as arguments are
     * replaced ([encodableArgument]).
     */
    private fun patterns(
        text: String,
        regex: Boolean,
        matchCase: Boolean,
        users: List<String>,
    ): List<String> {
        val authors = users.map(String::trim).filter(String::isNotEmpty).flatMap(::authorPatterns)
        if (text.isEmpty() && authors.isEmpty()) return emptyList()
        return buildList {
            add("--extended-regexp")
            if (text.isNotEmpty() && !matchCase) add("--regexp-ignore-case")
            if (text.isNotEmpty()) add("--grep=" + encodableArgument(if (regex) text else escapeRegex(text)))
            // Git matches them against "Name <email> timestamp zone" and ORs them, but ANDs them with --grep.
            authors.forEach { add("--author=" + encodableArgument(it)) }
        }
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
    ): String? = runBytes(arguments, directory, timeout)?.toString(Charsets.UTF_8)

    /**
     * Runs git with [arguments] in [directory], [input] on its standard input; its output if it succeeds in time.
     * Reads take no optional locks and drop what git says on its standard error.
     */
    private fun runBytes(
        arguments: List<String>,
        directory: Path?,
        timeout: Duration,
        input: ByteArray? = null,
    ): ByteArray? {
        val execution = execute(arguments, directory, timeout, input, write = false) ?: return null
        // An output that may have been cut off is no answer.
        return if (execution.exitValue == 0 && execution.complete) execution.output else null
    }

    /**
     * Runs git with [arguments] in the repository at [root] to change it (ADR 0041), [input] on its standard input:
     * with optional locks, unlike the reads, as git itself would run; within [timeout], as hooks may run; never with an
     * editor (`GIT_EDITOR=false`: git fails at once where it would open one). The result has git's own message: its
     * standard error, or its standard output if that is empty, trimmed and without git's `hint:` lines, which point at
     * command lines (unless there is nothing else).
     */
    internal fun write(
        root: Path,
        arguments: List<String>,
        input: ByteArray? = null,
        timeout: Duration = WRITE_TIMEOUT,
    ): GitResult {
        if (!available) return GitResult(false, "git is not available.")
        val execution =
            execute(arguments, root, timeout, input, write = true)
                ?: return GitResult(false, "git could not be run.")
        val exitValue = execution.exitValue ?: return GitResult(false, "git did not finish within ${shown(timeout)}.")
        val message =
            withoutHints(
                execution.errors
                    .toString(Charsets.UTF_8)
                    .trim()
                    .ifEmpty { execution.output.toString(Charsets.UTF_8).trim() },
            )
        return when {
            exitValue == 0 -> GitResult(true, message)
            message.isEmpty() -> GitResult(false, "git failed with exit code $exitValue.")
            else -> GitResult(false, message)
        }
    }

    /**
     * Whether the commit [ancestor] is an ancestor of (or the same as) [descendant] in the repository at [root] (full
     * hashes or refnames): `git merge-base --is-ancestor`; `null` if git is not available, fails or takes longer than
     * 10 s.
     */
    internal fun isAncestor(
        root: Path,
        ancestor: String,
        descendant: String,
    ): Boolean? {
        if (!available) return null
        val arguments =
            listOf("--no-optional-locks", "merge-base", "--is-ancestor", "--end-of-options", ancestor, descendant)
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
     * a few lines, well below the pipe's buffer, or read by git at once (a tag message).
     */
    private fun execute(
        arguments: List<String>,
        directory: Path?,
        timeout: Duration,
        input: ByteArray?,
        write: Boolean,
    ): Execution? {
        val process =
            try {
                ProcessBuilder(listOf(executable) + arguments)
                    .directory(directory?.toFile())
                    .apply {
                        if (!write) redirectError(ProcessBuilder.Redirect.DISCARD)
                        environment().clear()
                        environment().putAll(this@GitCli.environment)
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
        val readers =
            listOfNotNull(
                Thread.ofVirtual().start { process.inputStream.use { it.transferTo(output) } },
                if (write) Thread.ofVirtual().start { process.errorStream.use { it.transferTo(errors) } } else null,
            )
        try {
            process.outputStream.use { stdin -> input?.let(stdin::write) }
        } catch (e: IOException) {
            // Git ended without reading all its input; its exit value tells.
            log.log(System.Logger.Level.DEBUG, "Cannot write to $executable", e)
        }
        try {
            if (!process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
                stop(process, write)
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
        return Execution(process.exitValue(), output.toByteArray(), errors.toByteArray(), readers.none(Thread::isAlive))
    }

    /**
     * Kills git and whatever it started (hooks, helpers); the descendants first, as they are lost with git. A [write]
     * is asked to end first (SIGTERM, on which git removes its lock files) and killed only if it has not after a
     * moment.
     */
    private fun stop(
        process: Process,
        write: Boolean,
    ) {
        if (write) {
            val interrupted = Thread.interrupted()
            try {
                process.descendants().forEach { it.destroy() }
                process.destroy()
                process.waitFor(STOP_GRACE.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
    }

    /**
     * A run of git that ended: its [exitValue] (`null` if it took too long and was stopped) and what it wrote, read to
     * its end if [complete].
     */
    private class Execution(
        val exitValue: Int?,
        val output: ByteArray,
        val errors: ByteArray,
        val complete: Boolean,
    )

    internal companion object {
        private val HASH_PREFIX = Regex("[0-9a-fA-F]{4,64}")

        /** How long a git command that changes the repository may take (ADR 0041); hooks may run. */
        val WRITE_TIMEOUT = 60.seconds

        /** How long a stopped write may take to end by itself before it is killed. */
        private val STOP_GRACE = 2.seconds

        /** How long the output of a git that has ended may take to be read to its end, whatever the timeout. */
        private val PIPE_GRACE = 2.seconds

        /**
         * The most bytes written to git's standard input. They are written while git runs (its output is read in
         * parallel), but the write blocks while git does not read and is not covered by the timeout ([execute]); below
         * the smallest default pipe buffer (16 KiB on macOS) it never blocks.
         */
        private const val MAX_INPUT = 15 * 1024
    }
}

/** The characters with a meaning in a POSIX extended regular expression. */
private const val REGEX_SPECIAL = ".[]()*+?{}|^$\\"

/** [text] as a POSIX extended regular expression that matches it literally. */
internal fun escapeRegex(text: String): String =
    buildString { for (char in text) append(if (char in REGEX_SPECIAL) "\\$char" else char) }

/**
 * The patterns that match an author whose name or email is [value], ignoring case by turning ASCII letters into
 * bracket expressions (`[Aa]`) and escaping the rest: the name from the start of git's `Name <email> …` up to ` <`,
 * the email between `<` and `>`.
 */
internal fun authorPatterns(value: String): List<String> {
    val pattern =
        buildString {
            for (char in value) {
                when {
                    char in 'a'..'z' || char in 'A'..'Z' -> {
                        append(
                            '[',
                        ).append(char.uppercaseChar()).append(char.lowercaseChar()).append(']')
                    }

                    char in REGEX_SPECIAL -> {
                        append('\\').append(char)
                    }

                    else -> {
                        append(char)
                    }
                }
            }
        }
    return listOf("^$pattern <", "<$pattern>")
}

/** The charset the JVM turns process arguments into bytes with. */
internal val argumentCharset: Charset =
    System.getProperty("sun.jnu.encoding")?.let { runCatching { Charset.forName(it) }.getOrNull() }
        ?: Charset.defaultCharset()

/**
 * [pattern] as a process argument for a regular expression of git (ADR 0039): every character [charset] cannot
 * encode, which the JVM would turn into `?`, becomes `.` once per byte of its UTF-8 encoding. In such a locale git
 * matches byte by byte, so it still matches the character (and a little more). Inside a bracket expression it does
 * not work.
 */
internal fun encodableArgument(
    pattern: String,
    charset: Charset = argumentCharset,
): String {
    val encoder = charset.newEncoder()
    if (encoder.canEncode(pattern)) return pattern
    return buildString {
        var index = 0
        while (index < pattern.length) {
            val end = pattern.offsetByCodePoints(index, 1)
            val character = pattern.substring(index, end)
            if (encoder.canEncode(character)) {
                append(character)
            } else {
                repeat(character.toByteArray(Charsets.UTF_8).size) { append('.') }
            }
            index = end
        }
    }
}

/**
 * Whether [argument] reaches git unchanged as a process argument: [charset], the one the JVM encodes arguments with,
 * can encode it. In a plain ASCII (POSIX) locale the JVM would turn other characters into `?` (ADR 0038).
 */
internal fun canPassArgument(
    argument: String,
    charset: Charset = argumentCharset,
): Boolean = charset.newEncoder().canEncode(argument)

/** [message] without git's `hint:` lines; [message] itself if it has nothing else. */
internal fun withoutHints(message: String): String =
    message
        .lines()
        .filterNot { it.startsWith("hint:") }
        .joinToString("\n")
        .trim()
        .ifEmpty { message }

/** [duration] as a message says it: "60 s", "90 s", "10 min". */
internal fun shown(duration: Duration): String =
    if (duration > 1.minutes && duration.inWholeSeconds % 60 == 0L) {
        "${duration.inWholeMinutes} min"
    } else {
        "${duration.inWholeSeconds} s"
    }
