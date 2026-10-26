package dev.sluide.git.cli

import dev.sluide.git.GitCancellation
import dev.sluide.git.parse.GitDiffParser
import dev.sluide.git.parse.GitStatusParser
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/**
 * The output of `git status --porcelain=v2 --branch -z --ignored` for the working tree at [root] (see
 * [GitStatusParser]), or `null` if git is not available, fails or takes longer than 10 s.
 */
internal fun GitCli.status(root: Path): String? {
    if (!available) return null
    // Without optional locks git does not rewrite .git/index, which would trigger our own watch of .git.
    val arguments = listOf("--no-optional-locks", "status", "--porcelain=v2", "--branch", "-z", "--ignored")
    return run(arguments, root, 10.seconds)
}

/**
 * The bytes of [file] at `HEAD` in the repository at [root], with the filters (line endings, smudge) a checkout
 * would apply, or `null` if git is not available, fails (e.g. the file is not in `HEAD`), takes longer than 3 s or is
 * stopped by [cancellation].
 */
internal fun GitCli.headContent(
    root: Path,
    file: Path,
    cancellation: GitCancellation? = null,
): ByteArray? {
    if (!available) return null
    val path = relativePath(root, file) ?: return null
    return runBytes(listOf("cat-file", "--filters", "HEAD:$path"), root, 3.seconds, cancellation = cancellation)
}

/**
 * The output of `git diff -U0` between `HEAD` and the working tree for [file] in the repository at [root] (see
 * [GitDiffParser]), or `null` if git is not available, fails, takes longer than 3 s or is stopped by [cancellation].
 */
internal fun GitCli.diffToHead(
    root: Path,
    file: Path,
    cancellation: GitCancellation? = null,
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
    return run(arguments, root, 3.seconds, cancellation)
}

/**
 * [file] relative to [root], `/`-separated as git wants it; `null` if it is not inside [root]. Relative paths are
 * taken from the working directory.
 */
private fun GitCli.relativePath(
    root: Path,
    file: Path,
): String? {
    val relative = root.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize())
    if (relative.toString().isEmpty() || relative.startsWith("..")) return null
    return relative.joinToString("/")
}

/**
 * How many tracked files of the working tree at [root] have changes, staged or not, against `HEAD`: the records of
 * `git status --porcelain -z --untracked-files=no --no-renames`. `null` if git is not available, fails or takes
 * longer than 10 s.
 */
internal fun GitCli.trackedChangeCount(root: Path): Int? {
    if (!available) return null
    val arguments =
        listOf("--no-optional-locks", "status", "--porcelain", "-z", "--untracked-files=no", "--no-renames")
    return run(arguments, root, 10.seconds)?.split('\u0000')?.count(String::isNotEmpty)
}
