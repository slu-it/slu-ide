package dev.sluide.gitlog.operations

import dev.sluide.git.GitFetchResult
import dev.sluide.git.GitMergeMode
import dev.sluide.git.GitMergeResult
import dev.sluide.git.GitPullSources
import dev.sluide.git.GitRebaseResult
import dev.sluide.git.GitResult
import dev.sluide.gitlog.data.GitLogWrites
import java.nio.file.Path

/**
 * What the Pull dialog offers (ADR 0106): to pull into the current [branch] (short name) from one of the
 * [remoteBranches] (full refnames, by short name), each of one of the [remotes], [preselected] (one of them, `null`
 * for none). [fromPush]: asked for by the Push dialog, which then owns the dialog.
 */
internal data class PullPrompt(
    val branch: String,
    val remoteBranches: List<String>,
    val remotes: List<String>,
    val preselected: String?,
    val fromPush: Boolean = false,
)

/** What the header says when a pull is asked for on a detached `HEAD`. */
internal const val PULL_NEEDS_BRANCH = "Pull needs a current branch."

/** What the header says when a pull is asked for on a branch without a commit. */
internal const val PULL_NEEDS_COMMIT = "Pull needs a commit to pull into."

/** What the header says when the repository has no remote. */
internal const val NO_REMOTES_TO_PULL = "The repository has no remotes to pull from."

/** What the header says when no remote branch was fetched yet. */
internal const val NO_REMOTE_BRANCHES = "No remote branches to pull from; fetch first."

/** What the header says when a pull is asked for while another git operation runs. */
internal const val PULL_WAITS = "Wait for the running git operation to finish."

/**
 * Why nothing can be pulled with [sources], as the header says it: what git is in the middle of, no current branch,
 * no commit on it, no remote, no remote branches ([pullPrompt]); `null` if the dialog can ask.
 */
internal fun pullRefusal(sources: GitPullSources): String? =
    when {
        sources.busy != null -> sources.busy
        sources.branch == null -> PULL_NEEDS_BRANCH
        !sources.hasCommit -> PULL_NEEDS_COMMIT
        sources.remotes.isEmpty() -> NO_REMOTES_TO_PULL
        pullableBranches(sources).isEmpty() -> NO_REMOTE_BRANCHES
        else -> null
    }

/**
 * The remote-tracking branches of [sources] that can be pulled: those of a remote ([remoteOfBranch]), not
 * `<remote>/HEAD`, sorted by short name.
 */
private fun pullableBranches(sources: GitPullSources): List<String> =
    sources.remoteBranches
        .filter { ref ->
            val short = ref.removePrefix(REMOTES)
            val remote = remoteOfBranch(short, sources.remotes)
            ref.startsWith(REMOTES) && remote != null && short != "$remote/HEAD"
        }.sortedBy { it.removePrefix(REMOTES) }

/**
 * What the Pull dialog asks with [sources], which have no [pullRefusal]: the branches to pull from, [preselected]
 * (a full refname) if it is one of them, else the current branch's upstream if it is, else none.
 */
internal fun pullPrompt(
    sources: GitPullSources,
    preselected: String?,
    fromPush: Boolean = false,
): PullPrompt {
    val branches = pullableBranches(sources)
    val selected = preselected?.takeIf { it in branches } ?: sources.upstream?.takeIf { it in branches }
    return PullPrompt(sources.branch.orEmpty(), branches, sources.remotes, selected, fromPush)
}

/**
 * Whether the Pull dialog's "Stash local changes" can be checked: always for a rebase, for a merge unless it squashes
 * ([stashes]).
 */
internal fun pullStashes(
    merge: Boolean,
    mode: GitMergeMode,
) = !merge || stashes(mode)

/**
 * Reads what can be pulled (ADR 0106) and asks with the Pull dialog ([RefOutcome.pull]), [preselected] (a full
 * refname) chosen if it can be pulled; if nothing can be pulled the header says why ([pullRefusal]).
 */
