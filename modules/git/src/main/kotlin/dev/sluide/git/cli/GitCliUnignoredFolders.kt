package dev.sluide.git.cli

import dev.sluide.git.GitCancellation
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/** The most bytes [unignoredFolders] reads: one record already proves a folder, so a cut-off listing still answers. */
private const val MAX_UNIGNORED_LISTING = 1L * 1024 * 1024

private val UNIGNORED_TIMEOUT = 10.seconds

/**
 * The [folders] (each below [root], the repository root) that hold an untracked file git does not ignore, in their
 * order: `git ls-files --others --exclude-standard -z -- <folders>` with `--literal-pathspecs`, run in [root]; a path
 * it lists under a folder puts that folder in the answer. Git decides, so `*.class` lines, a `build/` line, negations
 * and global excludes all count. A folder that is missing or empty is not listed.
 *
 * `null` if git is not available, a folder is not below [root] (or is [root]), git fails or takes longer than 10 s, or
 * [cancellation] stops it.
 */
internal fun GitCli.unignoredFolders(
    root: Path,
    folders: List<Path>,
    cancellation: GitCancellation?,
    maxListing: Long = MAX_UNIGNORED_LISTING,
): List<Path>? {
    if (!available) return null
    val repository = root.toAbsolutePath().normalize()
    val relative =
        folders.map { folder ->
            val below = repository.relativize(folder.toAbsolutePath().normalize())
            if (below.toString().isEmpty() || below.startsWith("..")) return null
            below.joinToString("/") + "/"
        }
    val execution =
        execute(
            listOf(
                "--no-optional-locks",
                "--literal-pathspecs",
                "ls-files",
                "--others",
                "--exclude-standard",
                "-z",
                "--",
            ) +
                relative,
            repository,
            UNIGNORED_TIMEOUT,
            null,
            write = false,
            cancellation = cancellation,
            maxOutput = maxListing,
        ) ?: return null
    if (execution.cancelled || !execution.complete) return null
    // Stopped for writing too much: its exit value is that of a killed process.
    if (!execution.overflowed && execution.exitValue != 0) return null
    val listed = nulRecords(execution.output.toString(Charsets.UTF_8), execution.overflowed)
    return folders.filterIndexed { index, _ -> listed.any { it.startsWith(relative[index]) } }
}
