package dev.sluide.git.cli

import dev.sluide.git.GitResult
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/**
 * The commits that descend from [commit] and are ancestors of [head] (full hashes), parents first: `git rev-list
 * --topo-order --reverse --ancestry-path <commit>..<head>`, without [commit] itself; `null` if git is not available,
 * fails or takes longer than 10 s.
 */
internal fun GitCli.ancestryPath(
    root: Path,
    commit: String,
    head: String,
): List<String>? {
    if (!available || commit.startsWith('-') || head.startsWith('-')) return null
    val arguments =
        listOf(
            "--no-optional-locks",
            "rev-list",
            "--topo-order",
            "--reverse",
            "--ancestry-path",
            "$commit..$head",
            "--",
        )
    return run(arguments, root, 10.seconds)?.lines()?.filter(String::isNotEmpty)
}

/**
 * The raw objects of the commits [hashes] (full hashes) by hash, as stored: `git cat-file --batch`, the hashes on its
 * standard input in parts of at most [GitCli.MAX_INPUT] bytes. `null` if git is not available, fails, takes longer
 * than [GitCli.WRITE_TIMEOUT] for a part, or one of them is no commit.
 */
internal fun GitCli.rawCommits(
    root: Path,
    hashes: List<String>,
): Map<String, ByteArray>? {
    if (!available || hashes.any { '\n' in it }) return null
    val objects = LinkedHashMap<String, ByteArray>()
    val perPart = (GitCli.MAX_INPUT / ((hashes.maxOfOrNull { it.length } ?: 0) + 1)).coerceAtLeast(1)
    for (part in hashes.chunked(perPart)) {
        val input = part.joinToString("") { "$it\n" }.toByteArray(Charsets.UTF_8)
        val arguments = listOf("--no-optional-locks", "cat-file", "--batch")
        val output = runBytes(arguments, root, GitCli.WRITE_TIMEOUT, input) ?: return null
        var offset = 0
        for (hash in part) {
            val end = output.indexOf(NEWLINE, offset)
            if (end < 0) return null
            val header = output.copyOfRange(offset, end).toString(Charsets.UTF_8).split(' ')
            if (header.size != 3 || header[0] != hash || header[1] != "commit") return null
            val size = header[2].toIntOrNull() ?: return null
            val start = end + 1
            if (size < 0 || start + size >= output.size || output[start + size] != NEWLINE) return null
            objects[hash] = output.copyOfRange(start, start + size)
            offset = start + size + 1
        }
    }
    return objects
}

/**
 * [message] cleaned up as `git commit --cleanup=whitespace` does: `git stripspace`, without stripping comments, the
 * message on its standard input as UTF-8. `null` if git is not available, fails or takes longer than
 * [GitCli.WRITE_TIMEOUT].
 */
internal fun GitCli.stripSpace(
    root: Path,
    message: String,
): String? {
    if (!available) return null
    return runBytes(listOf("stripspace"), root, GitCli.WRITE_TIMEOUT, message.toByteArray(Charsets.UTF_8))
        ?.toString(Charsets.UTF_8)
}

/**
 * The identity and time a commit made now in the repository at [root] would have as its committer (`git var
 * GIT_COMMITTER_IDENT`: `Name <email> <seconds> <zone>`), as git gives its bytes; a failure with git's message if it
 * cannot tell (no identity configured, e.g.).
 */
internal fun GitCli.committerIdent(root: Path): Result<ByteArray> {
    fun failed(message: String) = Result.failure<ByteArray>(IllegalStateException(message))
    if (!available) return failed("git is not available.")
    // As a write, which keeps git's whole message; it changes nothing.
    val execution =
        execute(listOf("var", "GIT_COMMITTER_IDENT"), root, GitCli.WRITE_TIMEOUT, null, write = true)
            ?: return failed("git could not be run.")
    val exitValue = execution.exitValue ?: return failed(timedOut(GitCli.WRITE_TIMEOUT, writesFiles = false))
    val ident = execution.output.dropLastWhile { it == NEWLINE }.toByteArray()
    if (exitValue == 0 && execution.complete && ident.isNotEmpty()) return Result.success(ident)
    val message = withoutHints(execution.errors.toString(Charsets.UTF_8).trim())
    return failed(message.ifEmpty { "git failed with exit code $exitValue." })
}

