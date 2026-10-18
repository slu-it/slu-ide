package dev.sluide.git

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val HASH_PREFIX = Regex("[0-9a-fA-F]{4,64}")

/** Room for the header line of `cat-file --batch` besides the object name, and the line break at the end. */
private const val BLOB_HEADER_SLACK = 64L

/**
 * The full object name of the one commit [prefix] (4 to 64 hexadecimal characters) names in the repository at
 * [root]; `null` if it names none or several (ambiguous), or if git is not available, fails or takes longer than
 * 3 s.
 */
internal fun GitCli.commitOf(
    root: Path,
    prefix: String,
): String? {
    if (!available || !HASH_PREFIX.matches(prefix)) return null
    // -q: an unknown or ambiguous prefix fails quietly.
    val arguments = listOf("--no-optional-locks", "rev-parse", "--verify", "-q", "$prefix^{commit}")
    return run(arguments, root, 3.seconds)?.trim()?.ifEmpty { null }
}

/**
 * The output of `git ls-tree -r -t -z` for the tree of [commit] (a full object name) in the repository at
 * [root], each record `<mode> <type> <object>\t<path>` ended by NUL, the path unquoted; `null` if git is not
 * available, fails or takes longer than 10 s.
 */
internal fun GitCli.treePaths(
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
 * The output of `git show -s` for the commit [hash] in the repository at [root] (see [GitCommitDetailsParser]),
 * or `null` if git is not available, fails (e.g. there is no such commit) or takes longer than 3 s.
 */
internal fun GitCli.show(
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
internal fun GitCli.changedFiles(
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
internal fun GitCli.objectInfo(
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
internal fun GitCli.resolveRevisions(
    root: Path,
    revisions: List<String>,
): String? {
    if (!available || revisions.any { '\n' in it }) return null
    val input = revisions.joinToString("") { "$it^{commit}\n$it\n" }.toByteArray(Charsets.UTF_8)
    if (input.size > GitCli.MAX_INPUT) return null
    return runBytes(listOf("--no-optional-locks", "cat-file", "--batch-check"), root, 10.seconds, input)
        ?.toString(Charsets.UTF_8)
}

/**
 * The output of `git for-each-ref --contains` for the local and remote branches that contain the commit [hash] (a
 * full object name) in the repository at [root], one `<refname>\u001f<symref>` per line (ADR 0045); `null` if git
 * is not available, fails or takes longer than 10 s.
 */
internal fun GitCli.branchesContaining(
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
internal fun GitCli.blob(
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
internal fun GitCli.diffBlobs(
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
 * The content of the blob [name] (a full object name) with the filters (line endings, smudge) a checkout of it at
 * [path] would apply (ADR 0044): `git cat-file --batch --filters`, the name and path on the standard input, so the
 * path reaches git as UTF-8 whatever the JVM's locale. A smudge filter (e.g. git-lfs) may make it much larger than
 * the stored blob: above [maxSize] bytes the reading stops, git and the filter are killed, and the result is
 * [FilteredBlob.TooLarge]. Git holds a filter's whole output before it writes any of it, so a filter that does not
 * end (or fetches over the network) is only stopped by the timeout. `null` if git is not available, fails or takes
 * longer than 10 s, or for a path with a line break.
 */
internal fun GitCli.filteredBlob(
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
internal fun GitCli.workingTreeChanges(
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
 * The empty tree of the repository at [root] (`git hash-object -t tree --stdin` with nothing on standard input, so
 * also for SHA-256 repositories), which git knows without storing it; `null` if git is not available or fails.
 */
internal fun GitCli.emptyTree(root: Path): String? {
    if (!available) return null
    val arguments = listOf("--no-optional-locks", "hash-object", "-t", "tree", "--stdin")
    return runBytes(arguments, root, 3.seconds, input = ByteArray(0))?.toString(Charsets.UTF_8)?.trim()
}

/**
 * The output of `git diff --no-index --text -U0` from the file [original] to the file [current] in [folder] (plain
 * names relative to it; see [GitDiffParser]), the texts compared as they are (ADR 0044): run in [folder], with the
 * parent folders not searched for a repository, no attributes (an empty `core.attributesFile`, no system ones) and
 * no line-ending conversion, as the texts are both in their checkout form already. [algorithm] is the
 * `diff.algorithm` to use, e.g. the repository's. `null` if git is not available, fails (also exit value 1 without a
 * hunk, which is how it fails to read a file) or takes longer than 10 s.
 */
internal fun GitCli.diffFiles(
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
 * Stores the files [paths] of the working tree at [root] as they are, without filters, and gives their object
 * names in order: `git hash-object -w --no-filters --stdin-paths`, the paths on its standard input, so that their
 * bytes can be read with [blob] whatever the JVM's locale. `null` if git is not available, fails (also for a
 * missing file) or takes longer than 10 minutes (the files may be large), or for a path with a line break.
 */
internal fun GitCli.storeFiles(
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
            GitCli.CHECKOUT_TIMEOUT,
            input,
        )?.toString(Charsets.UTF_8)?.lines()?.filter(String::isNotEmpty)
    return names?.takeIf { it.size == paths.size }
}

/** The size in bytes of the object [name] (a full object name); `null` if git is not available or fails. */
internal fun GitCli.objectSize(
    root: Path,
    name: String,
): Long? =
    objectInfo(root, listOf(name))
        ?.lineSequence()
        ?.firstOrNull()
        ?.split(' ')
        ?.getOrNull(2)
        ?.toLongOrNull()

/** The content of a blob with the filters of a checkout ([GitCli.filteredBlob]), or that it is too large. */
internal sealed interface FilteredBlob {
    class Content(
        val bytes: ByteArray,
    ) : FilteredBlob

    data object TooLarge : FilteredBlob
}
