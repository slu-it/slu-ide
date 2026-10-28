package dev.sluide.git.cli

import java.nio.file.Path

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