/**
 * Stores the commit object [content] in the repository at [root]: `git hash-object -t commit -w --stdin`, so git's
 * checks of a commit object apply. The result has the new commit's hash, or git's message.
 */
internal fun GitCli.writeCommit(
    root: Path,
    content: ByteArray,
): GitResult {
    val output = writeWithOutput(root, listOf("hash-object", "-t", "commit", "-w", "--stdin"), input = content)
    if (!output.result.success) return output.result
    val hash = output.output.trim()
    return if (FULL_HASH.matches(hash)) GitResult(true, hash) else GitResult(false, "git gave no commit hash.")
}

/**
 * Moves the local branch [branch] (short name) from [old] to [new] (full hashes) with [reason] in its reflog: `git
 * update-ref -m <reason> --stdin`, the branch named on its standard input, so any name reaches git as UTF-8. Fails
 * with git's message if the branch is no longer at [old].
 */
internal fun GitCli.moveBranch(
    root: Path,
    branch: String,
    new: String,
    old: String,
    reason: String,
): GitResult {
    if ('\n' in branch) return GitResult(false, "'$branch' cannot be passed to git.")
    val input = "update refs/heads/$branch $new $old\n".toByteArray(Charsets.UTF_8)
    return write(root, listOf("update-ref", "-m", reason, "--stdin"), input = input)
}

/**
 * The commit object [raw] (as [rawCommits] reads it) for its copy in a reword (ADR 0100): the headers in order and
 * byte for byte, except each `parent` replaced by its new hash in [rewritten] (kept if it has none), the `committer`
 * line by [committer] (an identity of [committerIdent]), and the signatures (`gpgsig`, `gpgsig-sha256`, with their
 * continuation lines) dropped. With a [message] (UTF-8), that is the new message and the `encoding` header is dropped;
 * without, the message stays as its bytes are.
 */
internal fun rewordedCommit(
    raw: ByteArray,
    rewritten: Map<String, String>,
    committer: ByteArray,
    message: ByteArray?,
): ByteArray {
    val separator = (0 until raw.size - 1).firstOrNull { raw[it] == NEWLINE && raw[it + 1] == NEWLINE }
    val headersEnd = separator?.plus(1) ?: raw.size
    val result = ByteArrayOutputStream(raw.size + (message?.size ?: 0))
    var keep = true
    var start = 0
    while (start < headersEnd) {
        val end = raw.indexOf(NEWLINE, start).let { if (it < 0 || it >= headersEnd) headersEnd else it + 1 }
        val line = raw.copyOfRange(start, end)
        start = end
        if (line.isNotEmpty() && line[0] == SPACE) {
            if (keep) result.write(line)
            continue
        }
        val text = line.toString(Charsets.ISO_8859_1)
        val key = text.substringBefore(' ').trimEnd('\n')
        keep = key !in SIGNATURES && !(key == "encoding" && message != null)
        when {
            !keep -> {}

            key == "parent" -> {
                val parent = text.removePrefix("parent ").trimEnd('\n')
                result.write("parent ${rewritten[parent] ?: parent}\n".toByteArray(Charsets.UTF_8))
            }

            key == "committer" -> {
                result.write("committer ".toByteArray(Charsets.UTF_8))
                result.write(committer)
                result.write(NEWLINE.toInt())
            }

            else -> {
                result.write(line)
            }
        }
    }
    if (separator != null || message != null) result.write(NEWLINE.toInt())
    if (message != null) {
        result.write(message)
    } else if (separator != null) {
        result.write(raw, separator + 2, raw.size - separator - 2)
    }
    return result.toByteArray()
}

/** The index of the first [byte] in this array at or after [from], `-1` if there is none. */
private fun ByteArray.indexOf(
    byte: Byte,
    from: Int,
): Int {
    for (index in from until size) if (this[index] == byte) return index
    return -1
}

private const val NEWLINE = '\n'.code.toByte()

private const val SPACE = ' '.code.toByte()

/** The headers of a signed commit, which a rewritten commit cannot keep. */
private val SIGNATURES = setOf("gpgsig", "gpgsig-sha256")

private val FULL_HASH = Regex("[0-9a-f]{40}|[0-9a-f]{64}")
