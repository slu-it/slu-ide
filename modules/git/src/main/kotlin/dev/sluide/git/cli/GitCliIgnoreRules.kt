package dev.sluide.git.cli

import dev.sluide.git.GitCancellation
import dev.sluide.git.GitIgnoreLineUse
import dev.sluide.git.GitIgnoreRules
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/** The most candidates (untracked and tracked together) [ignoreRules] asks git about. */
internal const val MAX_IGNORE_CANDIDATES = 50_000

/** The most bytes of output [ignoreRules] reads from the commands that list the candidates. */
internal const val MAX_IGNORE_LISTING = 16L * 1024 * 1024

/** The most examples of each kind a [GitIgnoreLineUse] keeps. */
private const val MAX_EXAMPLES = 10

/** How long each git call of [ignoreRules] may take. */
private val IGNORE_TIMEOUT = 10.seconds

/** How long all git calls of one [ignoreRules] may take together. */
internal val IGNORE_DEADLINE = 15.seconds

/**
 * Which lines of [ignoreFile], an ignore file in the repository at [root], decide which paths below its folder D (ADR
 * 0080, git decides as in ADR 0013):
 * - the untracked candidates are the `?` and `!` entries of `git status --porcelain=v2 -z --ignored=matching
 *   --untracked-files=all -- D/`: files one by one, a folder only where a pattern matches the folder itself;
 * - the tracked candidates are those of `git ls-files -z --cached --ignored --exclude-standard -- D/`, each once
 *   (git lists an unmerged path once per stage);
 * - `git check-ignore -v -z --stdin` (with `--no-index` for the tracked ones) gives each candidate's deciding line,
 *   of which only those of [ignoreFile] count. Its paths go to git's standard input in chunks below
 *   [GitCli.MAX_INPUT].
 *
 * The listings run with `--literal-pathspecs`; `check-ignore` does not take it, so a path starting with `:` (pathspec
 * magic) goes to it as `./:…`. At most [limit] candidates are asked about, the untracked first; beyond that, or if a
 * listing writes more than [maxListing] bytes (its complete records are kept, git is stopped), the result is not
 * [complete][GitIgnoreRules.complete].
 *
 * Each call has 10 s, and all of them together [deadline] (15 s): a call gets at most what is left of it. Once it has
 * passed while `check-ignore` runs (between its chunks, or a chunk stopped for it), the lines matched so far are the
 * answer, not [complete][GitIgnoreRules.complete]; while the listings run, there is none.
 *
 * `null` if git is not available, [ignoreFile] is not below [root], a call fails (exit 1 of `check-ignore`, "nothing
 * matched", is an answer), takes longer than its 10 s, the listings take longer than [deadline], or [cancellation]
 * stops it, which is also looked at between the chunks.
 */
internal fun GitCli.ignoreRules(
    root: Path,
    ignoreFile: Path,
    cancellation: GitCancellation?,
    limit: Int = MAX_IGNORE_CANDIDATES,
    maxListing: Long = MAX_IGNORE_LISTING,
    deadline: Duration = IGNORE_DEADLINE,
): GitIgnoreRules? {
    if (!available) return null
    val time = Deadline(System.nanoTime() + deadline.inWholeNanoseconds)
    val repository = root.toAbsolutePath().normalize()
    val file = ignoreFile.toAbsolutePath().normalize()
    val relative = repository.relativize(file)
    if (relative.toString().isEmpty() || relative.startsWith("..")) return null
    val folder = relative.parent?.joinToString("/")?.let { "$it/" } ?: ""
    val source = relative.joinToString("/")
    val pathspec = folder.ifEmpty { "./" }

    val status =
        listing(
            repository,
            listOf(
                "status",
                "--porcelain=v2",
                "-z",
                "--no-renames",
                "--ignored=matching",
                "--untracked-files=all",
                "--",
                pathspec,
            ),
            cancellation,
            maxListing,
            time,
        ) ?: return null
    val untracked = statusCandidates(status.output, status.overflowed)
    val tracked =
        listing(
            repository,
            listOf("ls-files", "-z", "--cached", "--ignored", "--exclude-standard", "--", pathspec),
            cancellation,
            maxListing,
            time,
        ) ?: return null
    // An unmerged path has an entry per stage.
    val trackedPaths = nulRecords(tracked.output, tracked.overflowed).distinct()
    val askedUntracked = untracked.take(limit)
    val askedTracked = trackedPaths.take(limit - askedUntracked.size)
    val complete =
        !status.overflowed && !tracked.overflowed &&
            askedUntracked.size == untracked.size && askedTracked.size == trackedPaths.size

    // Many records name the same few sources.
    val sources = HashMap<String, Boolean>()
    val isIgnoreFile = { match: IgnoreMatch ->
        sources.getOrPut(match.source) { isSource(match.source, source, repository, file) }
    }
    val untrackedLines = decidingLines(repository, askedUntracked, tracked = false, cancellation, time) ?: return null
    val trackedLines =
        if (untrackedLines.cutOff) {
            DecidingLines(emptyList(), cutOff = true)
        } else {
            decidingLines(repository, askedTracked, tracked = true, cancellation, time) ?: return null
        }
    return GitIgnoreRules(
        lineUses(untrackedLines.matches.filter(isIgnoreFile), trackedLines.matches.filter(isIgnoreFile), folder),
        complete && !trackedLines.cutOff,
    )
}