internal data class PreparePull(
    val preselected: String? = null,
    val fromPush: Boolean = false,
) : RefOperation {
    override val progress get() = "Reading the branches to pull from…"
    override val errorTitle get() = PULL_FAILED
    override val failure get() = "Nothing can be pulled."
    override val fileChange get() = null

    override fun run(
        writes: GitLogWrites,
        root: Path,
    ): RefOutcome {
        val sources =
            writes.pullSources(root)
                ?: return RefOutcome(
                    GitResult(false, "The branches and remotes of the repository could not be read."),
                    null,
                )
        pullRefusal(sources)?.let { return RefOutcome(GitResult(true, ""), it) }
        return RefOutcome(GitResult(true, ""), null, pull = pullPrompt(sources, preselected, fromPush))
    }
}

/**
 * Pulls the remote-tracking branch [ref] (a full refname) of [remote] into the current [branch] (short name; ADR 0106):
 * fetches [remote] (cancellable; unless [fetched] already, then [fetchNote] says what it brought), then, unless [ref]
 * is gone or already contained in `HEAD`, merges it in [mode] with [message] (or git's own), or rebases onto it with
 * the fork point (`mode == null`); local changes stashed and restored if [autostash]. A rebase asks first, after the
 * fetch, if `HEAD` has merge commits [ref] lacks (or that cannot be read), unless [confirmed]; the confirmed one does
 * not fetch again.
 */
internal data class PullBranch(
    val branch: String,
    val remote: String,
    val ref: String,
    val mode: GitMergeMode?,
    val message: String?,
    val autostash: Boolean,
    val fetched: Boolean = false,
    val fetchNote: String? = null,
    val confirmed: Boolean = false,
    val fromPush: Boolean = false,
) : RefOperation {
    /** The branch pulled from, as the user knows it: `origin/main`. */
    private val label get() = ref.removePrefix(REMOTES)

    override val progress get() = if (fetched) integrating() else "Fetching $remote…"
    override val errorTitle get() = PULL_FAILED
    override val failure get() = "$label could not be pulled into $branch."

    // It merges or rebases: it changes the files.
    override val fileChange get() = "Pull"

    // Only the fetch; the merge or rebase after it is not.
    override val cancellable get() = !fetched

    private fun integrating() = if (mode == null) "Rebasing $branch onto $label…" else "Merging $label into $branch…"

    override fun run(
        writes: GitLogWrites,
        root: Path,
    ) = run(writes, root, RunContext())

    override fun run(
        writes: GitLogWrites,
        root: Path,
        context: RunContext,
    ): RefOutcome {
        var note = fetchNote
        if (!fetched) {
            val fetch = writes.fetch(root, remote, context.cancellation)
            if (fetch.cancelled) return RefOutcome(GitResult(true, ""), PULL_CANCELLED, cancelled = true)
            if (!fetch.result.success) {
                val failed = "$remote could not be fetched; nothing was pulled."
                return RefOutcome(
                    GitResult(false, fetch.result.message),
                    pullFetchNote(remote, fetch),
                    failure = failed,
                )
            }
            note = pullFetchNote(remote, fetch)
            context.uncancellable()
        }
        // Checked by the merge and the rebase too, but the early answers below come first.
        if (writes.currentBranch(root) != branch) {
            return RefOutcome(GitResult(false, "'$branch' is no longer the current branch."), note)
        }
        if (writes.remoteBranchExists(root, ref) == false) {
            return RefOutcome(GitResult(false, "$label no longer exists on $remote."), note)
        }
        if (writes.isAncestor(root, ref, "HEAD") == true) {
            return RefOutcome(GitResult(true, ""), joined(note, "$branch is already up to date with $label."))
        }
        context.progress(integrating())
        val outcome =
            if (mode != null) {
                mergeOutcome(writes.merge(root, branch, ref, mode, message, autostash), label, branch) { done ->
                    pulledText(label, branch, done)
                }
            } else {
                // Where the rebase starts: the fork point, without one the merge base (what ref..HEAD counts from).
                val forkPoint = writes.forkPoint(root, ref)
                if (!confirmed) {
                    val merges = writes.rebaseMergeCount(root, forkPoint ?: ref)
                    if (merges == null || merges > 0) return confirmation(merges, note)
                }
                rebase(writes, root, forkPoint)
            }
        return outcome.copy(done = joined(note, outcome.done))
    }

    /**
     * The rebase onto [ref] from its [forkPoint] (`null`: none, the merge base); a fast-forward if `HEAD` is behind
     * [ref]. The commits of `HEAD` the fork point leaves out, as [ref] no longer has them, are told after a rebase that
     * ran ([leftOutNote]).
     */
    private fun rebase(
        writes: GitLogWrites,
        root: Path,
        forkPoint: String?,
    ): RefOutcome {
        val behind = writes.isAncestor(root, "HEAD", ref) == true
        val leftOut = forkPoint?.let { writes.commitCount(root, ref, it) } ?: 0
        val result = writes.pullRebase(root, branch, ref, autostash)
        val outcome =
            rebaseOutcome(result, branch) {
                val how = if (behind) "fast-forward" else rebasedCommits(writes.commitCount(root, ref, "HEAD"))
                "Pulled $label into $branch ($how)."
            }
        val ran = result is GitRebaseResult.Done || result is GitRebaseResult.Stopped
        if (!ran || leftOut == 0) return outcome
        return outcome.copy(done = joined(outcome.done, leftOutNote(leftOut, label)))
    }

    /**
     * Asks before a rebase that drops [merges] merge commits (`null`: maybe some); confirmed, it runs without fetching
     * again, the header still telling what the fetch brought ([note]).
     */
    private fun confirmation(
        merges: Int?,
        note: String?,
    ): RefOutcome {
        val confirmation =
            RefConfirmation(
                "Pull with Rebase",
                mergesQuestion(branch, label, merges),
                REBASE_BUTTON,
                copy(fetched = true, fetchNote = note, confirmed = true),
            )
        return RefOutcome(GitResult(true, ""), note, confirmation = confirmation)
    }
}

