package dev.sluide.git.cli

import dev.sluide.git.GitCancellation
import dev.sluide.git.GitLogFilter
import dev.sluide.git.GitLogOrder
import dev.sluide.git.parse.GitFileHistoryParser
import dev.sluide.git.parse.GitLogParser
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/**
 * The output of `git log` for the repository at [root] (see [GitLogParser]) with the [filter] (ADR 0039): by
 * default the commits of all branches, remote branches, tags and `HEAD` in date order; after the first [skip], at
 * most [limit]. `null` if git is not available, fails or takes longer than 10 s, or if a revision or path of the
 * filter has a line break or they are too many for git's standard input. Empty for a repository without commits.
 * The commits of the refs matching [excludedGlobs] (`--not --glob=<pattern>`, a pattern with `*`) are left out, as
 * those of the filter's excluded revisions, but without their number being limited by the standard input.
 */
internal fun GitCli.log(
    root: Path,
    filter: GitLogFilter,
    skip: Int,
    limit: Int,
    excludedGlobs: List<String> = emptyList(),
    cancellation: GitCancellation? = null,
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
    return runBytes(
        arguments,
        root,
        10.seconds,
        input.takeIf {
            it.isNotEmpty()
        },
        cancellation = cancellation,
    )?.toString(Charsets.UTF_8)
}

/**
 * The output of `git log` for the commit [hash] alone (see [GitLogParser]) if it passes the users, paths and no
 * merges of [filter] (not its text and revisions); empty if it does not. `null` if git is not available, fails or
 * takes longer than 10 s, or for paths as [log] refuses them.
 */
internal fun GitCli.logCommit(
    root: Path,
    hash: String,
    filter: GitLogFilter,
    cancellation: GitCancellation? = null,
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
    return runBytes(
        arguments,
        root,
        10.seconds,
        input.takeIf {
            it.isNotEmpty()
        },
        cancellation = cancellation,
    )?.toString(Charsets.UTF_8)
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
internal fun GitCli.fileHistory(
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
 * How many commits that pass [filter] (its text, users, paths, first parents only and no merges; not its revisions)
 * are reachable from [left] but not from [right], and the other way round (ADR 0044): `git log --left-right
 * --format=%m` with the limiting options of [log] (`rev-list --count` does not take `--use-mailmap`), the symmetric
 * range `<left>...<right>` and the paths on the standard input, so that they reach git as UTF-8 whatever the JVM's
 * locale. `null` if git is not available, fails (also for a revision that names no commit) or takes longer than
 * 10 s, or for revisions and paths as [log] refuses them.
 */
internal fun GitCli.comparisonCounts(
    root: Path,
    left: String,
    right: String,
    filter: GitLogFilter = GitLogFilter(),
    cancellation: GitCancellation? = null,
): Pair<Int, Int>? {
    if (!available) return null
    val input = logInput(listOf("$left...$right"), filter.paths) ?: return null
    val arguments =
        globalLogOptions(filter) +
            listOf("log", "--left-right") +
            limitingOptions(filter) +
            listOf("--use-mailmap", "--no-color", "--format=%m", "--stdin", "--")
    val marks =
        runBytes(arguments, root, 10.seconds, input, cancellation = cancellation)?.toString(Charsets.UTF_8)
            ?: return null
    return marks.count { it == '<' } to marks.count { it == '>' }
}

/**
 * Options of git itself for a log with [filter]: paths are literal paths, not patterns; `log.follow` of the
 * user's configuration would make a single path follow renames, with parents that are not rewritten.
 */
private fun GitCli.globalLogOptions(filter: GitLogFilter): List<String> =
    listOf("--no-optional-locks", "-c", "log.follow=false") +
        if (filter.paths.isEmpty()) emptyList() else listOf("--literal-pathspecs")

/**
 * The options of a log that limit its commits beyond its revisions (ADR 0039): first parents only, no merges, the
 * text and the users; shared by [log] and [comparisonCounts], so that the counts match the list.
 */
private fun GitCli.limitingOptions(filter: GitLogFilter): List<String> =
    buildList {
        if (filter.firstParent) add("--first-parent")
        if (filter.noMerges) add("--no-merges")
        addAll(patterns(filter.text.trim(), filter.regex, filter.matchCase, filter.users))
    }

/** The format of the commits [GitLogParser] reads, each record started by [prefix] (ADR 0046). */
private fun GitCli.logOutputOptions(prefix: String = "") =
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
 * [GitCli.MAX_INPUT] (see there).
 */
private fun GitCli.logInput(
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
    return input.takeIf { it.size <= GitCli.MAX_INPUT }
}

/**
 * The pattern options of a log (ADR 0039): always extended regular expressions; [text] (if not empty) matching
 * the message, escaped unless [regex], ignoring case unless [matchCase]; for every one of the [users] its name or
 * email, exactly but ignoring case whatever [matchCase] says. Characters the JVM cannot pass as arguments are
 * replaced ([encodableArgument]).
 */
private fun GitCli.patterns(
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
