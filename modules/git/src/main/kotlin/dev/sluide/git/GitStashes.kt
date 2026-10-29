package dev.sluide.git

import dev.sluide.git.cli.GitCli
import dev.sluide.git.cli.canPassArgument
import dev.sluide.git.cli.commitOfRevision
import dev.sluide.git.cli.currentBranch
import dev.sluide.git.cli.inProgress
import dev.sluide.git.cli.otherPaths
import dev.sluide.git.cli.stashTip
import dev.sluide.git.cli.stashTrackedPaths
import dev.sluide.git.cli.stashUntrackedPaths
import dev.sluide.git.cli.trackedStates
import dev.sluide.git.cli.unmergedEntries
import dev.sluide.git.cli.unmergedPaths
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/**
 * Lists, makes, pops, applies and drops stashes (ADR 0107) with the git command line, and marks files resolved that
 * have conflicts while git is in the middle of nothing else ([GitPendingConflicts]: a conflicting pop or apply, a
 * squash, an autostash). Every method runs git on the calling thread; none can be cancelled. A stash is named by its
 * place in the list (`stash@{n}`), which moves with every stash made or dropped, so each command first checks that the
 * name still stands for the commit listed. Paths go to git's standard input, never as arguments (ADR 0038).
 */
@Component
class GitStashes(
    private val git: GitCli,
) {
    /** Whether the JVM can name the working-tree file at a path (ADR 0038); tests take the fallback through git. */
    internal var canNameFile: (String) -> Boolean = { canPassArgument(it) }

    /** Whether a message reaches git unchanged as an argument (ADR 0038); tests take a POSIX locale's answer. */
    internal var canPass: (String) -> Boolean = { canPassArgument(it) }

    private val conflicts = GitConflictCheck(git) { canNameFile(it) }

    /**
     * The stashes of the repository at [root], newest first: `git stash list -z` with the name, commit, committer time
     * and message of each; empty without stashes, `null` if git is not available, fails or is cancelled.
     */
    fun list(
        root: Path,
        cancellation: GitCancellation? = null,
    ): List<GitStash>? {
        if (!git.available) return null
        val arguments = listOf("--no-optional-locks", "stash", "list", "-z", "--format=%gd%x1f%H%x1f%ct%x1f%gs")
        val output = git.run(arguments, root, 10.seconds, cancellation = cancellation) ?: return null
        return output.split('\u0000').filter(String::isNotEmpty).mapIndexedNotNull { position, record ->
            val fields = record.split('\u001f', limit = 4)
            if (fields.size != 4) return@mapIndexedNotNull null
            val index =
                STASH_NAME
                    .matchEntire(fields[0])
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull() ?: position
            GitStash(index, fields[1], fields[2].toLongOrNull() ?: 0, fields[3])
        }
    }

    /** What the Stash Changes dialog needs first in the repository at [root]: the current branch, and why not now. */
    fun sources(root: Path): GitStashSources {
        val busy = git.inProgress(root).fold({ pending -> pending?.let(::busyMessage) }, { IN_PROGRESS_UNREADABLE })
        return GitStashSources(git.currentBranch(root)?.removePrefix("refs/heads/"), busy)
    }

    /**
     * Stashes the local changes: `git stash push`, with the untracked files that are not ignored if [includeUntracked]
     * (`--include-untracked`; ignored files never), with [message] if given (`--message`, trimmed, blank is none; it
     * must be passable in the JVM's locale, ADR 0038: else refused, never mangled), only the [paths] if given
     * (`--pathspec-from-file=- --pathspec-file-nul`, literal, on git's standard input). Without changes (`refs/stash`
     * did not move) it is [GitStashResult.NothingToStash]. Refused without running git while git is in the middle of
     * anything (or that cannot be read).
     */
    fun push(
        root: Path,
        message: String?,
        includeUntracked: Boolean,
        paths: List<String>? = null,
    ): GitStashResult {
        if (paths != null && paths.isEmpty()) return GitStashResult.Failed("Nothing is selected to stash.")
        val message = message?.trim()?.ifEmpty { null }
        if (message != null && !canPass(message)) {
            return GitStashResult.Failed("'$message' cannot be passed to git in this locale.")
        }
        busy(root)?.let { return GitStashResult.Failed(it) }
        val arguments =
            buildList {
                if (paths != null) add("--literal-pathspecs")
                addAll(listOf("stash", "push"))
                if (includeUntracked) add("--include-untracked")
                if (message != null) add("--message=$message")
                if (paths != null) addAll(listOf("--pathspec-from-file=-", "--pathspec-file-nul"))
            }
        val input = paths?.joinToString("") { "$it\u0000" }?.toByteArray(Charsets.UTF_8)
        val before = git.stashTip(root)
        val result = git.write(root, arguments, input, GitCli.CHECKOUT_TIMEOUT, writesFiles = true)
        if (!result.success) return GitStashResult.Failed(result.message)
        val after = git.stashTip(root)
        val unmoved = before != null && after != null && before == after
        return if (unmoved) GitStashResult.NothingToStash else GitStashResult.Done
    }

    /**
     * Pops [stash]: `git stash pop` without `--index`, which applies it and drops it if that went through. What was
     * staged comes back unstaged, except that after conflicts git leaves the files it merged cleanly staged. Conflicts
     * keep the stash (git 2.53) and leave the files with markers, unmerged ([GitStashResult.Conflicts]). Git failing
     * otherwise keeps the stash too: a failure that changed none of the stash's files is [GitStashResult.Failed]
     * (local changes in the way of a stash without untracked files); one that did is [GitStashResult.Partly]: git
     * writes the stash's untracked files that are not on disk before it fails, on local changes in the way (the
     * tracked changes not applied) or on an untracked file that exists already (the tracked changes applied).
     * Refused without running git while git is in the middle of anything, or if `stash@{n}` is no longer [stash]
     * ([GitStashResult.Changed]); between that check and git a stash made or dropped elsewhere would still shift it.
     */
    fun pop(
        root: Path,
        stash: GitStash,
    ): GitStashResult = apply(root, stash, pop = true)

    /**
     * Applies [stash] as [pop] does, but keeps it in the list; git is given its commit, checked to be `stash@{n}`, so
     * nothing can shift it.
     */
    fun apply(
        root: Path,
        stash: GitStash,
    ): GitStashResult = apply(root, stash, pop = false)

    private fun apply(
        root: Path,
        stash: GitStash,
        pop: Boolean,
    ): GitStashResult {
        busy(root)?.let { return GitStashResult.Failed(it) }
        if (!listed(root, stash)) return GitStashResult.Changed
        val tracked = git.stashTrackedPaths(root, stash.commit)
        val untracked = git.stashUntrackedPaths(root, stash.commit)
        val statesBefore = git.trackedStates(root)
        val existedBefore = existing(root, untracked)
        val arguments = if (pop) listOf("pop", "--end-of-options", stash.name) else listOf("apply", stash.commit)
        val result = git.write(root, listOf("stash") + arguments, timeout = GitCli.CHECKOUT_TIMEOUT, writesFiles = true)
        if (result.success) return GitStashResult.Done
        // Nothing was unmerged before: busy() refuses then.
        val unmerged = git.unmergedPaths(root).orEmpty()
        if (unmerged.isNotEmpty()) return GitStashResult.Conflicts(unmerged, listed(root, stash))
        // Git 2.53 writes the untracked files it can before it fails on one that exists ("u already exists"), and the
        // tracked changes either before that or not at all (local changes in the way): only what the stash changed
        // counts, not edits elsewhere meanwhile (an editor saving).
        val statesAfter = git.trackedStates(root)
        val trackedApplied =
            tracked != null && statesBefore != null && statesAfter != null &&
                tracked.any { statesBefore[it] != statesAfter[it] }
        val existsAfter = existing(root, untracked)
        val restored =
            if (existedBefore == null || existsAfter == null) {
                emptyList()
            } else {
                untracked.filter { it !in existedBefore && it in existsAfter }
            }
        if (!trackedApplied && restored.isEmpty()) return GitStashResult.Failed(result.message)
        val inTheWay = existedBefore?.let { before -> untracked.filter { it in before } }.orEmpty()
        val trackedState = if (tracked.isNullOrEmpty()) null else trackedApplied
        return GitStashResult.Partly(trackedState, restored, inTheWay, listed(root, stash), result.message)
    }

    /**
     * Drops [stash]: `git stash drop`; its changes are lost (reachable through git's unreachable objects only until
     * they are pruned). Also while git is in the middle of something, as it changes no files. Refused without running
     * git if `stash@{n}` is no longer [stash] ([GitStashResult.Changed]).
     */
    fun drop(
        root: Path,
        stash: GitStash,
    ): GitStashResult {
        if (!listed(root, stash)) return GitStashResult.Changed
        val result = git.write(root, listOf("stash", "drop", "--end-of-options", stash.name))
        return if (result.success) GitStashResult.Done else GitStashResult.Failed(result.message)
    }

    /**
     * Marks the files with conflicts resolved while git is in the middle of nothing else ([GitPendingConflicts]), with
     * the checks of continuing a cherry-pick (ADR 0042): conflict markers left ([GitResolveResult.MarkersLeft]) or
     * files that cannot be checked and are not all among the [confirmed] ones ([GitResolveResult.Unchecked]) refuse
     * it, nothing staged; then exactly the unmerged files are staged as they are on disk, nothing else.
     */
    fun markResolved(
        root: Path,
        confirmed: Set<GitUncheckedConflict> = emptySet(),
    ): GitResolveResult {
        val pending = git.inProgress(root).getOrElse { return GitResolveResult.Failed(IN_PROGRESS_UNREADABLE) }
        if (pending == null) return GitResolveResult.Failed("No files have conflicts.")
        if (pending !is GitPendingConflicts) return GitResolveResult.Failed(busyMessage(pending))
        val entries = git.unmergedEntries(root) ?: return GitResolveResult.Failed(CONFLICTS_UNREADABLE)
        val check = conflicts.check(root, entries) ?: return GitResolveResult.Failed(CONFLICTS_UNREADABLE)
        if (check.markers.isNotEmpty()) return GitResolveResult.MarkersLeft(check.markers)
        check.refusal?.let { return GitResolveResult.Failed(it) }
        if (!confirmed.containsAll(check.unchecked)) return GitResolveResult.Unchecked(check.unchecked)
        val staged = conflicts.stage(root, entries)
        if (!staged.success) return GitResolveResult.Failed(staged.message)
        return GitResolveResult.Done(entries.map { it.path }.distinct())
    }

    /** Why nothing may change files now: what git is in the middle of, or that it cannot be read; `null` if nothing. */
    private fun busy(root: Path): String? =
        git.inProgress(root).fold({ pending -> pending?.let(::busyMessage) }, { IN_PROGRESS_UNREADABLE })

    /**
     * Which of the [paths] (relative to [root]) are on disk now, a link too; checked by the JVM, or for paths it
     * cannot name in its locale (ADR 0038) with `git ls-files --others`, which lists every file not in the index;
     * `null` if that cannot be read.
     */
    private fun existing(
        root: Path,
        paths: List<String>,
    ): Set<String>? {
        if (paths.isEmpty()) return emptySet()
        val others by lazy { git.otherPaths(root)?.toSet() }
        return paths
            .filter { path ->
                val file = localFile(root, path, canNameFile)
                if (file != null) Files.exists(file, LinkOption.NOFOLLOW_LINKS) else path in (others ?: return null)
            }.toSet()
    }

    /** Whether `stash@{n}` of [stash] still names its commit. */
    private fun listed(
        root: Path,
        stash: GitStash,
    ): Boolean = git.commitOfRevision(root, stash.name) == stash.commit

    private companion object {
        val STASH_NAME = Regex("""stash@\{(\d+)}""")
    }
}
