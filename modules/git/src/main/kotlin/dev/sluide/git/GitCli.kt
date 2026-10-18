package dev.sluide.git

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.CompletableFuture
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
     * The commits of the refs matching [excludedGlobs] (`--not --glob=<pattern>`, a pattern with `*`) are left out, as
     * those of the filter's excluded revisions, but without their number being limited by the standard input.
     */
    internal fun log(
        root: Path,
        filter: GitLogFilter,
        skip: Int,
        limit: Int,
        excludedGlobs: List<String> = emptyList(),
    ): String? {
        if (!available) return null
        // Revisions and paths go to git's standard input, so they reach it as UTF-8 whatever the JVM's locale.
        val input = logInput(filter.revisions, filter.paths, filter.excluded) ?: return null
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
                addAll(limitingOptions(filter))
                // %P then gives the parents as rewritten for the paths: the nearest commits shown.
                if (filter.paths.isNotEmpty()) add("--parents")
                // The revisions on the standard input keep their own flags: --not does not reach them.
                if (excludedGlobs.isNotEmpty()) addAll(listOf("--not") + excludedGlobs.map { "--glob=$it" })
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
     * The output of `git log` for the commit [hash] alone (see [GitLogParser]) if it passes the users, paths and no
     * merges of [filter] (not its text and revisions); empty if it does not. `null` if git is not available, fails or
     * takes longer than 10 s, or for paths as [log] refuses them.
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
                add("log")
                if (filter.noMerges) add("--no-merges")
                addAll(patterns("", regex = false, matchCase = false, users = filter.users))
                if (input.isNotEmpty()) add("--stdin")
                addAll(logOutputOptions())
                // The commit without its parents, not --no-walk: git 2.55 ignores the paths with --no-walk.
                addAll(listOf(hash, "^$hash^@"))
                add("--")
            }
        return runBytes(arguments, root, 10.seconds, input.takeIf { it.isNotEmpty() })?.toString(Charsets.UTF_8)
    }

    /**
     * The output of `git log --follow --name-status` for the file [path] from the revision [start] on, at most [limit]
     * commits (see [GitFileHistoryParser], ADR 0046): every record starts with U+001E, then the commit as [log] gives
     * it and, after a line break, the file's status and paths at that commit. The revision and the path go to git's
     * standard input, so they reach it as UTF-8 whatever the JVM's locale; git takes the single pathspec `--follow`
     * needs from there. An unborn `HEAD` gives an empty output (`--ignore-missing`). `--follow` turns off git's pruning
     * by paths (and its changed-path Bloom filters), so it walks every commit: 60 s at most. A [cancellation] stops git.
     * `null` if git is not available, fails, takes longer or is cancelled, or for a revision or path [logInput] refuses.
     */
    internal fun fileHistory(
        root: Path,
        path: String,
        start: String,
        limit: Int,
        cancellation: GitCancellation? = null,
    ): String? {
        if (!available) return null
        val input = logInput(listOf(start), listOf(path)) ?: return null
        val arguments =
            buildList {
                // The path is a file's name, not a pattern.
                addAll(listOf("--no-optional-locks", "--literal-pathspecs", "log", "--follow"))
                // Renames detected whatever diff.renames says.
                addAll(listOf("-M", "--name-status", "--ignore-missing", "--stdin"))
                // The root commit adds the file, whatever log.showRoot says.
                add("--root")
                addAll(logOutputOptions(prefix = "%x1e"))
                add("--max-count=$limit")
                add("--")
            }
        return runBytes(arguments, root, 60.seconds, input, cancellation)?.toString(Charsets.UTF_8)
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
     * The names of the remotes of the repository at [root] (`git remote`), in git's order; `null` if git is not
     * available, fails or takes longer than 3 s. Git refuses line breaks in remote names, so one per line is safe.
     */
    internal fun remoteNames(root: Path): List<String>? {
        if (!available) return null
        return run(listOf("--no-optional-locks", "remote"), root, 3.seconds)?.lines()?.filter(String::isNotEmpty)
    }

    /**
     * The remotes of the repository at [root] that `git fetch --all` leaves out: `remote.<name>.skipFetchAll` true, its
     * last value counting as for git (a repository's `false` after a global `true`); `null` if git is not available,
     * fails (also for a value that is not a boolean) or takes longer than 3 s.
     */
    internal fun remotesSkippedByFetchAll(root: Path): Set<String>? {
        if (!available) return null
        val arguments =
            listOf("--no-optional-locks", "config", "-z", "--type=bool", "--get-regexp", "^remote\\..*\\.skipfetchall$")
        val execution = execute(arguments, root, 3.seconds, null, write = false) ?: return null
        if (execution.exitValue == 1) return emptySet()
        if (execution.exitValue != 0 || !execution.complete) return null
        // <key>\n<value> NUL in the order git reads them, the last one counting; the variable's name in lower case, the
        // remote's as written.
        return execution.output
            .toString(Charsets.UTF_8)
            .split('\u0000')
            .filter { '\n' in it }
            .associate { it.substringBefore('\n') to it.substringAfter('\n') }
            .filterValues { it == "true" }
            .keys
            .map { it.removePrefix("remote.").removeSuffix(".skipfetchall") }
            .toSet()
    }

    /**
     * All values of the configuration variable [key] in the repository at [root], in the order git reads them (`git
     * config --get-all`); empty if it is not set, `null` if git is not available, fails or takes longer than 3 s.
     */
    internal fun configValues(
        root: Path,
        key: String,
    ): List<String>? {
        if (!available) return null
        val arguments = listOf("--no-optional-locks", "config", "-z", "--get-all", "--end-of-options", key)
        val execution = execute(arguments, root, 3.seconds, null, write = false) ?: return null
        if (execution.exitValue == 1) return emptyList()
        if (execution.exitValue != 0 || !execution.complete) return null
        return execution.output
            .toString(Charsets.UTF_8)
            .split('\u0000')
            .dropLast(1)
    }

    /**
     * The boolean configuration variable [key] in the repository at [root] as git reads it (`git config --type=bool
     * --get`: also a value-less entry or any number); `false` if it is not set, `null` if git is not available, fails
     * (also for a value that is not a boolean) or takes longer than 3 s.
     */
    internal fun configFlag(
        root: Path,
        key: String,
    ): Boolean? {
        if (!available) return null
        val arguments = listOf("--no-optional-locks", "config", "--type=bool", "--get", "--end-of-options", key)
        val execution = execute(arguments, root, 3.seconds, null, write = false) ?: return null
        if (execution.exitValue == 1) return false
        if (execution.exitValue != 0 || !execution.complete) return null
        return execution.output.toString(Charsets.UTF_8).trim() == "true"
    }

    /**
     * The configuration git sees in the repository at [root], in the order it reads it, with `include` and `includeIf`
     * files followed and where each entry comes from: `git config --list --includes --show-scope --show-origin -z`,
     * which runs nothing of the repository's (ADR 0053); `null` if git is not available, fails or takes longer than
     * 3 s.
     */
    internal fun configEntries(root: Path): List<ConfigEntry>? {
        if (!available) return null
        val arguments =
            listOf("--no-optional-locks", "config", "--list", "--includes", "--show-scope", "--show-origin", "-z")
        val execution = execute(arguments, root, 3.seconds, null, write = false, guardFsmonitor = false) ?: return null
        if (execution.exitValue != 0 || !execution.complete) return null
        // Per entry: scope, origin, then key and value separated by a newline (just the key for one without "=").
        val fields =
            execution.output
                .toString(Charsets.UTF_8)
                .split('\u0000')
                .dropLast(1)
        if (fields.size % 3 != 0) return null
        return fields.chunked(3).map { (scope, origin, entry) ->
            ConfigEntry(
                scope,
                origin,
                entry.substringBefore('\n'),
                entry.substringAfter('\n', "").takeIf {
                    '\n' in
                        entry
                },
            )
        }
    }

    /**
     * The top level of the work tree git sees for [root] (`git rev-parse --show-toplevel`, which follows `core.worktree`),
     * with links resolved; `null` if git is not available, fails, has no work tree there, or the path cannot be read.
     */
    internal fun topLevel(root: Path): Path? {
        if (!available) return null
        val line = run(listOf("--no-optional-locks", "rev-parse", "--show-toplevel"), root, 3.seconds)?.trimEnd('\n')
        if (line.isNullOrEmpty()) return null
        return try {
            root.resolve(line).toRealPath()
        } catch (_: InvalidPathException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    /**
     * The paths of the submodules in the index of the repository at [root] (gitlinks, mode 160000), relative to it:
     * `git ls-files --stage -z`; `null` if git is not available, fails or takes longer than 10 s.
     */
    internal fun gitlinks(root: Path): List<String>? {
        if (!available) return null
        val output = run(listOf("--no-optional-locks", "ls-files", "--stage", "-z"), root, 10.seconds) ?: return null
        // Per entry: mode, object, stage, a tab, the path.
        return output
            .split('\u0000')
            .filter { it.startsWith("160000 ") && '\t' in it }
            .map { it.substringAfter('\t') }
            .distinct()
    }

    /**
     * Whether the user configured how git runs ssh in the repository at [root]: `core.sshCommand` or `ssh.variant`
     * (also given in the environment, `GIT_CONFIG_PARAMETERS`); `null` if git is not available, fails or takes longer
     * than 3 s.
     */
    internal fun sshConfigured(root: Path): Boolean? {
        if (!available) return null
        val arguments = listOf("--no-optional-locks", "config", "--get-regexp", "^(core\\.sshcommand|ssh\\.variant)$")
        return when (execute(arguments, root, 3.seconds, null, write = false)?.exitValue) {
            0 -> true
            1 -> false
            else -> null
        }
    }

    /**
     * The objects the refs starting with [prefixes] (such as `refs/tags/`) of the repository at [root] point at, by
     * full refname, without symbolic refs (`refs/remotes/origin/HEAD`); `null` if git is not available, fails or takes
     * longer than 10 s.
     */
    internal fun refTips(
        root: Path,
        prefixes: List<String>,
    ): Map<String, String>? {
        if (!available) return null
        val arguments =
            listOf("--no-optional-locks", "for-each-ref", "--format=%(refname)%1f%(objectname)%1f%(symref)") + prefixes
        val output = run(arguments, root, 10.seconds) ?: return null
        return output
            .lineSequence()
            .map { it.split('\u001f') }
            .filter { it.size == 3 && it[2].isEmpty() }
            .associate { it[0] to it[1] }
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
     * The output of `git cat-file --batch-check` for two lines per revision of [revisions] (ADR 0045): the commit it
     * names (`<revision>^{commit}`), then the revision itself, which tells an ambiguous short hash from an unknown one
     * (see [GitRevisionParser]). The revisions are given on the standard input, so any characters reach git as UTF-8;
     * `null` for a revision with a line break, for more input than git is safely given (about 15 KB), or if git is not
     * available, fails (e.g. `@{upstream}` without one) or takes longer than 10 s.
     */
    internal fun resolveRevisions(
        root: Path,
        revisions: List<String>,
    ): String? {
        if (!available || revisions.any { '\n' in it }) return null
        val input = revisions.joinToString("") { "$it^{commit}\n$it\n" }.toByteArray(Charsets.UTF_8)
        if (input.size > MAX_INPUT) return null
        return runBytes(listOf("--no-optional-locks", "cat-file", "--batch-check"), root, 10.seconds, input)
            ?.toString(Charsets.UTF_8)
    }

    /**
     * The output of `git for-each-ref --contains` for the local and remote branches that contain the commit [hash] (a
     * full object name) in the repository at [root], one `<refname>\u001f<symref>` per line (ADR 0045); `null` if git
     * is not available, fails or takes longer than 10 s.
     */
    internal fun branchesContaining(
        root: Path,
        hash: String,
    ): String? {
        if (!available) return null
        val arguments =
            listOf(
                "--no-optional-locks",
                "for-each-ref",
                // Attached, so the hash can never be read as an option or a pattern.
                "--contains=$hash",
                // %(symref): the target of a symbolic ref such as refs/remotes/origin/HEAD, which is skipped.
                "--format=%(refname)%1f%(symref)",
                "refs/heads",
                "refs/remotes",
            )
        return run(arguments, root, 10.seconds)
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
     * How many commits that pass [filter] (its text, users, paths, first parents only and no merges; not its revisions)
     * are reachable from [left] but not from [right], and the other way round (ADR 0044): `git log --left-right
     * --format=%m` with the limiting options of [log] (`rev-list --count` does not take `--use-mailmap`), the symmetric
     * range `<left>...<right>` and the paths on the standard input, so that they reach git as UTF-8 whatever the JVM's
     * locale. `null` if git is not available, fails (also for a revision that names no commit) or takes longer than
     * 10 s, or for revisions and paths as [log] refuses them.
     */
    internal fun comparisonCounts(
        root: Path,
        left: String,
        right: String,
        filter: GitLogFilter = GitLogFilter(),
    ): Pair<Int, Int>? {
        if (!available) return null
        val input = logInput(listOf("$left...$right"), filter.paths) ?: return null
        val arguments =
            globalLogOptions(filter) +
                listOf("log", "--left-right") +
                limitingOptions(filter) +
                listOf("--use-mailmap", "--no-color", "--format=%m", "--stdin", "--")
        val marks = runBytes(arguments, root, 10.seconds, input)?.toString(Charsets.UTF_8) ?: return null
        return marks.count { it == '<' } to marks.count { it == '>' }
    }

    /**
     * The content of the blob [name] (a full object name) with the filters (line endings, smudge) a checkout of it at
     * [path] would apply (ADR 0044): `git cat-file --batch --filters`, the name and path on the standard input, so the
     * path reaches git as UTF-8 whatever the JVM's locale. A smudge filter (e.g. git-lfs) may make it much larger than
     * the stored blob: above [maxSize] bytes the reading stops, git and the filter are killed, and the result is
     * [FilteredBlob.TooLarge]. Git holds a filter's whole output before it writes any of it, so a filter that does not
     * end (or fetches over the network) is only stopped by the timeout. `null` if git is not available, fails or takes
     * longer than 10 s, or for a path with a line break.
     */
    internal fun filteredBlob(
        root: Path,
        name: String,
        path: String,
        maxSize: Long,
    ): FilteredBlob? {
        if (!available || '\n' in path) return null
        // The path is what follows the object name; the size git prints is the one before the filters.
        val input = "$name $path\n".toByteArray(Charsets.UTF_8)
        val arguments = listOf("--no-optional-locks", "cat-file", "--batch", "--filters")
        // The header line, the content and a line break.
        val limit = maxSize + name.length + BLOB_HEADER_SLACK
        val execution =
            execute(arguments, root, 10.seconds, input, write = false, maxOutput = limit) ?: return null
        if (execution.overflowed) return FilteredBlob.TooLarge
        if (execution.exitValue != 0 || !execution.complete) return null
        val output = execution.output
        val header = output.indexOf('\n'.code.toByte())
        if (header < 0 || output.size < header + 2 || output.last() != '\n'.code.toByte()) return null
        val line = output.copyOfRange(0, header).toString(Charsets.UTF_8)
        if (!line.startsWith("$name blob ")) return null
        val content = output.copyOfRange(header + 1, output.size - 1)
        return if (content.size > maxSize) FilteredBlob.TooLarge else FilteredBlob.Content(content)
    }

    /**
     * The output of `git diff -z --name-status -M` between [commit] (a full object name) and the working tree at [root]
     * (ADR 0044), for the tracked files (see [GitNameStatusParser]); `null` if git is not available, fails or takes
     * longer than 10 s.
     */
    internal fun workingTreeChanges(
        root: Path,
        commit: String,
    ): String? {
        if (!available) return null
        val arguments =
            listOf(
                "--no-optional-locks",
                "diff",
                "-z",
                "--name-status",
                "-M",
                "--no-color",
                "--no-ext-diff",
                commit,
                "--",
            )
        return run(arguments, root, 10.seconds)
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
     * The parents of [commit] (a full hash) in the repository at [root], full hashes in git's order: `git rev-list
     * --parents -n 1`; empty for a root commit, `null` if git is not available, fails or takes longer than 3 s.
     */
    internal fun parents(
        root: Path,
        commit: String,
    ): List<String>? {
        if (!available || commit.startsWith('-')) return null
        val arguments = listOf("--no-optional-locks", "rev-list", "--parents", "-n", "1", commit, "--")
        val fields = run(arguments, root, 3.seconds)?.trim()?.split(' ') ?: return null
        return if (fields.firstOrNull() == commit) fields.drop(1) else null
    }

    /**
     * The empty tree of the repository at [root] (`git hash-object -t tree --stdin` with nothing on standard input, so
     * also for SHA-256 repositories), which git knows without storing it; `null` if git is not available or fails.
     */
    internal fun emptyTree(root: Path): String? {
        if (!available) return null
        val arguments = listOf("--no-optional-locks", "hash-object", "-t", "tree", "--stdin")
        return runBytes(arguments, root, 3.seconds, input = ByteArray(0))?.toString(Charsets.UTF_8)?.trim()
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

    /**
     * The full message of the commit `HEAD` (`git show -s --format=%B`, as stored, with the newline git adds); `null` if
     * there is no commit, or if git is not available, fails or takes longer than 3 s.
     */
    internal fun headMessage(root: Path): String? {
        if (!available) return null
        val arguments =
            listOf(
                "--no-optional-locks",
                "show",
                "-s",
                "--no-color",
                // No gpg lines (log.showSignature), and UTF-8 whatever i18n.logOutputEncoding says.
                "--no-show-signature",
                "--encoding=UTF-8",
                "--format=%B",
                "HEAD",
                "--",
            )
        return run(arguments, root, 3.seconds)
    }

    /**
     * Whether `HEAD` of the repository at [root] is unborn: a symbolic ref to a branch without commits (`git rev-parse
     * -q --verify` exits with 1). `false` if there is a commit; `null` if git is not available or fails in any other
     * way, or `HEAD` is detached without a commit.
     */
    internal fun isHeadUnborn(root: Path): Boolean? {
        if (!available) return null
        val arguments = listOf("--no-optional-locks", "rev-parse", "-q", "--verify", "HEAD^{commit}")
        return when (execute(arguments, root, 3.seconds, null, write = false)?.exitValue) {
            0 -> false
            1 -> if (currentBranch(root) != null) true else null
            else -> null
        }
    }

    /**
     * The output of `git diff --no-index --text -U0` from the file [original] to the file [current] in [folder] (plain
     * names relative to it; see [GitDiffParser]), the texts compared as they are (ADR 0044): run in [folder], with the
     * parent folders not searched for a repository, no attributes (an empty `core.attributesFile`, no system ones) and
     * no line-ending conversion, as the texts are both in their checkout form already. [algorithm] is the
     * `diff.algorithm` to use, e.g. the repository's. `null` if git is not available, fails (also exit value 1 without a
     * hunk, which is how it fails to read a file) or takes longer than 10 s.
     */
    internal fun diffFiles(
        folder: Path,
        original: String,
        current: String,
        algorithm: String?,
    ): String? {
        if (!available) return null
        val noAttributes = folder.resolve("attributes")
        try {
            Files.write(noAttributes, ByteArray(0))
        } catch (_: IOException) {
            return null
        }
        val arguments =
            buildList {
                addAll(listOf("--no-optional-locks", "-c", "core.autocrlf=false", "-c", "core.eol=lf"))
                addAll(listOf("-c", "core.attributesFile=attributes"))
                if (algorithm != null) addAll(listOf("-c", "diff.algorithm=$algorithm"))
                addAll(listOf("diff", "--no-index", "--text", "--no-color", "--no-ext-diff", "--no-textconv", "-U0"))
                addAll(listOf("--", original, current))
            }
        val outside =
            listOfNotNull(
                folder
                    .toAbsolutePath()
                    .parent
                    ?.toString()
                    ?.let { "GIT_CEILING_DIRECTORIES" to it },
                "GIT_ATTR_NOSYSTEM" to "1",
            ).toMap()
        // Without an index there is no fsmonitor to guard against, and the folder is a new one every time.
        val execution =
            execute(arguments, folder, 10.seconds, null, write = false, outside, guardFsmonitor = false) ?: return null
        if (!execution.complete) return null
        val output = execution.output.toString(Charsets.UTF_8)
        // Without an index, git exits with 1 when the files differ, but also when it cannot read one.
        return when (execution.exitValue) {
            0 -> output
            1 -> output.takeIf { it.contains("\n@@ ") }
            else -> null
        }
    }

    /**
     * Options of git itself for a log with [filter]: paths are literal paths, not patterns; `log.follow` of the
     * user's configuration would make a single path follow renames, with parents that are not rewritten.
     */
    private fun globalLogOptions(filter: GitLogFilter): List<String> =
        listOf("--no-optional-locks", "-c", "log.follow=false") +
            if (filter.paths.isEmpty()) emptyList() else listOf("--literal-pathspecs")

    /**
     * The options of a log that limit its commits beyond its revisions (ADR 0039): first parents only, no merges, the
     * text and the users; shared by [log] and [comparisonCounts], so that the counts match the list.
     */
    private fun limitingOptions(filter: GitLogFilter): List<String> =
        buildList {
            if (filter.firstParent) add("--first-parent")
            if (filter.noMerges) add("--no-merges")
            addAll(patterns(filter.text.trim(), filter.regex, filter.matchCase, filter.users))
        }

    /** The format of the commits [GitLogParser] reads, each record started by [prefix] (ADR 0046). */
    private fun logOutputOptions(prefix: String = "") =
        listOf(
            // Authors as mapped by .mailmap, for --author too, whatever log.mailmap says.
            "--use-mailmap",
            "-z",
            "--no-color",
            "--no-show-signature",
            // The output is read as UTF-8, whatever i18n.logOutputEncoding says.
            "--encoding=UTF-8",
            // %aN and %aE: name and email as mapped by .mailmap.
            "--format=$prefix%H%x1f%P%x1f%aN%x1f%aE%x1f%at%x1f%s",
        )

    /**
     * The standard input of a log for [revisions], [excluded] revisions and [paths]: one revision per line, the
     * excluded ones as `^<rev>` (ADR 0044), then `--` and one path per line; empty without any. `null` if one has a line
     * break (git would read two) or a revision starts with `-` (git would read an option), or if it is larger than
     * [MAX_INPUT] (see there).
     */
    private fun logInput(
        revisions: List<String>,
        paths: List<String>,
        excluded: List<String> = emptyList(),
    ): ByteArray? {
        val allRevisions = revisions + excluded
        if ((allRevisions + paths).any { '\n' in it } || allRevisions.any { it.startsWith('-') }) return null
        if (allRevisions.isEmpty() && paths.isEmpty()) return ByteArray(0)
        val lines =
            revisions + excluded.map { "^$it" } + if (paths.isEmpty()) emptyList() else listOf("--") + paths
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
     * Runs git with [arguments] in [directory], [input] on its standard input; its output if it succeeds in time and is
     * not stopped by [cancellation]. Reads take no optional locks and drop what git says on its standard error.
     */
    private fun runBytes(
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
     * Stores the files [paths] of the working tree at [root] as they are, without filters, and gives their object
     * names in order: `git hash-object -w --no-filters --stdin-paths`, the paths on its standard input, so that their
     * bytes can be read with [blob] whatever the JVM's locale. `null` if git is not available, fails (also for a
     * missing file) or takes longer than 10 minutes (the files may be large), or for a path with a line break.
     */
    internal fun storeFiles(
        root: Path,
        paths: List<String>,
    ): List<String>? {
        if (!available || paths.any { '\n' in it }) return null
        if (paths.isEmpty()) return emptyList()
        val input = paths.joinToString("") { "$it\n" }.toByteArray(Charsets.UTF_8)
        val arguments = listOf("hash-object", "-w", "--no-filters", "--stdin-paths")
        val names =
            runBytes(
                arguments,
                root,
                CHECKOUT_TIMEOUT,
                input,
            )?.toString(Charsets.UTF_8)?.lines()?.filter(String::isNotEmpty)
        return names?.takeIf { it.size == paths.size }
    }

    /** The size in bytes of the object [name] (a full object name); `null` if git is not available or fails. */
    internal fun objectSize(
        root: Path,
        name: String,
    ): Long? =
        objectInfo(root, listOf(name))
            ?.lineSequence()
            ?.firstOrNull()
            ?.split(' ')
            ?.getOrNull(2)
            ?.toLongOrNull()

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
     * The full refname of the branch `HEAD` is on in the repository at [root], an empty string when it is detached;
     * `null` if git is not available, fails or takes longer than 3 s (unlike [currentBranch], which gives `null` for
     * both).
     */
    internal fun headBranch(root: Path): String? {
        if (!available) return null
        val execution =
            execute(listOf("--no-optional-locks", "symbolic-ref", "-q", "HEAD"), root, 3.seconds, null, write = false)
                ?: return null
        if (!execution.complete) return null
        return when (execution.exitValue) {
            0 -> {
                execution.output
                    .toString(Charsets.UTF_8)
                    .trim()
                    .ifEmpty { null }
            }

            1 -> {
                ""
            }

            else -> {
                null
            }
        }
    }

    /**
     * How many commits reachable from `HEAD` in the repository at [root] are not reachable from [target] (a full
     * hash) nor from any local branch other than [branch] (the short name of the current one, `null` when `HEAD` is
     * detached), remote branch or tag: `git rev-list --count`. `null` if git is not available, fails or takes longer
     * than 10 s.
     */
    internal fun unreachableCount(
        root: Path,
        target: String,
        branch: String?,
    ): Int? {
        if (!available || target.startsWith('-')) return null
        val arguments =
            buildList {
                addAll(listOf("--no-optional-locks", "rev-list", "--count", "HEAD", "--not", target))
                // A pattern for the next --branches, without refs/heads/ (with it, nothing would be excluded).
                if (branch != null) add("--exclude=$branch")
                addAll(listOf("--branches", "--tags", "--remotes", "--"))
            }
        return run(arguments, root, 10.seconds)?.trim()?.toIntOrNull()
    }

    /**
     * How many commits reachable from `HEAD` in the repository at [root] are reachable neither from [target] (a hash or
     * full refname) nor from any ref below `refs/` (branches, tags, remote branches, the stash, notes): the commits a
     * switch from a detached `HEAD` to [target] leaves behind, as git counts them for its warning; `git rev-list --count`.
     * `null` if git is not available, fails or takes longer than 10 s.
     */
    internal fun leftBehindCount(
        root: Path,
        target: String,
    ): Int? {
        if (!available || target.startsWith('-')) return null
        // A glob with *, so git appends no /*; it matches across slashes, as git's warning excludes every ref.
        val arguments =
            listOf("--no-optional-locks", "rev-list", "--count", "HEAD", "--not", target, "--glob=refs/*", "--")
        return run(arguments, root, 10.seconds)?.trim()?.toIntOrNull()
    }

    /**
     * How many tracked files of the working tree at [root] have changes, staged or not, against `HEAD`: the records of
     * `git status --porcelain -z --untracked-files=no --no-renames`. `null` if git is not available, fails or takes
     * longer than 10 s.
     */
    internal fun trackedChangeCount(root: Path): Int? {
        if (!available) return null
        val arguments =
            listOf("--no-optional-locks", "status", "--porcelain", "-z", "--untracked-files=no", "--no-renames")
        return run(arguments, root, 10.seconds)?.split('\u0000')?.count(String::isNotEmpty)
    }

    /**
     * The last commit, in topological order, that `HEAD` has and no ref matching [excludedGlobs] (`--glob`) has, as its
     * hash followed by its parents; empty if there is none. `null` if git is not available, fails or takes longer than
     * 10 s. Only the last line of git's output is read as a commit.
     */
    internal fun lastOutgoing(
        root: Path,
        excludedGlobs: List<String>,
    ): List<String>? {
        if (!available) return null
        val arguments =
            listOf("--no-optional-locks", "rev-list", "--topo-order", "--parents", "HEAD", "--not") +
                excludedGlobs.map { "--glob=$it" } + "--"
        val output = run(arguments, root, 10.seconds) ?: return null
        val last = output.trimEnd().substringAfterLast('\n')
        return last.split(' ').filter(String::isNotEmpty)
    }

    /**
     * The best common ancestor of the commits [first] and [second] (full hashes or refnames) in the repository at
     * [root]: `git merge-base`; `null` if they have none, or if git is not available, fails or takes longer than 10 s.
     */
    internal fun mergeBase(
        root: Path,
        first: String,
        second: String,
    ): String? {
        if (!available) return null
        val arguments = listOf("--no-optional-locks", "merge-base", "--end-of-options", first, second)
        return run(arguments, root, 10.seconds)?.trim()?.ifEmpty { null }
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
     * a few lines, well below the pipe's buffer, or read by git at once (a tag message). [environment] is added to the
     * one of the processes. A [cancellation] stops git as the timeout does, unless git has ended by then; one before git
     * starts keeps it from starting. With [maxOutput], git and what it started are killed as soon as it writes more
     * than that many bytes, and the result says it [overflowed][Execution.overflowed]. A read in a [directory] runs no
     * `core.fsmonitor` hook ([fsmonitorGuard]) unless [guardFsmonitor] is `false`.
     */
    private fun execute(
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
    private class Execution(
        val exitValue: Int?,
        val output: ByteArray,
        val errors: ByteArray,
        val complete: Boolean,
        val cancelled: Boolean = false,
        /** Git wrote more than the most output asked for, and was killed. */
        val overflowed: Boolean = false,
    )

    internal companion object {
        private val HASH_PREFIX = Regex("[0-9a-fA-F]{4,64}")

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
        private const val MAX_INPUT = 15 * 1024

        /** The options that turn a `core.fsmonitor` hook off for a read. */
        private val GUARDED = listOf("-c", "core.fsmonitor=false")

        /** Room for the header line of `cat-file --batch` besides the object name, and the line break at the end. */
        private const val BLOB_HEADER_SLACK = 64L
    }
}

/**
 * The variables git lists with `--local-env-vars` but keeps when it switches repositories (submodules): configuration
 * given in the environment (`git -c` of an outer git, `GIT_CONFIG_COUNT` with its keys and values, e.g.
 * `safe.directory` in a container), which is the user's and points at no repository.
 */
private val CONFIGURATION_VARIABLES = setOf("GIT_CONFIG_PARAMETERS", "GIT_CONFIG_COUNT")

/**
 * The variables that point git at a repository as git 2.53 lists them (`git rev-parse --local-env-vars`), without the
 * [CONFIGURATION_VARIABLES].
 */
internal val KNOWN_REPOSITORY_VARIABLES =
    listOf(
        "GIT_ALTERNATE_OBJECT_DIRECTORIES",
        "GIT_CONFIG",
        "GIT_OBJECT_DIRECTORY",
        "GIT_DIR",
        "GIT_WORK_TREE",
        "GIT_IMPLICIT_WORK_TREE",
        "GIT_GRAFT_FILE",
        "GIT_INDEX_FILE",
        "GIT_NO_REPLACE_OBJECTS",
        "GIT_REPLACE_REF_BASE",
        "GIT_PREFIX",
        "GIT_SHALLOW_FILE",
        "GIT_COMMON_DIR",
    )

/**
 * The variables of [environment] that point git at a repository (ADR 0052): those `git rev-parse --local-env-vars`
 * lists, run with [executable] (so a newer git's are known too), and the [KNOWN_REPOSITORY_VARIABLES]; as for git, not
 * the [CONFIGURATION_VARIABLES]. Git is asked only if one of the known ones is set, and is given none of them; if it
 * cannot be asked within 3 s, the known ones count.
 */
internal fun repositoryVariables(
    executable: String,
    environment: Map<String, String>,
): Set<String> {
    val known = KNOWN_REPOSITORY_VARIABLES.filter { it in environment }
    if (known.isEmpty()) return emptySet()
    val listed =
        try {
            val process =
                ProcessBuilder(executable, "rev-parse", "--local-env-vars")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .apply {
                        environment().clear()
                        environment().putAll(environment - known.toSet())
                    }.start()
            process.outputStream.close()
            // A few short lines: they fit in the pipe, so they can be read once git has ended.
            if (process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0) {
                process.inputStream
                    .use { it.readAllBytes() }
                    .toString(
                        Charsets.UTF_8,
                    ).lines()
                    .filter(String::isNotEmpty)
            } else {
                process.destroyForcibly()
                emptyList()
            }
        } catch (e: IOException) {
            log.log(System.Logger.Level.DEBUG, "Cannot run $executable", e)
            emptyList()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            emptyList()
        }
    val variables = (known + listed.filter { it in environment && it !in CONFIGURATION_VARIABLES }).toSet()
    log.log(System.Logger.Level.INFO, "git runs without ${variables.sorted().joinToString(", ")} of the environment")
    return variables
}

/**
 * Waits up to [timeout] for [process] to end, or until [cancellation] (if any) is cancelled; whether it ended. A
 * process that ended by the time it was cancelled counts as ended, so its result stands.
 */
internal fun awaitEnd(
    process: Process,
    timeout: Duration,
    cancellation: GitCancellation?,
): Boolean {
    if (cancellation == null) return process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    try {
        CompletableFuture
            .anyOf(process.onExit(), cancellation.signal)
            .get(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
        // Still running, unless it ended just now: told below.
    }
    return !process.isAlive
}

/**
 * Copies [input] to [output] up to [limit] bytes; `false` (with [limit] bytes copied) if [input] has more, which are
 * not read.
 */
internal fun copyAtMost(
    input: InputStream,
    output: OutputStream,
    limit: Long,
): Boolean {
    val buffer = ByteArray(8192)
    var copied = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) return true
        if (copied + read > limit) {
            output.write(buffer, 0, (limit - copied).toInt())
            return false
        }
        output.write(buffer, 0, read)
        copied += read
    }
}

/** The content of a blob with the filters of a checkout ([GitCli.filteredBlob]), or that it is too large. */
internal sealed interface FilteredBlob {
    class Content(
        val bytes: ByteArray,
    ) : FilteredBlob

    data object TooLarge : FilteredBlob
}

/**
 * One entry of git's configuration ([GitCli.configEntries]): its [scope] (`system`, `global`, `local`, `worktree`,
 * `command`), its [origin] as git names it (`file:.git/config`), its [key] (section and variable lower case) and its
 * [value], `null` for an entry without `=`.
 */
internal data class ConfigEntry(
    val scope: String,
    val origin: String,
    val key: String,
    val value: String?,
)

/** An unmerged index entry (a conflict): the [path]'s version of [stage] 1 (base), 2 (ours) or 3 (theirs). */
internal data class UnmergedEntry(
    val path: String,
    val stage: Int,
    val mode: String,
    val objectName: String,
)

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

/**
 * The message of a write that git did not finish within [timeout] and was stopped: for one that [writesFiles], also that
 * some of them may be changed and others not (git changes files one by one), and how to see which.
 */
internal fun timedOut(
    timeout: Duration,
    writesFiles: Boolean,
): String {
    val stopped = "git did not finish within ${shown(timeout)}."
    if (!writesFiles) return stopped
    return "$stopped Files and the index may be partly changed; check git status in a terminal."
}

/** [duration] as a message says it: "60 s", "90 s", "10 min". */
internal fun shown(duration: Duration): String =
    if (duration > 1.minutes && duration.inWholeSeconds % 60 == 0L) {
        "${duration.inWholeMinutes} min"
    } else {
        "${duration.inWholeSeconds} s"
    }

/**
 * What a write of git gave: its [result], its [exitValue] (`null` if it did not end by itself), its standard [output]
 * and its standard [errors] (without hints), and whether it was stopped as it [timedOut].
 */
internal data class GitWriteOutput(
    val result: GitResult,
    val exitValue: Int?,
    val output: String = "",
    val errors: String = "",
    val timedOut: Boolean = false,
)
