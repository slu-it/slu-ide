package dev.sluide.git.cli

import dev.sluide.git.GitCancellation
import dev.sluide.git.GitPendingMerge
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/**
 * The merge in progress in the repository at [root] (ADR 0105), read from its [mergeHead] (`MERGE_HEAD`, one commit
 * per line, several for an octopus merge) and [mergeMessage] (`MERGE_MSG`, its first non-blank line that is not a
 * comment). A file that cannot be read leaves its field empty; `null` only if the unmerged paths cannot be read.
 */
internal fun GitCli.pendingMerge(
    root: Path,
    mergeHead: Path,
    mergeMessage: Path,
    cancellation: GitCancellation? = null,
): GitPendingMerge? {
    fun lines(file: Path): List<String> =
        try {
            Files.readAllLines(file).map(String::trim)
        } catch (_: IOException) {
            emptyList()
        }
    val heads = lines(mergeHead).filter { line -> line.isNotEmpty() && line.all { it in '0'..'9' || it in 'a'..'f' } }
    val message = lines(mergeMessage).firstOrNull { it.isNotEmpty() && !it.startsWith('#') }
    val conflicts = unmergedPaths(root, cancellation = cancellation) ?: return null
    return GitPendingMerge(heads, message, conflicts)
}

/**
 * Whether the short name [name] stands for the full refname [ref] alone in the repository at [root]: `git rev-parse
 * --symbolic-full-name`, which prints nothing for a name that is ambiguous (a tag of the same name, as git warns) and
 * the first match if the warning is turned off; `false` if git is not available or fails.
 */
internal fun GitCli.namesOnly(
    root: Path,
    name: String,
    ref: String,
): Boolean {
    if (!available || name.startsWith('-')) return false
    // No --end-of-options: rev-parse would print it as a name; a name never starts with '-' here.
    val arguments = listOf("--no-optional-locks", "rev-parse", "--symbolic-full-name", name)
    return run(arguments, root, 3.seconds)?.trim() == ref
}