/** The end of the time all calls of one [ignoreRules] have, by [System.nanoTime]. */
private class Deadline(
    val end: Long,
) {
    /** What is left of it, at most the 10 s of one call; zero or less once it has passed. */
    fun timeout(): Duration = minOf(IGNORE_TIMEOUT, (end - System.nanoTime()).nanoseconds)
}

/** What a listing of [ignoreRules] wrote, cut off if it [overflowed]. */
private class Listing(
    val output: String,
    val overflowed: Boolean,
)

/**
 * Runs a read that lists paths; `null` if it fails, takes too long (also past [deadline]) or is cancelled, but not if
 * it overflowed.
 */
private fun GitCli.listing(
    root: Path,
    arguments: List<String>,
    cancellation: GitCancellation?,
    maxOutput: Long,
    deadline: Deadline,
): Listing? {
    val timeout = deadline.timeout()
    if (!timeout.isPositive()) return null
    val execution =
        execute(
            listOf("--no-optional-locks", "--literal-pathspecs") + arguments,
            root,
            timeout,
            null,
            write = false,
            cancellation = cancellation,
            maxOutput = maxOutput,
        ) ?: return null
    if (execution.cancelled || !execution.complete) return null
    // Stopped for writing too much: its exit value is that of a killed process.
    if (!execution.overflowed && execution.exitValue != 0) return null
    return Listing(execution.output.toString(Charsets.UTF_8), execution.overflowed)
}

/** The [matches] of [decidingLines], [cutOff] if its deadline passed before all paths were asked. */
private class DecidingLines(
    val matches: List<IgnoreMatch>,
    val cutOff: Boolean,
)

/**
 * The deciding lines of [paths] by `git check-ignore -v -z --stdin` ([tracked]: `--no-index`, else git leaves tracked
 * paths out), in their order, asked in chunks below [GitCli.MAX_INPUT] bytes; those found by then if the [deadline]
 * passes, `null` on a failure or once [cancellation] says so. A path too long for a chunk of its own (longer than any
 * file system allows) is left out.
 */
private fun GitCli.decidingLines(
    root: Path,
    paths: List<String>,
    tracked: Boolean,
    cancellation: GitCancellation?,
    deadline: Deadline,
): DecidingLines? {
    // check-ignore takes no --literal-pathspecs; a leading ':' is pathspec magic, which ./ keeps out.
    val arguments =
        listOf("--no-optional-locks", "check-ignore", "-v", "-z", "--stdin") +
            if (tracked) listOf("--no-index") else emptyList()
    val asked = paths.map { if (it.startsWith(':')) "./$it" else it }
    val matches = mutableListOf<IgnoreMatch>()
    for (chunk in inputChunks(asked, GitCli.MAX_INPUT)) {
        if (cancellation?.cancelled == true) return null
        val timeout = deadline.timeout()
        if (!timeout.isPositive()) return DecidingLines(matches, cutOff = true)
        val execution =
            execute(arguments, root, timeout, chunk, write = false, cancellation = cancellation) ?: return null
        // Stopped as the deadline passed, not after its own 10 s.
        val timedOut = execution.exitValue == null && !execution.cancelled
        if (timedOut && timeout < IGNORE_TIMEOUT) return DecidingLines(matches, cutOff = true)
        // 1: none of the paths is ignored.
        if (execution.cancelled || !execution.complete || execution.exitValue !in 0..1) return null
        // Git writes each path as it was asked; no listed path starts with ./ by itself.
        matches +=
            checkIgnoreMatches(
                execution.output.toString(Charsets.UTF_8),
            ).map { it.copy(path = it.path.removePrefix("./")) }
    }
    return DecidingLines(matches, cutOff = false)
}

/**
 * The [paths] as NUL-terminated UTF-8, in chunks of at most [maxBytes] bytes each, in their order; a path that does not
 * fit into a chunk of its own is left out.
 */
