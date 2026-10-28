package dev.sluide.gitlog.data

import dev.sluide.git.GitBranchDeletion
import dev.sluide.git.GitBranchTip
import dev.sluide.git.GitCancellation
import dev.sluide.git.GitChangedFile
import dev.sluide.git.GitCommitResult
import dev.sluide.git.GitFetchChanges
import dev.sluide.git.GitFetchResult
import dev.sluide.git.GitInProgress
import dev.sluide.git.GitMergeMode
import dev.sluide.git.GitMergeResult
import dev.sluide.git.GitPick
import dev.sluide.git.GitPickResult
import dev.sluide.git.GitPushResult
import dev.sluide.git.GitRebaseResult
import dev.sluide.git.GitRemoteDeletion
import dev.sluide.git.GitRemoteDeletionOutcome
import dev.sluide.git.GitRemoteDeletionResult
import dev.sluide.git.GitRemoteRefResult
import dev.sluide.git.GitResetMode
import dev.sluide.git.GitResult
import dev.sluide.git.GitRewordCheck
import dev.sluide.git.GitRewordResult
import dev.sluide.git.GitUncheckedConflict
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

    /** How many commits of a detached `HEAD` a checkout leaves on no ref. */
    @Volatile
    var leftBehind: Int? = 0

    override fun leftBehind(
        root: Path,
        target: String,
    ): Int? {
        reads += "left behind $target"
        return leftBehind
    }

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

    /** What a cherry-pick, revert or continue answers, by its line. */
    @Volatile
    var pickResult: (String) -> GitPickResult = { GitPickResult.Done(NEW_HEAD) }

    /** Where `HEAD` points. */
    @Volatile
    var head: String? = "h1"

    @Volatile
    var unreachable: Int? = 0

    @Volatile
    var localChanges: Int? = 0

    /** The reads of [leftBehind] and of what a reset would lose, as lines. */
    val reads = CopyOnWriteArrayList<String>()

    private fun pickCall(line: String): GitPickResult {
        call(line)
        return pickResult(line)
    }

    override fun headCommit(root: Path) = head

    override fun pick(
        root: Path,
        kind: GitPick,
        commit: String,
        firstParent: Boolean,
    ) = pickCall("${kind.command} ${if (firstParent) "-m 1 " else ""}$commit")

    /** What git is in the middle of. */
    @Volatile
    var inProgress: GitInProgress? = null

    override fun inProgress(root: Path) = inProgress

    override fun continuePick(
        root: Path,
        kind: GitPick,
        confirmed: Set<GitUncheckedConflict>,
    ): GitPickResult {
        confirmedSets += confirmed
        return pickCall("${kind.command} --continue${if (confirmed.isEmpty()) "" else " as on disk"}")
    }

    /** The conflicted files confirmed to be committed as on disk, for every continue. */
    val confirmedSets = CopyOnWriteArrayList<Set<GitUncheckedConflict>>()

    override fun abortPick(
        root: Path,
        kind: GitPick,
    ) = call("${kind.command} --abort")

    /** What a rebase, continue, skip or abort answers, by its line. */
    @Volatile
    var rebaseResult: (String) -> GitRebaseResult = { GitRebaseResult.Done(NEW_HEAD) }

    /** How many merge commits a rebase would drop; `null`: cannot be read. */
    @Volatile
    var rebaseMerges: Int? = 0

    private fun rebaseCall(line: String): GitRebaseResult {
        call(line)
        return rebaseResult(line)
    }

    override fun rebase(
        root: Path,
        branch: String,
        onto: String,
    ) = rebaseCall("rebase $branch $onto")

    override fun rebaseMergeCount(
        root: Path,
        onto: String,
    ): Int? {
        calls += "rebase merges $onto"
        return rebaseMerges
    }

    override fun continueRebase(
        root: Path,
        confirmed: Set<GitUncheckedConflict>,
    ): GitRebaseResult {
        confirmedSets += confirmed
        return rebaseCall("rebase --continue${if (confirmed.isEmpty()) "" else " as on disk"}")
    }

    override fun skipRebase(root: Path) = rebaseCall("rebase --skip")

    override fun abortRebase(root: Path) = rebaseCall("rebase --abort")

    /** What a merge, continue or abort answers, by its line. */
    @Volatile
    var mergeResult: (String) -> GitMergeResult = { GitMergeResult.Done(NEW_HEAD) }

    private fun mergeCall(line: String): GitMergeResult {
        call(line)
        return mergeResult(line)
    }

    override fun merge(
        root: Path,
        branch: String,
        ref: String,
        mode: GitMergeMode,
        message: String?,
        autostash: Boolean,
    ): GitMergeResult {
        val options = listOfNotNull(mode.name.lowercase(), message?.let { "-m $it" }, "autostash".takeIf { autostash })
        return mergeCall("merge $branch $ref ${options.joinToString(" ")}")
    }

    override fun continueMerge(
        root: Path,
        confirmed: Set<GitUncheckedConflict>,
    ): GitMergeResult {
        confirmedSets += confirmed
        return mergeCall("merge --continue${if (confirmed.isEmpty()) "" else " as on disk"}")
    }

    override fun abortMerge(root: Path) = mergeCall("merge --abort")

    /** What the check of a reword answers. */
    @Volatile
    var rewordCheck: GitRewordCheck = GitRewordCheck.Allowed("Old message", emptyList(), null)

    /** What a reword answers. */
    @Volatile
    var rewordResult: GitRewordResult = GitRewordResult.Done(NEW_HEAD)

    override fun rewordCheck(
        root: Path,
        branch: String,
        commit: String,
    ): GitRewordCheck {
        calls += "reword check $branch $commit"
        return rewordCheck
    }

    override fun reword(
        root: Path,
        branch: String,
        commit: String,
        message: String,
    ): GitRewordResult {
        calls += "reword $branch $commit $message"
        return rewordResult
    }

    override fun unreachableCount(
        root: Path,
        target: String,
    ): Int? {
        reads += "unreachable $target"
        return unreachable
    }

    override fun localChangeCount(root: Path): Int? {
        reads += "local changes"
        return localChanges
    }

    @Volatile
    var stagedOnly: Int? = 0

    override fun stagedOnlyCount(root: Path): Int? {
        reads += "staged only"
        return stagedOnly
    }

    @Volatile
    var softRefused: Boolean? = false

    override fun softResetRefused(root: Path): Boolean? {
        reads += "soft refused"
        return softRefused
    }

    @Volatile
    var pickOverwritten: List<String>? = emptyList()

    override fun pickOverwrites(
        root: Path,
        kind: GitPick,
        commit: String,
        firstParent: Boolean,
    ): List<String>? {
        reads += "pick overwrites $commit"
        return pickOverwritten
    }

    @Volatile
    var overwritten: List<String>? = emptyList()

    override fun overwrittenFiles(
        root: Path,
        target: String,
    ): List<String>? {
        reads += "overwritten $target"
        return overwritten
    }

    override fun reset(
        root: Path,
        target: String,
        mode: GitResetMode,
    ) = call("reset ${mode.option} $target")

    /** The remotes to fetch; `null` if they cannot be read. */
    @Volatile
    var remotes: List<String>? = listOf("origin")

    /** What fetching a remote gives, by its name, unless the fetch was cancelled. */
    @Volatile
    var fetchResult: (String) -> GitFetchResult = { GitFetchResult(GitResult(true, ""), changes = GitFetchChanges()) }

    override fun remotes(root: Path) = remotes

    /** Waits for the [gate] like the others, but ends as soon as it is cancelled, as git would be stopped. */
    override fun fetch(
        root: Path,
        remote: String,
        cancellation: GitCancellation,
    ): GitFetchResult {
        threads += Thread.currentThread().name
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (gate?.await(10, TimeUnit.MILLISECONDS) == false && !cancellation.cancelled) {
            if (System.nanoTime() > deadline) break
        }
        calls += "fetch $remote"
        if (cancellation.cancelled) return GitFetchResult(GitResult(false, "Cancelled."), cancelled = true)
        return fetchResult(remote)
    }

    /** The commit a commit makes; `null` as if it could not be read afterwards. */
    @Volatile
    var committed: String? = NEW_HEAD

    /**
     * Records `commit [--amend] <paths>|<untracked>: <message>` (paths comma-separated, the message's line breaks as
     * `\n`) and answers with [result] for it; a success with [committed].
     */
    override fun commit(
        root: Path,
        message: String,
        files: List<GitChangedFile>,
        untracked: List<String>,
        amend: Boolean,
        cancellation: GitCancellation,
    ): GitCommitResult {
        val option = if (amend) " --amend" else ""
        val paths = files.joinToString(",") { it.path }
        val result = call("commit$option $paths|${untracked.joinToString(",")}: ${message.replace("\n", "\\n")}")
        return GitCommitResult(result, if (result.success) committed else null)
    }

    /** What a push gives, by its line, unless it was cancelled. */
    @Volatile
    var pushResult: (String) -> GitPushResult = { GitPushResult(GitResult(true, "")) }

    /**
     * Records `push [-u] [--force-with-lease=<target>:<expected>] <remote> <branch>:<target>` and answers with
     * [pushResult]; waits for the [gate] like a fetch, ending as soon as it is cancelled.
     */
    override fun push(
        root: Path,
        remote: String,
        branch: String,
        target: String,
        force: Boolean,
        expected: String?,
        setUpstream: Boolean,
        cancellation: GitCancellation,
    ): GitPushResult {
        threads += Thread.currentThread().name
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (gate?.await(10, TimeUnit.MILLISECONDS) == false && !cancellation.cancelled) {
            if (System.nanoTime() > deadline) break
        }
        val upstream = if (setUpstream) "-u " else ""
        val lease = if (force) "--force-with-lease=$target:${expected.orEmpty()} " else ""
        val line = "push $upstream$lease$remote $branch:$target"
        calls += line
        if (cancellation.cancelled) return GitPushResult(GitResult(false, "Cancelled."), cancelled = true)
        return pushResult(line)
    }

    /** The branch of a remote a remote-tracking ref keeps, by remote and ref; by default the usual refspec's. */
    @Volatile
    var remoteBranch: (String, String) -> String? = { remote, ref ->
        ref.takeIf { it.startsWith("refs/remotes/$remote/") }?.let {
            "refs/heads/" +
                it.removePrefix("refs/remotes/$remote/")
        }
    }

    override fun remoteBranch(
        root: Path,
        remote: String,
        trackingRef: String,
    ): String? {
        threads += Thread.currentThread().name
        return remoteBranch(remote, trackingRef)
    }

    /** What deleting refs on a remote gives, by its line, unless it was cancelled; by default all are deleted. */
    @Volatile
    var deleteRemoteResult: (String, List<GitRemoteDeletion>) -> GitRemoteDeletionResult = { _, refs ->
        GitRemoteDeletionResult(
            GitResult(true, ""),
            outcomes = refs.associate { it.ref to GitRemoteRefResult(GitRemoteDeletionOutcome.DELETED) },
        )
    }

    /**
     * Records `push --delete <remote> <ref>[:<expected>]…` and answers with [deleteRemoteResult]; waits for the [gate]
     * like a push, ending as soon as it is cancelled.
     */
    override fun deleteRemoteRefs(
        root: Path,
        remote: String,
        refs: List<GitRemoteDeletion>,
        cancellation: GitCancellation,
    ): GitRemoteDeletionResult {
        threads += Thread.currentThread().name
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (gate?.await(10, TimeUnit.MILLISECONDS) == false && !cancellation.cancelled) {
            if (System.nanoTime() > deadline) break
        }
        val line =
            "push --delete $remote " +
                refs.joinToString(" ") { ref -> ref.ref + ref.expected?.let { ":$it" }.orEmpty() }
        calls += line
        if (cancellation.cancelled) return GitRemoteDeletionResult(GitResult(false, "Cancelled."), cancelled = true)
        return deleteRemoteResult(line, refs)
    }

    companion object {
        /** The commit a cherry-pick or revert makes by default. */
        const val NEW_HEAD = "5e6f7a8b9c0d"
    }
}
