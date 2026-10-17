package dev.sluide.git

import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path

/** Git's commands that apply a commit's changes: cherry-pick, or revert to undo them. */
enum class GitPick(
    /** The git command: `cherry-pick`, `revert`. */
    val command: String,
) {
    CHERRY_PICK("cherry-pick"),
    REVERT("revert"),
}

/** Something git is in the middle of in a repository (ADR 0042), as `git status` would tell. */
sealed interface GitInProgress

/**
 * A single cherry-pick or revert in progress: git stopped it for the [commit] (a full hash), `CHERRY_PICK_HEAD` or
 * `REVERT_HEAD`, and [conflicts] are the paths still unmerged (relative to the repository), empty once all are staged.
 */
data class GitPendingPick(
    val kind: GitPick,
    val commit: String,
    val conflicts: List<String>,
) : GitInProgress

/** Another [operation] in progress, which Thoreau leaves to the terminal. */
data class GitPendingOperation(
    val operation: GitOperation,
) : GitInProgress

/** What git can be in the middle of besides a single cherry-pick or revert. */
enum class GitOperation {
    MERGE,
    REBASE,

    /** `git am`, applying patches from mails. */
    AM,

    /** A cherry-pick of several commits (`git cherry-pick A..B`), its state in `sequencer`. */
    CHERRY_PICK_SEQUENCE,

    /** A revert of several commits. */
    REVERT_SEQUENCE,

    /** A sequence whose kind could not be read. */
    SEQUENCE,
}

/** Why a conflicted file cannot be checked for conflict markers before continuing (ADR 0042). */
enum class GitConflictReason {
    /** Binary (by git's heuristic or attributes): git kept one side's version, without markers. */
    BINARY,

    /** Too large to read for the check. */
    TOO_LARGE,

    /** Deleted on one side, and the file is on disk: it will be committed. */
    DELETED_ON_ONE_SIDE,

    /** Deleted on disk: the deletion will be committed. */
    DELETED_ON_DISK,

    /** A submodule (gitlink). */
    SUBMODULE,

    /** A symbolic link. */
    SYMLINK,
}

/** A conflicted [path] that cannot be checked for conflict markers, and why. */
data class GitUncheckedConflict(
    val path: String,
    val reason: GitConflictReason,
)

/** What a cherry-pick or revert, or continuing one, gave (ADR 0042). */
sealed interface GitPickResult {
    /** A commit was made; [commit] is the new `HEAD` (a full hash), `null` if it could not be read. */
    data class Done(
        val commit: String?,
    ) : GitPickResult

    /**
     * There was nothing to commit: the changes are already there (or already undone). The state git left was cleared
     * (`--skip`, or `--quit` for a revert, which leaves no `REVERT_HEAD`); nothing else changed. [branch] is the short
     * name of the current branch, `null` when `HEAD` is detached or that could not be read.
     */
    data class Empty(
        val branch: String?,
    ) : GitPickResult

    /**
     * Git stopped and left the state for the user: with [conflicts] (paths), or without (e.g. a hook failed), with
     * git's [message].
     */
    data class Stopped(
        val conflicts: List<String>,
        val message: String,
    ) : GitPickResult

    /** Continuing was refused without running git: conflict markers are left in these [paths]. */
    data class MarkersLeft(
        val paths: List<String>,
    ) : GitPickResult

    /**
     * Continuing needs a confirmation first, nothing was done: these conflicted [files] cannot be checked for conflict
     * markers and would be committed as they are on disk.
     */
    data class Unchecked(
        val files: List<GitUncheckedConflict>,
    ) : GitPickResult

    /** Git failed (or was not run), with its [message]; whatever is left is as git left it. */
    data class Failed(
        val message: String,
    ) : GitPickResult
}

/** What [GitCommits.commit] gave: git's [result], and on success the new `HEAD` (a full hash) as [commit]. */
data class GitCommitResult(
    val result: GitResult,
    val commit: String?,
)

/** How `git reset` moves the current branch (or a detached `HEAD`). */
enum class GitResetMode(
    /** The option: `--soft`. */
    val option: String,
) {
    /** Index and files stay; the differences are staged. */
    SOFT("--soft"),

    /** Files stay; the index is reset, the differences are not staged. */
    MIXED("--mixed"),

    /** Index and tracked files are reset; their local changes are lost. */
    HARD("--hard"),
}

