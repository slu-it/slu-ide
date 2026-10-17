package dev.sluide.gitlog

import dev.sluide.git.GitPushResult
import dev.sluide.git.GitResult
import java.nio.file.Path

/**
 * Pushes the local [branch] to the [target] branch of [remote] (ADR 0049), with [force] only if the remote branch is
 * at [expected] (the commit the Push dialog showed, `null` for a branch the remote does not have), making the target
 * the branch's upstream if [setUpstream]. [count] is the number of commits the dialog showed, [more] that there were
 * more. It changes no files, so no unsaved editors are asked about; cancellable, as a fetch (ADR 0043). Cancelled, the
 * header says so and no dialog is shown.
 */
internal data class PushOperation(
    val remote: String,
    val branch: String,
    val target: String,
    val force: Boolean,
    val expected: String?,
    val setUpstream: Boolean,
    val count: Int,
    val more: Boolean = false,
) : RefOperation {
    override val progress get() = "${if (force) "Force pushing" else "Pushing"} $branch to $remote/$target…"
    override val errorTitle get() = "Push Failed"
    override val failure get() = "$branch could not be pushed to $remote/$target."
    override val fileChange get() = null
    override val cancellable get() = true

    override fun run(
        writes: GitLogWrites,
        root: Path,
    ) = run(writes, root, RunContext())

    override fun run(
        writes: GitLogWrites,
        root: Path,
        context: RunContext,
    ): RefOutcome {
        val pushed =
            writes.push(root, remote, branch, target, force, expected, setUpstream, context.cancellation)
        if (pushed.cancelled || (!pushed.result.success && context.cancellation.cancelled)) {
            return RefOutcome(GitResult(true, "", changed = false), PUSH_CANCELLED)
        }
        if (!pushed.result.success) return RefOutcome(pushed.result, null)
        return RefOutcome(pushed.result, pushSummary(this, pushed))
    }
}

/** What the header says when a push was cancelled. */
internal const val PUSH_CANCELLED = "Push cancelled."

/**
 * What the header says after the successful push [operation] gave [pushed]: "Everything up to date." if the remote had
 * it already, "Pushed main to origin/feature (new branch).", "Force pushed main to origin/main.", "Pushed 3 commits to
 * origin/main." ("1000+ commits" when there were more than shown), or "Pushed main to origin/main." without commits.
 */
internal fun pushSummary(
    operation: PushOperation,
    pushed: GitPushResult,
): String {
    val where = "${operation.remote}/${operation.target}"
    return when {
        pushed.upToDate -> "Everything up to date."
        pushed.created -> "Pushed ${operation.branch} to $where (new branch)."
        operation.force -> "Force pushed ${operation.branch} to $where."
        operation.count == 0 -> "Pushed ${operation.branch} to $where."
        else -> "Pushed ${commitCount(operation.count, operation.more)} to $where."
    }
}

/** "1 commit", "3 commits", "1000+ commits" if there are [more]. */
internal fun commitCount(
    count: Int,
    more: Boolean = false,
): String =
    when {
        more -> "$count+ commits"
        count == 1 -> "1 commit"
        else -> "$count commits"
    }

/** The branches never force pushed to (ADR 0049); a setting may come later. */
internal val PROTECTED_BRANCHES = setOf("main", "master")

/**
 * Whether force pushing to the remote branch [target] is not allowed: it is one of the [PROTECTED_BRANCHES], in any
 * case, as some remotes treat refs case-insensitively (`Main` on such a server is `main`).
 */
internal fun isProtectedBranch(target: String) = PROTECTED_BRANCHES.any { it.equals(target, ignoreCase = true) }
