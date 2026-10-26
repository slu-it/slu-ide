package dev.sluide.git

import dev.sluide.git.cli.BranchRef
import dev.sluide.git.cli.GitCli
import dev.sluide.git.cli.ancestryPath
import dev.sluide.git.cli.branchRefs
import dev.sluide.git.cli.branchesContaining
import dev.sluide.git.cli.canPassArgument
import dev.sluide.git.cli.commitMessage
import dev.sluide.git.cli.committerIdent
import dev.sluide.git.cli.currentBranch
import dev.sluide.git.cli.headCommit
import dev.sluide.git.cli.inProgress
import dev.sluide.git.cli.isAncestor
import dev.sluide.git.cli.moveBranch
import dev.sluide.git.cli.rawCommits
import dev.sluide.git.cli.refusal
import dev.sluide.git.cli.remoteNames
import dev.sluide.git.cli.rewordedCommit
import dev.sluide.git.cli.stripSpace
import dev.sluide.git.cli.writeCommit
import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * Changes the message of a commit of the current branch, also of one before `HEAD` (ADR 0100), without touching the
 * working tree or the index: the commit and every commit between it and `HEAD` are recreated with the same trees
 * (`git hash-object -t commit -w`) and the branch is moved to the new `HEAD` at once (`git update-ref`), only if it is
 * still where it was. Refused for a commit on the repository's default branch. Every method runs git on the calling
 * thread (the git log's write thread, never the JavaFX thread); none can be cancelled.
 */
@Component
class GitRewords(
    private val git: GitCli,
) {
    /** The most commits after the reworded one that are rewritten with it. */
    internal var maxCommits = MAX_COMMITS

    /** Runs after the new commits are written and before the branch is moved; tests move the branch meanwhile. */
    internal var beforeUpdate: () -> Unit = {}

    /**
     * What the dialog to edit the message of [commit] (a full hash, lowercase as git gives it) on the current branch
     * [branch] (short name) needs, or why it is refused: [commit] cannot be given to git, git is in the middle of
     * anything (or that cannot be read), [branch] is no longer the current branch, [commit] is not on it or is on the
     * default branch (or whether it is cannot be read), or more than [maxCommits] commits follow it.
     */
    fun check(
        root: Path,
        branch: String,
        commit: String,
    ): GitRewordCheck {
        requireHash(commit)
        val plan = plan(root, branch, commit).getOrElse { return GitRewordCheck.Refused(it.message.orEmpty()) }
        val message =
            git.commitMessage(root, commit)?.trimEnd('\n')
                ?: return GitRewordCheck.Refused("The message of '${commit.take(SHORT_HASH)}' could not be read.")
        return GitRewordCheck.Allowed(message, plan.otherBranches, plan.pushedTo)
    }

    /**
     * Changes the message of [commit] (a full hash) on the current branch [branch] (short name) to [message], after the
     * checks of [check]. The message is cleaned up as the Commit tab's (`git stripspace`, lines starting with `#`
     * kept); [GitRewordResult.Unchanged] if it then equals the old one, cleaned up the same way. The commit and the
     * commits on the ancestry path from it to `HEAD` are copied with their trees, authors and other headers, the new
     * parents, the current committer and no signature; other commits (the other side of a merge) are kept. The branch is
     * moved from the old `HEAD` to the new one, which fails if it moved meanwhile.
     */
    fun reword(
        root: Path,
        branch: String,
        commit: String,
        message: String,
    ): GitRewordResult {
        requireHash(commit)
        val short = commit.take(SHORT_HASH)
        val plan = plan(root, branch, commit).getOrElse { return GitRewordResult.Failed(it.message.orEmpty()) }
        if (message.isBlank()) return GitRewordResult.Failed(EMPTY)
        val cleaned = git.stripSpace(root, message) ?: return GitRewordResult.Failed(CLEANUP_FAILED)
        if (cleaned.isBlank()) return GitRewordResult.Failed(EMPTY)
        val old =
            git.commitMessage(root, commit)?.let { git.stripSpace(root, it) }
                ?: return GitRewordResult.Failed("The message of '$short' could not be read.")
        if (cleaned == old) return GitRewordResult.Unchanged
        val committer = git.committerIdent(root).getOrElse { return GitRewordResult.Failed(it.message.orEmpty()) }
        val objects =
            git.rawCommits(root, plan.chain)
                ?: return GitRewordResult.Failed("The commits from '$short' on could not be read.")
        val rewritten = HashMap<String, String>()
        for (hash in plan.chain) {
            val raw =
                objects[hash] ?: return GitRewordResult.Failed("The commit '${hash.take(SHORT_HASH)}' is missing.")
            val newMessage = if (hash == commit) cleaned.toByteArray(Charsets.UTF_8) else null
            val written = git.writeCommit(root, rewordedCommit(raw, rewritten, committer, newMessage))
            if (!written.success) return GitRewordResult.Failed(written.message)
            rewritten[hash] = written.message
        }
        val newHead = rewritten.getValue(plan.head)
        beforeUpdate()
        val reason = "reword: ${cleaned.lineSequence().first()}".takeIf { canPassArgument(it) } ?: "reword $short"
        val moved = git.moveBranch(root, branch, newHead, plan.head, reason)
        return if (moved.success) GitRewordResult.Done(newHead) else GitRewordResult.Failed(moved.message)
    }

    /** The commits to rewrite and what the dialog tells about them; a failure with the reason if it is refused. */
    private fun plan(
        root: Path,
        branch: String,
        commit: String,
    ): Result<Plan> {
        refusal(commit)?.let { return refused(it) }
        val busy = git.inProgress(root).getOrElse { return refused(IN_PROGRESS_UNREADABLE) }
        if (busy != null) return refused(busyMessage(busy))
        val ref = "refs/heads/$branch"
        if (git.currentBranch(root) != ref) return refused("'$branch' is no longer the current branch.")
        val short = commit.take(SHORT_HASH)
        val notOnBranch = "'$short' is not on the current branch '$branch'."
        val head = git.headCommit(root) ?: return refused(notOnBranch)
        val onBranch =
            git.isAncestor(root, commit, head)
                ?: return refused("Whether '$short' is on the current branch '$branch' could not be read.")
        if (!onBranch) return refused(notOnBranch)
        val unreadable = "Whether '$short' is on the default branch could not be read."
        val remotes = git.remoteNames(root) ?: return refused(unreadable)
        val refs = git.branchRefs(root) ?: return refused(unreadable)
        val containing = git.branchesContaining(root, commit)?.let(::containingBranches) ?: return refused(unreadable)
        defaultBranches(refs, remotes).firstOrNull { it in containing }?.let {
            return refused("'$short' is already on ${shortName(it)}; its message can't be changed.")
        }
        val after =
            git.ancestryPath(root, commit, head) ?: return refused("The commits after '$short' could not be read.")
        if (after.size > maxCommits) {
            return refused("'$short' has more than $maxCommits commits after it; change its message in a terminal.")
        }
        val others = containing.filter { it.startsWith(LOCAL) && it != ref }.map(::shortName)
        val upstream = refs.firstOrNull { it.name == ref }?.upstream?.takeIf { it in containing }
        return Result.success(Plan(head, listOf(commit) + after, others, upstream?.let(::shortName)))
    }

    private fun refused(reason: String) = Result.failure<Plan>(IllegalStateException(reason))

    private fun requireHash(commit: String) = require(HASH.matches(commit)) { "not a commit hash: $commit" }

    /**
     * What [plan] found: the current [head], the commits to rewrite parents first ([chain], the reworded one first), and
     * what the dialog tells ([GitRewordCheck.Allowed]).
     */
    private class Plan(
        val head: String,
        val chain: List<String>,
        val otherBranches: List<String>,
        val pushedTo: String?,
    )

    internal companion object {
        /** The most commits after the reworded one that are rewritten with it, by default. */
        const val MAX_COMMITS = 1000

        private const val SHORT_HASH = 8

        private const val LOCAL = "refs/heads/"

        private const val REMOTE = "refs/remotes/"

        private const val EMPTY = "The message is empty."

        private const val CLEANUP_FAILED = "The message could not be cleaned up."

        /** The names of default branches, in any case (as the git log's protected branches). */
        private val DEFAULT_NAMES = listOf("main", "master")

        private val HASH = Regex("[0-9a-f]{40}|[0-9a-f]{64}")

        /** The full refnames of the branches in the output of [branchesContaining], without symbolic refs. */
        private fun containingBranches(output: String): Set<String> =
            output
                .lineSequence()
                .map { it.split('\u001f') }
                .filter { it.size == 2 && it[0].isNotEmpty() && it[1].isEmpty() }
                .map { it[0] }
                .toSet()

        /**
         * The full refnames of the default branches among [refs], whose commits are never reworded: for each of the
         * [remotes], what its `refs/remotes/<remote>/HEAD` points at, or without one its `main` and `master`; the
         * local `main` and `master`, the names in any case; and the local branches of the same name as the remote one
         * they track (a local `develop` tracking `origin/develop`, which `origin/HEAD` points at), also the current
         * branch. A branch of another name that tracks one (`fix-123` made from `origin/main`) is no default branch.
         */
        internal fun defaultBranches(
            refs: List<BranchRef>,
            remotes: List<String>,
        ): List<String> {
            fun named(prefix: String) =
                refs.map { it.name }.filter { name ->
                    name.startsWith(prefix) &&
                        DEFAULT_NAMES.any { it.equals(name.removePrefix(prefix), ignoreCase = true) }
                }
            val remote =
                remotes.flatMap { remote ->
                    val head = refs.firstOrNull { it.name == "$REMOTE$remote/HEAD" && it.target.isNotEmpty() }
                    if (head != null) listOf(head.target) else named("$REMOTE$remote/")
                }
            val tracking =
                refs
                    .filter { ref ->
                        ref.name.startsWith(LOCAL) &&
                            ref.upstream in remote &&
                            remotes.any { ref.upstream == "$REMOTE$it/${ref.name.removePrefix(LOCAL)}" }
                    }.map { it.name }
            return (remote + named(LOCAL) + tracking).distinct()
        }

        private fun shortName(ref: String) =
            if (ref.startsWith(LOCAL)) ref.removePrefix(LOCAL) else ref.removePrefix(REMOTE)
    }
}