internal fun inputChunks(
    paths: List<String>,
    maxBytes: Int,
): List<ByteArray> {
    val chunks = mutableListOf<ByteArray>()
    val chunk = ByteArrayOutputStream()
    for (path in paths) {
        val bytes = path.toByteArray(Charsets.UTF_8)
        if (bytes.size + 1 > maxBytes) continue
        if (chunk.size() + bytes.size + 1 > maxBytes) {
            chunks += chunk.toByteArray()
            chunk.reset()
        }
        chunk.write(bytes)
        chunk.write(0)
    }
    if (chunk.size() > 0) chunks += chunk.toByteArray()
    return chunks
}

/** One record of `git check-ignore -v -z`: the [path]'s deciding [pattern], line [line] of [source]. */
internal data class IgnoreMatch(
    val source: String,
    val line: Int,
    val pattern: String,
    val path: String,
)

/**
 * The records of `git check-ignore -v -z`: `<source> NUL <linenum> NUL <pattern> NUL <path> NUL`. Without `-n` git
 * writes only matched paths; a record without a line number is skipped.
 */
internal fun checkIgnoreMatches(output: String): List<IgnoreMatch> {
    val fields = output.split('\u0000')
    return (0 until fields.size / 4).mapNotNull { index ->
        val line = fields[index * 4 + 1].toIntOrNull() ?: return@mapNotNull null
        IgnoreMatch(fields[index * 4], line, fields[index * 4 + 2], fields[index * 4 + 3])
    }
}

/**
 * The NUL-terminated records of [output]; if it was [cut off][overflowed], without the last one, which may be
 * incomplete.
 */
internal fun nulRecords(
    output: String,
    overflowed: Boolean,
): List<String> {
    val fields = output.split('\u0000')
    // What follows the last NUL: nothing in a complete output, a record cut off otherwise.
    return (if (overflowed) fields.dropLast(1) else fields).filter(String::isNotEmpty)
}

/**
 * The untracked (`?`) and ignored (`!`) paths of `git status --porcelain=v2 -z` in its order, folders ending in `/`;
 * the other records (changed tracked files; a rename's second path is skipped) do not count. If the output was
 * [cut off][overflowed], the last, incomplete record is left out.
 */
internal fun statusCandidates(
    output: String,
    overflowed: Boolean,
): List<String> {
    val records = nulRecords(output, overflowed)
    val candidates = mutableListOf<String>()
    var index = 0
    while (index < records.size) {
        val record = records[index]
        when {
            record.startsWith("? ") || record.startsWith("! ") -> candidates += record.substring(2)

            // A rename or copy: its original path follows as a field of its own.
            record.startsWith("2 ") -> index++
        }
        index++
    }
    return candidates
}

/**
 * Whether [recorded], a source `check-ignore` printed (relative to [root], or absolute such as `core.excludesFile`),
 * is the ignore [file], [relative] to [root] with `/`; also through another path to it, e.g. a symbolic link.
 */
private fun isSource(
    recorded: String,
    relative: String,
    root: Path,
    file: Path,
): Boolean {
    if (recorded == relative) return true
    val path =
        try {
            Path.of(recorded)
        } catch (_: InvalidPathException) {
            return false
        }
    val resolved = root.resolve(path).normalize()
    if (resolved == file) return true
    return try {
        Files.isSameFile(resolved, file)
    } catch (_: IOException) {
        false
    }
}

/**
 * The use of each line of the ignore file in the [untracked] and [tracked] matches of its lines, the examples without
 * the ignore file's [folder] (relative to the root, ending in `/`, empty for the root).
 */
internal fun lineUses(
    untracked: List<IgnoreMatch>,
    tracked: List<IgnoreMatch>,
    folder: String,
): Map<Int, GitIgnoreLineUse> {
    val untrackedByLine = untracked.groupBy(IgnoreMatch::line)
    val trackedByLine = tracked.groupBy(IgnoreMatch::line)
    return (untrackedByLine.keys + trackedByLine.keys).sorted().associateWith { line ->
        val decided = untrackedByLine[line].orEmpty()
        val kept = trackedByLine[line].orEmpty()
        val unignored = decided.count { it.pattern.startsWith('!') }
        GitIgnoreLineUse(
            ignored = decided.size - unignored,
            unignored = unignored,
            tracked = kept.size,
            examples = decided.take(MAX_EXAMPLES).map { it.path.removePrefix(folder) },
            trackedExamples = kept.take(MAX_EXAMPLES).map { it.path.removePrefix(folder) },
        )
    }
}