/**
 * Applies and undoes commits and resets the current branch (ADR 0042), with the git command line: hooks and the
 * user's configuration apply as for git itself. Every method runs git on the calling thread; commands that change
 * files or the index may take 10 minutes. Revisions are full hashes, passed after `--end-of-options`; paths go to git's
 * standard input, never as arguments (non-ASCII ones could not be passed in a POSIX locale, ADR 0038).
 *
 * A cherry-pick or revert that stops with nothing to commit is cleared at once ([GitPickResult.Empty]); one that stops
 * with conflicts is left for the user, to [continuePick] once resolved or to [abortPick].
 */
@Component
class GitCommits(
    private val git: GitCli,
) {
    /** What git is in the middle of, `null` if nothing or it cannot be read. */
    fun inProgress(root: Path): GitInProgress? = git.inProgress(root).getOrNull()

    /** The commit `HEAD` points at (a full hash); `null` if there is none or it cannot be read. */
    fun headCommit(root: Path): String? = git.headCommit(root)

    /**
     * What can be committed in the repository at [root] (ADR 0048): the tracked changes against `HEAD` (renames
     * included; with no commit yet, every file in the index as added), the untracked files that are not ignored (each
     * one, also inside untracked folders; a nested repository is listed as its folder with a trailing `/`, and committing
     * it records a submodule entry without a `.gitmodules`), what git is in the middle of with the unmerged paths, and whether `HEAD` is
     * already on the current branch's upstream. `null` if git fails.
     */
    fun commitChanges(root: Path): GitCommitChanges? {
        val unborn = git.isHeadUnborn(root) ?: return null
        val head = if (unborn) null else git.headCommit(root) ?: return null
        val files =
            if (head == null) {
                git.indexedPaths(root)?.map { GitChangedFile(GitChange.ADDED, it) }
            } else {
                git.workingTreeChanges(root, head)?.let(GitNameStatusParser::parse)
            } ?: return null
        val untracked = git.untrackedPaths(root) ?: return null
        val busy = git.inProgress(root).getOrElse { return null }
        val conflicts = git.unmergedPaths(root) ?: return null
        return GitCommitChanges(files, untracked, busy, conflicts, head != null && isPushed(root, head))
    }

    /** Whether [head] is `@{upstream}` of the current branch or an ancestor of it; false without a branch or upstream. */
    private fun isPushed(
        root: Path,
        head: String,
    ): Boolean {
        if (git.currentBranch(root) == null) return false
        val upstream = git.commitOfRevision(root, "@{upstream}") ?: return false
        return git.isAncestor(root, head, upstream) == true
    }

    /** The full message of `HEAD` without its trailing newline; `null` if there is no commit or git fails. */
    fun headMessage(root: Path): String? = git.headMessage(root)?.trimEnd('\n')

    /**
     * Commits the checked tracked changes [files] and [untracked] files with [message], or with [amend] amends `HEAD`
     * (ADR 0048). Refused without running git when the message is blank, nothing is checked and it is no amend, an
     * amend has no commit to amend, git is in the middle of anything ([inProgress], or that cannot be read) or paths
     * are unmerged. Checked untracked files are staged first (`git --literal-pathspecs add`), then
     * `git --literal-pathspecs commit --only [--amend] --cleanup=whitespace -F -` commits exactly the checked paths
     * from the working tree (a renamed file with both of its paths, so the deletion goes along) and leaves everything
     * else staged or modified as it was; without paths an amend only changes the message and keeps the tree. The
     * message goes to git's standard input as UTF-8 and is cleaned up as `--cleanup=whitespace` does, lines starting
     * with `#` are kept. The paths go in a temporary file of NUL-terminated UTF-8 names (`--pathspec-from-file`),
     * never as arguments (ADR 0038), so only the file's own name must be one git can be given; it is deleted at the
     * end. If staging succeeded and the commit failed (a hook rejected it), the staged files stay staged: nothing is
     * rolled back. A commit may take 10 minutes (hooks, a GPG pinentry); [cancellation] stops it. The result has git's
     * message, a hook's output included.
     */
    fun commit(
        root: Path,
        message: String,
        files: List<GitChangedFile>,
        untracked: List<String>,
        amend: Boolean,
        cancellation: GitCancellation? = null,
    ): GitCommitResult {
        // Only a rename's old path goes along (the deletion); the source of a copy is unchanged and stays alone.
        val paths =
            files
                .flatMap { file ->
                    listOfNotNull(file.path, file.oldPath.takeIf { file.change == GitChange.RENAMED })
                }.plus(untracked)
                .distinct()
        val reason =
            when {
                message.isBlank() -> "The commit message is empty."
                paths.isEmpty() && !amend -> "Nothing is selected to commit."
                else -> null
            }
        if (reason != null) return refused(reason)
        val unborn = git.isHeadUnborn(root) ?: return refused("HEAD could not be read.")
        if (amend && unborn) return refused("There is no commit to amend.")
        val busy = git.inProgress(root).getOrElse { return refused(UNREADABLE) }
        if (busy != null) return refused(busyMessage(busy))
        val unmerged = git.unmergedPaths(root) ?: return refused(CONFLICTS_UNREADABLE)
        if (unmerged.isNotEmpty()) return refused("Resolve the conflicts first.")
        val list =
            try {
                Files.createTempFile("sluide-commit-", ".paths")
            } catch (e: IOException) {
                return refused("The list of files could not be written: ${e.message}")
            }
        try {
            if (!canPassArgument(list.toString())) return refused("'$list' cannot be passed to git in this locale.")
            val pathspec = listOf("--pathspec-from-file=$list", "--pathspec-file-nul")
            if (untracked.isNotEmpty()) {
                Files.write(list, nulSeparated(untracked))
                val staged = git.write(root, listOf(LITERAL, "add") + pathspec, timeout = GitCli.WRITE_TIMEOUT)
                if (!staged.success) return GitCommitResult(staged, null)
            }
            Files.write(list, nulSeparated(paths))
            val arguments =
                buildList {
                    // The message is UTF-8, whatever i18n.commitEncoding says.
                    addAll(listOf(LITERAL, "-c", "i18n.commitEncoding=UTF-8", "commit", "--only"))
                    if (amend) add("--amend")
                    addAll(listOf("--cleanup=whitespace", "-F", "-"))
                    if (paths.isNotEmpty()) addAll(pathspec)
                }
            val (result, _) =
                git.writeWithExit(
                    root,
                    arguments,
                    message.toByteArray(Charsets.UTF_8),
                    GitCli.COMMIT_TIMEOUT,
                    cancellation = cancellation,
                )
            return GitCommitResult(result, if (result.success) git.headCommit(root) else null)
        } catch (e: IOException) {
            return refused("The list of files could not be written: ${e.message}")
        } finally {
            runCatching { Files.deleteIfExists(list) }
        }
    }

    private fun refused(message: String) = GitCommitResult(GitResult(false, message), null)

    private fun nulSeparated(paths: List<String>): ByteArray =
        paths.joinToString("") { it + "\u0000" }.toByteArray(Charsets.UTF_8)

    /**
     * Cherry-picks or reverts ([kind]) [commit] (a full hash): `git cherry-pick` or `git revert --no-edit`, for a
     * merge commit relative to its first parent (`-m 1`) if [firstParent]. Refused without running git while git is in
     * the middle of anything ([inProgress]) or that cannot be read: git would conclude a merge with it, or drop a
     * sequence's state when clearing an empty result.
     */
    fun pick(
        root: Path,
        kind: GitPick,
        commit: String,
        firstParent: Boolean,
    ): GitPickResult {
        refusal(commit)?.let { return GitPickResult.Failed(it) }
        val busy = git.inProgress(root).getOrElse { return GitPickResult.Failed(UNREADABLE) }
        if (busy != null) return GitPickResult.Failed(busyMessage(busy))
        val head = git.headCommit(root)
        val arguments =
            buildList {
                add(kind.command)
                if (kind == GitPick.REVERT) add("--no-edit")
                if (firstParent) addAll(listOf("-m", "1"))
                addAll(listOf("--end-of-options", commit))
            }
        val (result, exit) = git.writeWithExit(root, arguments, timeout = GitCli.CHECKOUT_TIMEOUT)
        return outcome(root, kind, commit, head, result, exit)
    }

    /**
     * Continues the cherry-pick or revert ([kind]) in progress (not a sequence, which is left to the terminal). Of the
     * files still unmerged, the text conflicts (all sides regular files, not binary by git's heuristic or the `merge`
     * attribute) are checked for lines starting with a conflict marker ([GitPickResult.MarkersLeft], nothing done);
     * the others cannot be checked and need to be among the [confirmed] ones, as the user was shown them
     * ([GitPickResult.Unchecked] with them all, nothing done); what `git update-index` cannot stage is refused: a
     * submodule that is not checked out, a folder or special file (a FIFO) in place of a text file. Then exactly the
     * unmerged files are staged as they are on disk (`git update-index --add --remove`, the paths on its standard
     * input, so a deleted file is staged as deleted; nothing else is staged) and the commit is made with git's message
     * (`--continue --no-edit`).
     */
    fun continuePick(
        root: Path,
        kind: GitPick,
        confirmed: Set<GitUncheckedConflict> = emptySet(),
    ): GitPickResult {
        val pending = git.inProgress(root).getOrElse { return GitPickResult.Failed(UNREADABLE) }
        if (pending is GitPendingOperation) return GitPickResult.Failed(busyMessage(pending))
        if (pending !is GitPendingPick || pending.kind != kind) {
            return GitPickResult.Failed("No ${kind.command} is in progress.")
        }
        val entries = git.unmergedEntries(root) ?: return GitPickResult.Failed(CONFLICTS_UNREADABLE)
        if (entries.isNotEmpty()) {
            val check = checkConflicts(root, entries) ?: return GitPickResult.Failed(CONFLICTS_UNREADABLE)
            if (check.markers.isNotEmpty()) return GitPickResult.MarkersLeft(check.markers)
            val refusals =
                listOfNotNull(
                    check.notCheckedOut.takeIf { it.isNotEmpty() }?.let(::notCheckedOut),
                    check.notFiles.takeIf { it.isNotEmpty() }?.let(::notFiles),
                )
            if (refusals.isNotEmpty()) return GitPickResult.Failed(refusals.joinToString("\n"))
            if (!confirmed.containsAll(check.unchecked)) return GitPickResult.Unchecked(check.unchecked)
            val input =
                entries
                    .map { it.path }
                    .distinct()
                    .joinToString("") { "$it\u0000" }
                    .toByteArray(Charsets.UTF_8)
            val staged =
                git.write(
                    root,
                    listOf("update-index", "-z", "--add", "--remove", "--stdin"),
                    input,
                    GitCli.CHECKOUT_TIMEOUT,
                )
            if (!staged.success) return GitPickResult.Failed(staged.message)
        }
        val head = git.headCommit(root)
        val (result, exit) =
            git.writeWithExit(root, listOf(kind.command, "--continue", "--no-edit"), timeout = GitCli.CHECKOUT_TIMEOUT)
        return outcome(root, kind, pending.commit, head, result, exit)
    }

    /** Git failed while a conflict was sorted. */
    private class ReadFailed : RuntimeException()

    /**
     * The conflicted files with markers left, those that cannot be checked, and what `git update-index` cannot stage:
     * the submodules that are not checked out (an empty folder), and folders or special files (a FIFO) in place of a
     * text conflict.
     */
    private class ConflictCheck(
        val markers: List<String>,
        val unchecked: List<GitUncheckedConflict>,
        val notCheckedOut: List<String>,
        val notFiles: List<String>,
    )

    private fun notFiles(paths: List<String>): String {
        val names = paths.joinToString(", ")
        val which = if (paths.size == 1) "$names is not a regular file" else "$names are not regular files"
        return "$which (a folder or a special file), which git cannot stage. Resolve it in the terminal."
    }

    private fun notCheckedOut(paths: List<String>): String {
        val names = paths.joinToString(", ")
        val which = if (paths.size == 1) "Submodule $names is" else "Submodules $names are"
        return "$which not checked out: the commit to keep cannot be taken from disk. Resolve it in the terminal."
    }

    /**
     * Sorts the conflicted files of the unmerged [entries] (ADR 0042): the text conflicts present on disk are checked
     * for lines starting with a conflict marker as long as their `conflict-marker-size` (7 by default); the others are
     * unchecked, with the reason. A file is read directly if the JVM can name it and it is a regular file (a symbolic
     * link in its place is unchecked, a folder or special file refused, neither is followed or read), a larger one than
     * [MAX_CHECKED_SIZE] is not read but unchecked; one it cannot name (non-ASCII in a POSIX locale) is stored with
     * `git hash-object` first, its path on git's standard input, and read from there. `null` if git or reading fails.
     */
    private fun checkConflicts(
        root: Path,
        entries: List<UnmergedEntry>,
    ): ConflictCheck? {
        val byPath = entries.groupBy { it.path }
        val missing = git.missingFiles(root) ?: return null
        val attributes = git.attributes(root, byPath.keys.toList(), listOf("merge", MARKER_SIZE)) ?: return null
        val unchecked = mutableListOf<GitUncheckedConflict>()
        val notCheckedOut = mutableListOf<String>()
        val notFiles = mutableListOf<String>()
        val text = mutableListOf<String>()
        for ((path, stages) in byPath) {
            val reason =
                try {
                    reason(root, stages, path in missing, attributes[path].orEmpty()["merge"])
                } catch (_: ReadFailed) {
                    return null
                }
            when {
                reason == null -> text += path
                reason == GitConflictReason.SUBMODULE && !checkedOut(root, path) -> notCheckedOut += path
                else -> unchecked += GitUncheckedConflict(path, reason)
            }
        }

        fun markerSize(path: String) = attributes[path].orEmpty()[MARKER_SIZE]?.toIntOrNull()?.takeIf { it > 0 } ?: 7
        val marked = mutableSetOf<String>()
        val unnamed = mutableListOf<String>()
        for (path in text) {
            val file = localFile(root, path)
            if (file == null) {
                unnamed += path
                continue
            }
            try {
                if (Files.isSymbolicLink(file)) {
                    unchecked += GitUncheckedConflict(path, GitConflictReason.SYMLINK)
                } else if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    // Reading a FIFO would block for good.
                    notFiles += path
                } else if (Files.size(file) > MAX_CHECKED_SIZE) {
                    unchecked += GitUncheckedConflict(path, GitConflictReason.TOO_LARGE)
                } else if (hasConflictMarker(Files.readAllBytes(file), markerSize(path))) {
                    marked += path
                }
            } catch (_: IOException) {
                return null
            }
        }
        // Their size cannot be read before they are stored.
        val stored = git.storeFiles(root, unnamed) ?: return null
        for ((path, name) in unnamed.zip(stored)) {
            val size = git.objectSize(root, name) ?: return null
            if (size > MAX_CHECKED_SIZE) {
                unchecked += GitUncheckedConflict(path, GitConflictReason.TOO_LARGE)
            } else if (hasConflictMarker(git.blob(root, name) ?: return null, markerSize(path))) {
                marked += path
            }
        }
        return ConflictCheck(text.filter { it in marked }, unchecked, notCheckedOut, notFiles)
    }

    /** The file [path] (relative to [root]) as the JVM can name it; `null` if it cannot in its locale. */
    private fun localFile(
        root: Path,
        path: String,
    ): Path? =
        try {
            if (canPassArgument(path)) root.resolve(path) else null
        } catch (_: InvalidPathException) {
            null
        }

    /**
     * Whether the submodule at [path] is checked out (it has a `.git`), so that `git update-index` can stage its
     * commit; also `true` if the JVM cannot name it (git then tells).
     */
    private fun checkedOut(
        root: Path,
        path: String,
    ): Boolean = localFile(root, path)?.let { Files.exists(it.resolve(".git"), LinkOption.NOFOLLOW_LINKS) } ?: true

    /**
     * Why the conflicted file of these unmerged [stages] cannot be checked for conflict markers; `null` for a text
     * conflict on disk ([missing] tells it is not, also for a submodule's folder). [merge] is its `merge` attribute.
     * Throws [ReadFailed] if git fails.
     */
    private fun reason(
        root: Path,
        stages: List<UnmergedEntry>,
        missing: Boolean,
        merge: String?,
    ): GitConflictReason? {
        val ours = stages.firstOrNull { it.stage == 2 }
        val theirs = stages.firstOrNull { it.stage == 3 }
        return when {
            // Before anything else: whatever it was, its deletion is committed.
            missing -> {
                GitConflictReason.DELETED_ON_DISK
            }

            stages.any { it.mode == GITLINK } -> {
                GitConflictReason.SUBMODULE
            }

            stages.any { it.mode == SYMLINK } -> {
                GitConflictReason.SYMLINK
            }

            ours == null || theirs == null -> {
                GitConflictReason.DELETED_ON_ONE_SIDE
            }

            merge == "unset" || merge == "binary" -> {
                GitConflictReason.BINARY
            }

            else -> {
                val sizes = listOf(ours, theirs).map { git.objectSize(root, it.objectName) ?: throw ReadFailed() }
                if (sizes.any { it > MAX_CHECKED_SIZE }) return GitConflictReason.TOO_LARGE
                val binary = listOf(ours, theirs).any { isBinary(git.blob(root, it.objectName) ?: throw ReadFailed()) }
                if (binary) GitConflictReason.BINARY else null
            }
        }
    }

    /**
     * Aborts the cherry-pick or revert ([kind]) in progress: `git cherry-pick --abort`, `git revert --abort`. Refused
     * without running git if there is none of that kind, or a sequence or another operation is in progress.
     */
    fun abortPick(
        root: Path,
        kind: GitPick,
    ): GitResult {
        val pending = git.inProgress(root).getOrElse { return GitResult(false, UNREADABLE) }
        if (pending is GitPendingOperation) return GitResult(false, busyMessage(pending))
        if (pending !is GitPendingPick || pending.kind != kind) {
            return GitResult(false, "No ${kind.command} is in progress.")
        }
        return git.write(root, listOf(kind.command, "--abort"), timeout = GitCli.CHECKOUT_TIMEOUT)
    }

    /**
     * What a cherry-pick or revert ([kind]) of [commit] gave, with git's [result] and [exit] value; [head] is where
     * `HEAD` was before. A stop with nothing to commit (`HEAD` where it was, no conflicts, the index as `HEAD`) is
     * cleared: with `--skip` if git left its state for the commit; for a revert, which leaves only `MERGE_MSG` and
     * `AUTO_MERGE` (exit value 1, where a refusal to start is 128), by removing those two.
     */
    private fun outcome(
        root: Path,
        kind: GitPick,
        commit: String,
        head: String?,
        result: GitResult,
        exit: Int?,
    ): GitPickResult {
        if (result.success) return GitPickResult.Done(git.headCommit(root))
        val pending = (inProgress(root) as? GitPendingPick)?.takeIf { it.kind == kind && it.commit == commit }
        val unmerged = git.unmergedPaths(root)
        val unchanged = head != null && git.headCommit(root) == head
        val empty = unmerged?.isEmpty() == true && unchanged && git.indexMatchesHead(root) == true
        return when {
            pending != null && unmerged?.isNotEmpty() == true -> GitPickResult.Stopped(unmerged, result.message)
            pending != null && empty -> skip(root, kind)
            pending != null -> GitPickResult.Stopped(emptyList(), result.message)
            kind == GitPick.REVERT && exit == 1 && empty -> clearRevertMessage(root)
            else -> GitPickResult.Failed(result.message)
        }
    }

    /** Clears the state of an empty cherry-pick or revert with `git <kind> --skip`. */
    private fun skip(
        root: Path,
        kind: GitPick,
    ): GitPickResult {
        val cleared = git.write(root, listOf(kind.command, "--skip"), timeout = GitCli.CHECKOUT_TIMEOUT)
        if (!cleared.success) return GitPickResult.Failed(cleared.message)
        return empty(root)
    }

    /**
     * Removes what an empty revert left, `MERGE_MSG` (which the next `git commit` would take as its message) and
     * `AUTO_MERGE`, and nothing else: not `git revert --quit`, which would also drop a sequence's state.
     */
    private fun clearRevertMessage(root: Path): GitPickResult {
        val removed = git.write(root, listOf("update-ref", "-d", "AUTO_MERGE"))
        if (!removed.success) return GitPickResult.Failed(removed.message)
        val message = git.gitPaths(root, listOf("MERGE_MSG"))?.single() ?: return GitPickResult.Failed(UNREADABLE)
        try {
            Files.deleteIfExists(message)
        } catch (e: IOException) {
            return GitPickResult.Failed("$message could not be deleted: ${e.message}")
        }
        return empty(root)
    }

    private fun empty(root: Path) = GitPickResult.Empty(git.currentBranch(root)?.removePrefix("refs/heads/"))

    /**
     * How many commits would no longer be on any branch, remote branch or tag if the current branch (or the detached
     * `HEAD`) were reset to [target] (a full hash): those reachable from `HEAD`, not from [target] and not from any
     * other ref. `null` if that cannot be read (also if the current branch's name cannot be passed to git).
     */
    fun unreachableCount(
        root: Path,
        target: String,
    ): Int? {
        if (refusal(target) != null) return null
        val branch = git.headBranch(root) ?: return null
        val name = branch.removePrefix("refs/heads/").ifEmpty { null }
        if (name != null && refusal(name) != null) return null
        return git.unreachableCount(root, target, name)
    }

    /**
     * How many tracked files have local changes, staged or not, which a hard reset would lose (untracked files stay);
     * `null` if that cannot be read.
     */
    fun localChangeCount(root: Path): Int? = git.trackedChangeCount(root)

    /**
     * Resets the current branch (or the detached `HEAD`) to [target] (a full hash): `git reset --soft|--mixed|--hard`.
     */
    fun reset(
        root: Path,
        target: String,
        mode: GitResetMode,
    ): GitResult {
        refusal(target)?.let { return GitResult(false, it) }
        return git.write(
            root,
            listOf("reset", mode.option, "--end-of-options", target),
            timeout = GitCli.CHECKOUT_TIMEOUT,
        )
    }

    /** Why nothing is started while git is in the middle of [busy]. */
    private fun busyMessage(busy: GitInProgress): String =
        when (busy) {
            is GitPendingPick -> {
                "A ${busy.kind.command} of ${busy.commit.take(SHORT_HASH)} is in progress; continue or abort it first."
            }

            is GitPendingOperation -> {
                when (busy.operation) {
                    GitOperation.MERGE -> "A merge is in progress; finish or abort it first."
                    GitOperation.REBASE -> "A rebase is in progress; finish or abort it first."
                    GitOperation.AM -> "A git am session is in progress; finish or abort it first."
                    GitOperation.CHERRY_PICK_SEQUENCE -> SEQUENCE_MESSAGE.format("cherry-pick")
                    GitOperation.REVERT_SEQUENCE -> SEQUENCE_MESSAGE.format("revert")
                    GitOperation.SEQUENCE -> SEQUENCE_MESSAGE.format("cherry-pick or revert")
                }
            }
        }

    private companion object {
        /** The length of hashes in messages, as in the git log. */
        const val SHORT_HASH = 8

        const val SEQUENCE_MESSAGE = "A %s sequence is in progress; continue or abort it in the terminal."
        const val UNREADABLE = "Whether a merge, rebase, cherry-pick or revert is in progress could not be read."
        const val LITERAL = "--literal-pathspecs"
        const val CONFLICTS_UNREADABLE = "The conflicted files could not be read."
        const val MARKER_SIZE = "conflict-marker-size"
        const val GITLINK = "160000"
        const val SYMLINK = "120000"

        /** The largest file read to check it, as the git log reads files for diffs. */
        const val MAX_CHECKED_SIZE = 5L * 1024 * 1024

        /** Git's test for binary content: a NUL byte among the first 8000 bytes. */
        const val BINARY_SAMPLE = 8000

        fun isBinary(bytes: ByteArray): Boolean = bytes.take(BINARY_SAMPLE).contains(0)
    }
}

/**
 * Whether [content] has a line starting with a conflict marker of [size] characters, `<` or `>` (not `=`, too common
 * alone), followed by a space or the line's end: the lines git writes around a conflict. Read as bytes, not decoded.
 */
internal fun hasConflictMarker(
    content: ByteArray,
    size: Int,
): Boolean {
    var start = 0
    while (start < content.size) {
        val end = (start until content.size).firstOrNull { content[it] == '\n'.code.toByte() } ?: content.size
        val first = content[start]
        if ((first == '<'.code.toByte() || first == '>'.code.toByte()) && end - start >= size) {
            val run = (start until start + size).all { content[it] == first }
            val after = if (start + size < end) content[start + size] else '\n'.code.toByte()
            if (run && (after == ' '.code.toByte() || after == '\r'.code.toByte() || after == '\n'.code.toByte())) {
                return true
            }
        }
        start = end + 1
    }
    return false
}
