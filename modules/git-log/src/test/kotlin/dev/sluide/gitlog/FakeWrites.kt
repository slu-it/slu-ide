package dev.sluide.gitlog

import dev.sluide.git.GitBranchDeletion
import dev.sluide.git.GitBranchTip
import dev.sluide.git.GitResult
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A fake of the git log's write side: records every call as a line (`switch main`, `branch -d a b`) and answers with
 * [result] for it; [notDeleted] tells which branches `deleteBranches` leaves. Calls wait for [gate] while it is not
 * `null`, and record the thread they run on.
 */
internal class FakeWrites : GitLogWrites {
    val calls = CopyOnWriteArrayList<String>()
    val threads = CopyOnWriteArrayList<String>()

    @Volatile
    var result: (String) -> GitResult = { GitResult(true, "") }

    @Volatile
    var notDeleted: (List<String>) -> List<String> = { emptyList() }

    @Volatile
    var localBranches: Set<String> = emptySet()

    @Volatile
    var unpassable: Set<String> = emptySet()

    @Volatile
    var gate: CountDownLatch? = null

    /** How often a call was interrupted while it waited for the [gate]. */
    @Volatile
    var interruptions = 0

    private fun call(line: String): GitResult {
        threads += Thread.currentThread().name
        try {
            gate?.await(10, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            interruptions++
            Thread.currentThread().interrupt()
        }
        calls += line
        return result(line)
    }

    override fun canPass(argument: String) = argument !in unpassable

    override fun switchBranch(
        root: Path,
        branch: String,
    ) = call("switch $branch")

    override fun switchDetached(
        root: Path,
        revision: String,
    ) = call("switch --detach $revision")

    override fun switchTracking(
        root: Path,
        remoteBranch: String,
    ) = call("switch --track $remoteBranch")

    override fun branchExists(
        root: Path,
        name: String,
    ) = name in localBranches

    /** The commits of the local branches, by name. */
    @Volatile
    var tips: Map<String, String> = emptyMap()

    /** Whether the first commit is an ancestor of the second. */
    @Volatile
    var ancestry: (String, String) -> Boolean? = { _, _ -> true }

    /** The branches whose commit cannot be read. */
    @Volatile
    var unreadable: Set<String> = emptySet()

    override fun branchTip(
        root: Path,
        name: String,
    ) = when {
        name in unreadable -> GitBranchTip.Unknown
        name in tips -> GitBranchTip.At(tips.getValue(name))
        else -> GitBranchTip.Missing
    }

    override fun isAncestor(
        root: Path,
        ancestor: String,
        descendant: String,
    ) = ancestry(ancestor, descendant)

    override fun createBranch(
        root: Path,
        name: String,
        startPoint: String,
        checkout: Boolean,
        overwrite: Boolean,
    ) = if (checkout) {
        call("switch ${if (overwrite) "-C" else "-c"} $name $startPoint")
    } else {
        call("branch ${if (overwrite) "-f " else ""}$name $startPoint")
    }

    override fun createTag(
        root: Path,
        name: String,
        commit: String,
        message: String?,
    ) = if (message.isNullOrBlank()) call("tag $name $commit") else call("tag -a $name $commit: $message")

    override fun renameBranch(
        root: Path,
        old: String,
        new: String,
    ) = call("branch -m $old $new")

    override fun deleteBranches(
        root: Path,
        names: List<String>,
        force: Boolean,
    ): GitBranchDeletion {
        val result = call("branch ${if (force) "-D" else "-d"} ${names.joinToString(" ")}")
        return GitBranchDeletion(result, if (result.success) emptyList() else notDeleted(names))
    }

    override fun deleteTags(
        root: Path,
        names: List<String>,
    ) = call("tag -d ${names.joinToString(" ")}")
}
