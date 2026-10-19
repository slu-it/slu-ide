package dev.sluide.git.cli

import dev.sluide.git.GitCancellation
import dev.sluide.git.GitPendingRebase
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The rebase in progress in the repository at [root] (ADR 0063), read from its state [directory]: `rebase-merge` for
 * the merge backend, `rebase-apply` for the [apply] one (`git rebase --apply`, not `git am`). A state file that is
 * missing or cannot be read leaves its field `null`; `null` only if the unmerged paths cannot be read.
 */
internal fun GitCli.pendingRebase(
    root: Path,
    directory: Path,
    apply: Boolean,
    cancellation: GitCancellation? = null,
): GitPendingRebase? {
    fun read(name: String): String? =
        try {
            Files.readString(directory.resolve(name)).trim().ifEmpty { null }
        } catch (_: IOException) {
            null
        }
    val branch = read("head-name")?.takeIf { it.startsWith(BRANCH_PREFIX) }?.removePrefix(BRANCH_PREFIX)
    val onto = read("onto")?.takeIf { hash -> hash.all { it in '0'..'9' || it in 'a'..'f' } }
    val step = read(if (apply) "next" else "msgnum")?.toIntOrNull()
    val total = read(if (apply) "last" else "end")?.toIntOrNull()
    val stopped = commitOfRevision(root, "REBASE_HEAD", cancellation = cancellation)
    val conflicts = unmergedPaths(root, cancellation = cancellation) ?: return null
    return GitPendingRebase(branch, onto, stopped, step, total, conflicts)
}

private const val BRANCH_PREFIX = "refs/heads/"
