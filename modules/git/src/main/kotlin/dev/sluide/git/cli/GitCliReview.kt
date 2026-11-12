package dev.sluide.git.cli

import dev.sluide.git.GitCancellation
import dev.sluide.git.parse.GitLogParser
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/**
 * The output of `git log --topo-order` for the commits [to] has and [from] has not (`<to> ^<from>`; all of [to]'s
 * history for a `null` [from]), at most [limit] of them, newest first, in the format [GitLogParser] reads. Both are
 * full hashes, after `--end-of-options`. `null` if git is not available, fails, takes longer than 10 s or is stopped by
 * [cancellation].
 */
internal fun GitCli.reviewLog(
    root: Path,
    from: String?,
    to: String,
    limit: Int,
    cancellation: GitCancellation? = null,
): String? {
    if (!available) return null
    val arguments =
        buildList {
            addAll(listOf("--no-optional-locks", "log", "--topo-order"))
            addAll(logOutputOptions())
            add("--max-count=$limit")
            add("--end-of-options")
            add(to)
            if (from != null) add("^$from")
            add("--")
        }
    return run(arguments, root, 10.seconds, cancellation)
}

/**
 * The lines of `git cat-file --batch-check` for the [objects] (such as `<commit>:<path>`), one per object in their
 * order: `<name> <type> <size>`, or `<object> missing`. The names go to git's standard input, in as many runs as keep
 * each input below [GitCli.MAX_INPUT]. `null` if git is not available, fails, takes longer than 10 s per run or is
 * stopped by [cancellation], or for a name [refusedName] refuses.
 */
internal fun GitCli.objectInfos(
    root: Path,
    objects: List<String>,
    cancellation: GitCancellation? = null,
): List<String>? {
    if (!available || objects.any(::refusedName)) return null
    return inputChunks(objects).flatMap { chunk ->
        val lines = objectInfo(root, chunk, cancellation)?.split('\n')?.take(chunk.size) ?: return null
        if (lines.size != chunk.size) return null
        lines
    }
}

/** The most bytes of blobs one `git cat-file --batch` of [blobs] reads (32 MiB), a single larger blob aside. */
internal const val MAX_BATCH_OUTPUT = 32L * 1024 * 1024

/**
 * The raw content of the blobs [sizes] names (full object names to their sizes in bytes, as `git cat-file
 * --batch-check` gave them), read with `git cat-file --batch`, the names on its standard input in as many runs as keep
 * each input below [GitCli.MAX_INPUT] and the blobs of each run within [maxRunOutput] bytes (unless one alone is
 * larger). A blob git does not find is left out. Git is killed if it writes more than the sizes announce. `null` if
 * git is not available, fails, takes longer than 30 s per run or is stopped by [cancellation], or if its output cannot
 * be read.
 */
internal fun GitCli.blobs(
    root: Path,
    sizes: Map<String, Long>,
    cancellation: GitCancellation? = null,
    maxRunOutput: Long = MAX_BATCH_OUTPUT,
): Map<String, ByteArray>? {
    if (!available || sizes.keys.any(::refusedName)) return null
    val blobs = HashMap<String, ByteArray>()
    for (run in inputChunks(sizes.keys.toList(), sizes::getValue, maxRunOutput)) {
        val input = run.joinToString("") { "$it\n" }.toByteArray(Charsets.UTF_8)
        // The header line, the content and a line break each.
        val limit = run.sumOf { sizes.getValue(it) + it.length + BLOB_HEADER_SLACK }
        val arguments = listOf("--no-optional-locks", "cat-file", "--batch")
        val execution =
            execute(arguments, root, 30.seconds, input, false, cancellation = cancellation, maxOutput = limit)
                ?: return null
        if (execution.overflowed || execution.cancelled || execution.exitValue != 0 || !execution.complete) return null
        if (!readBatch(execution.output, run, blobs)) return null
    }
    return blobs
}

/**
 * The lines of `git status --porcelain -z --untracked-files=all --no-renames` for the working tree at [root]: one
 * `XY <path>` per changed tracked file (staged or not) and per untracked file that is not ignored, every untracked
 * file on its own, also inside untracked folders. `null` if git is not available, fails or takes longer than 10 s.
 */
internal fun GitCli.porcelainStatus(root: Path): List<String>? {
    if (!available) return null
    val arguments =
        listOf("--no-optional-locks", "status", "--porcelain", "-z", "--untracked-files=all", "--no-renames")
    return run(arguments, root, 10.seconds)?.split('\u0000')?.filter(String::isNotEmpty)
}

/** Reads `git cat-file --batch` [output] for the [names] in order into [blobs]; `false` if it does not match them. */
private fun readBatch(
    output: ByteArray,
    names: List<String>,
    blobs: MutableMap<String, ByteArray>,
): Boolean {
    var position = 0
    for (name in names) {
        val end = output.indexOf('\n'.code.toByte(), position)
        if (end < 0) return false
        val header = String(output, position, end - position, Charsets.UTF_8)
        position = end + 1
        if (header == "$name missing") continue
        val fields = header.split(' ')
        if (fields.size != 3 || fields[0] != name || fields[1] != "blob") return false
        val size = fields[2].toIntOrNull() ?: return false
        if (size < 0 || position + size >= output.size || output[position + size] != '\n'.code.toByte()) return false
        blobs[name] = output.copyOfRange(position, position + size)
        position += size + 1
    }
    return position == output.size
}

/** The index of the first [byte] in this array from [start] on, -1 if there is none. */
private fun ByteArray.indexOf(
    byte: Byte,
    start: Int,
): Int {
    for (index in start until size) if (this[index] == byte) return index
    return -1
}

/**
 * [lines] in runs whose standard input (each line and a line break, in UTF-8) stays below [GitCli.MAX_INPUT] and whose
 * [weight] adds up to at most [maxWeight], a single heavier line in a run of its own.
 */
internal fun inputChunks(
    lines: List<String>,
    weight: (String) -> Long = { 0 },
    maxWeight: Long = Long.MAX_VALUE,
): List<List<String>> {
    val chunks = mutableListOf<MutableList<String>>()
    var size = 0
    var weights = 0L
    for (line in lines) {
        val bytes = line.toByteArray(Charsets.UTF_8).size + 1
        val lineWeight = weight(line)
        val full = size + bytes > GitCli.MAX_INPUT || weights + lineWeight > maxWeight
        if (chunks.isEmpty() || (full && chunks.last().isNotEmpty())) {
            chunks += mutableListOf<String>()
            size = 0
            weights = 0
        }
        chunks.last() += line
        size += bytes
        weights += lineWeight
    }
    return chunks
}

/**
 * Whether [name] cannot be given to `git cat-file --batch(-check)`, which reads one name per line: a line break would
 * start another name, and a carriage return at its end is dropped (`HEAD:a\r` reads `HEAD:a`), so any is refused.
 */
internal fun refusedName(name: String): Boolean = '\n' in name || '\r' in name
