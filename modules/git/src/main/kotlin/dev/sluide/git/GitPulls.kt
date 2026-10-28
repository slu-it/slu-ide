package dev.sluide.git

import dev.sluide.git.cli.GitCli
import dev.sluide.git.cli.branchRefs
import dev.sluide.git.cli.currentBranch
import dev.sluide.git.cli.headCommit
import dev.sluide.git.cli.inProgress
import dev.sluide.git.cli.refTips
import dev.sluide.git.cli.refusal
import dev.sluide.git.cli.remoteNames
import org.springframework.stereotype.Component
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/**
 * What a pull from the git log needs besides a fetch ([GitRemotes.fetch]) and a merge ([GitMerges.merge], ADR 0106):
 * what can be pulled, the upstream of a branch, and the rebase of a pull, with the fork point and git's autostash. A
 * pull is never `git pull`: the user's choice decides merge or rebase, not `pull.rebase`. Runs git on the calling
 * thread.
 */
@Component
class GitPulls(
    private val git: GitCli,
    private val rebases: GitRebases,
) {
    /**
     * What the Pull dialog offers in the repository at [root]: the current branch, whether it has a commit, the
     * remote-tracking branches and remotes, the current branch's upstream (the remote-tracking branch
     * `branch.<b>.remote` and `.merge` map to through the remote's fetch refspecs: `%(upstream)` of `git for-each-ref`;
     * none for a local upstream) and what git is in the middle of; `null` if the refs or remotes cannot be read.
     */
    fun sources(root: Path): GitPullSources? {
        val busy =
            git
                .inProgress(root)
                .fold({ pending -> pending?.let(::busyMessage) }, { IN_PROGRESS_UNREADABLE })
        val remotes = git.remoteNames(root) ?: return null
        val refs = git.branchRefs(root) ?: return null
        val current = git.currentBranch(root)
        val remoteBranches = refs.filter { it.name.startsWith(REMOTES) && it.target.isEmpty() }.map { it.name }
        val upstream = refs.firstOrNull { it.name == current }?.upstream?.takeIf { it in remoteBranches }
        return GitPullSources(
            branch = current?.removePrefix(HEADS),
            hasCommit = git.headCommit(root) != null,
            remoteBranches = remoteBranches,
            remotes = remotes,
            upstream = upstream,
            busy = busy,
        )
    }

    /**
     * Whether the remote-tracking branch [ref] (a full refname) exists in the repository at [root], e.g. still after a
     * fetch that may have pruned it; `null` if that cannot be read.
     */
    fun exists(
        root: Path,
        ref: String,
    ): Boolean? {
        if (refusal(ref) != null) return null
        // for-each-ref matches whole path components: the refs below a folder of that name do not count.
        return git.refTips(root, listOf(ref))?.containsKey(ref)
    }

    /**
     * The short name of the branch `HEAD` is attached to in the repository at [root]; `null` when detached or if that
     * cannot be read.
     */
    fun currentBranch(root: Path): String? = git.currentBranch(root)?.removePrefix(HEADS)

    /**
     * The fork point of `HEAD` with the remote-tracking branch [ref] (a full refname): `git merge-base --fork-point
     * <ref> HEAD`, from the reflog of [ref], which a rebase with `--fork-point` starts from; `null` if there is none
     * (no reflog, e.g. with `core.logAllRefUpdates=false`: the rebase takes the plain merge base then) or it cannot be
     * read.
     */
    fun forkPoint(
        root: Path,
        ref: String,
    ): String? {
        if (refusal(ref) != null || !git.available) return null
        val arguments = listOf("--no-optional-locks", "merge-base", "--fork-point", "--end-of-options", ref, "HEAD")
        return git.run(arguments, root, 10.seconds)?.trim()?.ifEmpty { null }
    }

    /**
     * How many commits [to] has that [from] has not (full refnames or hashes): `git rev-list --count <from>..<to>`;
     * `null` if that cannot be read.
     */
    fun commitCount(
        root: Path,
        from: String,
        to: String,
    ): Int? {
        if (refusal(from) != null || refusal(to) != null || !git.available) return null
        val arguments = listOf("--no-optional-locks", "rev-list", "--count", "--end-of-options", "$from..$to")
        return git.run(arguments, root, 10.seconds)?.trim()?.toIntOrNull()
    }

    /**
     * The rebase of a pull: [GitRebases.rebase] of [branch] onto [onto] (a full refname) with `--fork-point`, so that
     * commits [onto]'s reflog once had (rewritten by a force push on the remote) are not replayed, as `git pull
     * --rebase` does, and with `--autostash` if [autostash], else `--no-autostash` (git refuses local changes then).
     */
    fun rebase(
        root: Path,
        branch: String,
        onto: String,
        autostash: Boolean,
    ): GitRebaseResult = rebases.rebase(root, branch, onto, forkPoint = true, autostash = autostash)

    private companion object {
        const val HEADS = "refs/heads/"
        const val REMOTES = "refs/remotes/"
    }
}

/**
 * What can be pulled in a repository (ADR 0106): the current [branch] (short name, `null` when `HEAD` is detached),
 * whether it [hasCommit] (not an unborn branch), the [remoteBranches] (full refnames below `refs/remotes/`, without
 * symbolic refs such as `refs/remotes/origin/HEAD`, in git's order), the names of all [remotes], the current branch's
 * [upstream] if it is one of the [remoteBranches], and why nothing can start now ([busy]: what git is in the middle of,
 * or that this cannot be read; `null` for nothing).
 */
data class GitPullSources(
    val branch: String?,
    val hasCommit: Boolean,
    val remoteBranches: List<String>,
    val remotes: List<String>,
    val upstream: String?,
    val busy: String?,
)
