package dev.sluide.git.cli

import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/** Where `refs/stash` points as read once: the [commit], `null` while there is no stash. */
internal data class StashTip(
    val commit: String?,
)

/**
 * Where `refs/stash` of the repository at [root] points ([StashTip]); `null` if that cannot be read (`git
 * for-each-ref`, which succeeds without output while there is no stash).
 */
internal fun GitCli.stashTip(root: Path): StashTip? = refTips(root, listOf(STASH))?.let { StashTip(it[STASH]) }

/**
 * Whether git kept local changes it stashed for a merge or rebase (`--autostash`) as a stash entry: `refs/stash` moved
 * from [before] to [after] (ADRs 0105, 0106). Only if both were read: a read that failed tells nothing.
 */
internal fun stashKept(
    before: StashTip?,
    after: StashTip?,
): Boolean = before != null && after != null && before != after

private const val STASH = "refs/stash"

/**
 * The paths of the files the stash [commit] (a full hash) records in its untracked tree (its third parent, made with
 * `--include-untracked`): `git ls-tree -r -z --name-only <commit>^3`; empty if it has none or that cannot be read.
 */
internal fun GitCli.stashUntrackedPaths(
    root: Path,
    commit: String,
): List<String> {
    if (!available || commit.startsWith('-')) return emptyList()
    val arguments = listOf("--no-optional-locks", "ls-tree", "-r", "-z", "--name-only", "$commit^3", "--")
    return run(arguments, root, 10.seconds)?.split('\u0000')?.filter(String::isNotEmpty).orEmpty()
}

/**
 * The tracked paths the stash [commit] (a full hash) changes against the commit it was made on: `git diff
 * --name-only -z --no-renames <commit>^1 <commit>`; `null` if that cannot be read.
 */
internal fun GitCli.stashTrackedPaths(
    root: Path,
    commit: String,
): Set<String>? {
    if (!available || commit.startsWith('-')) return null
    val arguments =
        listOf("--no-optional-locks", "diff", "--name-only", "-z", "--no-renames", "$commit^1", commit, "--")
    return run(arguments, root, 10.seconds)?.split('\u0000')?.filter(String::isNotEmpty)?.toSet()
}

/**
 * The state of each tracked file `git status --porcelain=v2 -z --untracked-files=no` lists in the repository at
 * [root], by path (the record without its path; a rename's under its new path); files without changes are not listed.
 * `null` if that cannot be read.
 */
internal fun GitCli.trackedStates(root: Path): Map<String, String>? {
    if (!available) return null
    val arguments = listOf("--no-optional-locks", "status", "--porcelain=v2", "-z", "--untracked-files=no")
    val records = run(arguments, root, 10.seconds)?.split('\u0000') ?: return null
    val states = HashMap<String, String>()
    var index = 0
    while (index < records.size) {
        val record = records[index++]
        // Ordinary "1" (8 fields before the path), renamed or copied "2" (9, then the original path), unmerged "u" (10).
        val fields =
            when (record.firstOrNull()) {
                '1' -> 8
                '2' -> 9
                'u' -> 10
                else -> continue
            }
        val parts = record.split(' ', limit = fields + 1)
        if (parts.size == fields + 1) states[parts[fields]] = parts.take(fields).joinToString(" ")
        if (record.first() == '2') index++
    }
    return states
}