/** What the header says after a pull's fetch was cancelled; nothing was merged or rebased. */
internal const val PULL_CANCELLED = "Pull cancelled."

private const val PULL_FAILED = "Pull Failed"

private const val REMOTES = "refs/remotes/"

/**
 * What the header says first after the fetch of a pull of [remote] gave [fetch]: what it changed, in the words of
 * [fetchSummary]; `null` if it changed nothing (or that could not be read).
 */
internal fun pullFetchNote(
    remote: String,
    fetch: GitFetchResult,
): String? = if (fetch.changes?.none != false) null else fetchSummary(mapOf(remote to fetch), false)

/** What the header says after a pull of [source] into [branch] merged as [done] tells. */
internal fun pulledText(
    source: String,
    branch: String,
    done: GitMergeResult.Done,
): String =
    when {
        done.squashed -> mergedText(source, branch, done)
        done.fastForward -> "Pulled $source into $branch (fast-forward)."
        else -> "Pulled $source into $branch (merge commit)."
    }

/**
 * What the header adds when the fork point left [count] commits of `HEAD` out of a rebase onto [source], as it no
 * longer has them (rewritten by a force push; ADR 0106).
 */
internal fun leftOutNote(
    count: Int,
    source: String,
): String {
    val (commits, them) =
        when (count) {
            1 -> "1 commit that $source no longer has was" to "it"
            else -> "$count commits that $source no longer has were" to "them"
        }
    return "$commits left out, as git pull --rebase does; ORIG_HEAD still points to $them."
}

/** Whether [operation] is a pull started in the Push dialog, whose dialogs it then owns (ADR 0106). */
internal fun startedInPush(operation: RefOperation) =
    (operation as? PreparePull)?.fromPush == true || (operation as? PullBranch)?.fromPush == true

/** "rebased 1 commit", "rebased 3 commits", or "rebased" if [count] is not known. */
private fun rebasedCommits(count: Int?) =
    when (count) {
        null -> "rebased"
        1 -> "rebased 1 commit"
        else -> "rebased $count commits"
    }

/** The texts that are there, joined; `null` for none. */
private fun joined(vararg texts: String?): String? = texts.filterNotNull().joinToString(" ").ifEmpty { null }
