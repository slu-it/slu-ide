package dev.sluide.git

import dev.sluide.git.cli.GitCli
import dev.sluide.git.cli.StashTip
import dev.sluide.git.cli.canPassArgument
import dev.sluide.git.cli.commitOfRevision
import dev.sluide.git.cli.currentBranch
import dev.sluide.git.cli.headCommit
import dev.sluide.git.cli.inProgress
import dev.sluide.git.cli.isAncestor
import dev.sluide.git.cli.namesOnly
import dev.sluide.git.cli.refusal
import dev.sluide.git.cli.stashKept
import dev.sluide.git.cli.stashTip
import dev.sluide.git.cli.unmergedEntries
import dev.sluide.git.cli.unmergedPaths
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Merges a branch into the current one, and continues or aborts a merge in progress, also one started in a terminal or
 * by `git pull` (ADR 0105), with the git command line: hooks and the user's configuration apply as for git itself,
 * except what the dialog decides (`merge.ff`, `merge.autoStash`). Every method runs git on the calling thread and may
 * take 10 minutes; none can be cancelled. Paths go to git's standard input and messages into a file, never as
 * arguments (ADR 0038).
 */
@Component
class GitMerges(
    private val git: GitCli,
) {
    /** Whether the JVM can name the working-tree file at a path (ADR 0038); tests take the fallback through git. */
    internal var canNameFile: (String) -> Boolean = { canPassArgument(it) }

    private val conflicts = GitConflictCheck(git) { canNameFile(it) }

    /**
     * Merges [ref] (the full refname of a local or remote branch) into [branch] (a short name, expected to be the
     * current branch): `git merge <mode> --autostash|--no-autostash --no-edit [--file=<file>] --end-of-options <name>`,
     * with [message] in a temporary file if given (UTF-8, cleaned up as the Commit tab's), else git's own message.
     * [autostash] stashes local changes first and applies them when the merge ends, also through [continueMerge],
     * [abortMerge] or a commit in a terminal; never for a squash, after which git would keep them stashed until the
     * next commit (git 2.53), while local changes the squash does not touch stay as they are anyway. The name is the
     * branch's short name, so that git's message names it as in a terminal, unless that name also stands for another
     * ref (a tag of the same name): then the full refname, with [defaultMergeMessage] if there is no [message]. Refused
     * without running git while git is in the middle of anything (or that cannot be read) or [branch] is no longer the
     * current branch; [GitMergeResult.UpToDate] without running git if [ref] is already contained in `HEAD`.
     */
    fun merge(
        root: Path,
        branch: String,
        ref: String,
        mode: GitMergeMode,
        message: String?,
        autostash: Boolean,
    ): GitMergeResult {
        refusal(ref)?.let { return GitMergeResult.Failed(it) }
        val busy = git.inProgress(root).getOrElse { return GitMergeResult.Failed(IN_PROGRESS_UNREADABLE) }
        if (busy != null) return GitMergeResult.Failed(busyMessage(busy))
        if (git.currentBranch(root) != "refs/heads/$branch") {
            return GitMergeResult.Failed("'$branch' is no longer the current branch.")
        }
        val contained =
            git.isAncestor(root, ref, "HEAD")
                ?: return GitMergeResult.Failed("Whether '$branch' already contains $ref could not be read.")
        if (contained) return GitMergeResult.UpToDate
        val theirs = git.commitOfRevision(root, ref) ?: return GitMergeResult.Failed("$ref could not be read.")
        val short = ref.removePrefix(HEADS).removePrefix(REMOTES)
        val unique = short != ref && git.namesOnly(root, short, ref)
        val text = message ?: if (unique) null else defaultMergeMessage(ref, branch)
        // git merge reads no message from its standard input (git 2.53: "could not read file '-'").
        val file =
            try {
                text?.let { Files.createTempFile("sluide-merge-", ".txt") }
            } catch (e: IOException) {
                return GitMergeResult.Failed("The message could not be written: ${e.message}")
            }
        try {
            try {
                file?.let { Files.writeString(it, text) }
            } catch (e: IOException) {
                return GitMergeResult.Failed("The message could not be written: ${e.message}")
            }
            if (file != null && !canPassArgument(file.toString())) {
                return GitMergeResult.Failed("'$file' cannot be passed to git in this locale.")
            }
            val arguments =
                buildList {
                    // The message is UTF-8, whatever i18n.commitEncoding says.
                    if (file != null) addAll(listOf("-c", "i18n.commitEncoding=UTF-8"))
                    add("merge")
                    addAll(mode.arguments)
                    add(if (autostash && mode != GitMergeMode.SQUASH) "--autostash" else "--no-autostash")
                    add("--no-edit")
                    if (file != null) addAll(listOf("--cleanup=whitespace", "--file=$file"))
                    addAll(listOf("--end-of-options", if (unique) short else ref))
                }
            return run(root, arguments, mode, theirs)
        } finally {
            file?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }

    /**
     * Runs the `git merge` of [arguments] in [mode], merging the commit [theirs]; what it gave, read afterwards. A
     * squash is done if git exited with 0, or with 1 leaving conflicts: none were there before, as unmerged files
     * refuse the merge before git runs (ADR 0107); git's own refusals exit with other values.
     */
    private fun run(
        root: Path,
        arguments: List<String>,
        mode: GitMergeMode,
        theirs: String,
    ): GitMergeResult {
        val before = git.headCommit(root)
        val stash = git.stashTip(root)
        val (result, exit) =
            git.writeWithExit(root, arguments, timeout = GitCli.CHECKOUT_TIMEOUT, writesFiles = true)
        val after = git.inProgress(root).getOrNull()
        // A squash's conflicts leave nothing in progress but the conflicts themselves (ADR 0107).
        val nothingElse = after == null || after is GitPendingConflicts
        if (nothingElse && mode == GitMergeMode.SQUASH) {
            val left = git.unmergedPaths(root).orEmpty()
            if (exit == 0 || (exit == 1 && left.isNotEmpty())) {
                val kept = stashKept(stash, git.stashTip(root))
                return GitMergeResult.Done(git.headCommit(root), squashed = true, conflicts = left, stashKept = kept)
            }
        }
        return outcome(root, result, after, stash) { head -> head != before && head == theirs }
    }

    /**
     * Continues the merge in progress (ADR 0105), with the checks of continuing a cherry-pick (ADR 0042): conflict
     * markers left ([GitMergeResult.MarkersLeft]) or files that cannot be checked and are not all among the [confirmed]
     * ones ([GitMergeResult.Unchecked]) refuse it, nothing done; then exactly the unmerged files are staged and `git
     * merge --continue` commits with `GIT_EDITOR=:`, which keeps the message git prepared (`MERGE_MSG`, its comment
     * lines dropped). Local changes git stashed for the merge are applied then.
     */
    fun continueMerge(
        root: Path,
        confirmed: Set<GitUncheckedConflict> = emptySet(),
    ): GitMergeResult {
        pending(root).onFailure { return GitMergeResult.Failed(it.message.orEmpty()) }
        val entries = git.unmergedEntries(root) ?: return GitMergeResult.Failed(CONFLICTS_UNREADABLE)
        if (entries.isNotEmpty()) {
            val check = conflicts.check(root, entries) ?: return GitMergeResult.Failed(CONFLICTS_UNREADABLE)
            if (check.markers.isNotEmpty()) return GitMergeResult.MarkersLeft(check.markers)
            check.refusal?.let { return GitMergeResult.Failed(it) }
            if (!confirmed.containsAll(check.unchecked)) return GitMergeResult.Unchecked(check.unchecked)
            val staged = conflicts.stage(root, entries)
            if (!staged.success) return GitMergeResult.Failed(staged.message)
        }
        val stash = git.stashTip(root)
        val (result, _) =
            git.writeWithExit(
                root,
                listOf("merge", "--continue"),
                timeout = GitCli.CHECKOUT_TIMEOUT,
                environment = mapOf("GIT_EDITOR" to ":"),
                writesFiles = true,
            )
        return outcome(root, result, git.inProgress(root).getOrNull(), stash) { false }
    }

    /**
     * Aborts the merge in progress: `git merge --abort`, which takes the index and files back to before it and applies
     * local changes git stashed for it.
     */
    fun abortMerge(root: Path): GitMergeResult {
        pending(root).onFailure { return GitMergeResult.Failed(it.message.orEmpty()) }
        val stash = git.stashTip(root)
        val result = git.write(root, listOf("merge", "--abort"), timeout = GitCli.CHECKOUT_TIMEOUT, writesFiles = true)
        return outcome(root, result, git.inProgress(root).getOrNull(), stash) { false }
    }

    /**
     * The merge in progress; a failure with the message why nothing is done if there is none or that cannot be read.
     */
    private fun pending(root: Path): Result<GitPendingMerge> {
        val busy = git.inProgress(root).getOrElse { return refused(IN_PROGRESS_UNREADABLE) }
        return when (busy) {
            is GitPendingMerge -> Result.success(busy)
            null -> refused("No merge is in progress.")
            else -> refused(busyMessage(busy))
        }
    }

    private fun refused(message: String) = Result.failure<GitPendingMerge>(IllegalStateException(message))

    /**
     * What a merge command that gave [result] left, git being in the middle of [after] then: a merge still in progress
     * is [GitMergeResult.Stopped] (also when git succeeded), none after success is [GitMergeResult.Done] ([fastForward]
     * tells by the new `HEAD` whether it was one), none after a failure [GitMergeResult.Failed] with git's message. The
     * stash kept is told by `refs/stash` other than [stashBefore] ([stashKept]): an autostash git could not apply is
     * stored there.
     */
    private fun outcome(
        root: Path,
        result: GitResult,
        after: GitInProgress?,
        stashBefore: StashTip?,
        fastForward: (head: String?) -> Boolean,
    ): GitMergeResult =
        when {
            after is GitPendingMerge -> {
                GitMergeResult.Stopped(after, result.message)
            }

            result.success -> {
                val head = git.headCommit(root)
                val kept = stashKept(stashBefore, git.stashTip(root))
                GitMergeResult.Done(head, fastForward = fastForward(head), stashKept = kept)
            }

            else -> {
                GitMergeResult.Failed(result.message)
            }
        }

    private companion object {
        const val HEADS = "refs/heads/"
        const val REMOTES = "refs/remotes/"
    }
}
