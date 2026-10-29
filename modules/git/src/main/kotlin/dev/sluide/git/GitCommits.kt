package dev.sluide.git

import dev.sluide.git.GitConflictCheck.Companion.GITLINK
import dev.sluide.git.cli.GitCli
import dev.sluide.git.cli.canPassArgument
import dev.sluide.git.cli.changedPaths
import dev.sluide.git.cli.commitOfRevision
import dev.sluide.git.cli.configFlag
import dev.sluide.git.cli.currentBranch
import dev.sluide.git.cli.emptyTree
import dev.sluide.git.cli.gitPaths
import dev.sluide.git.cli.headBranch
import dev.sluide.git.cli.headCommit
import dev.sluide.git.cli.headMessage
import dev.sluide.git.cli.inProgress
import dev.sluide.git.cli.indexChanges
import dev.sluide.git.cli.indexMatchesHead
import dev.sluide.git.cli.indexedPaths
import dev.sluide.git.cli.isAncestor
import dev.sluide.git.cli.isHeadUnborn
import dev.sluide.git.cli.mergeTree
import dev.sluide.git.cli.otherPaths
import dev.sluide.git.cli.parents
import dev.sluide.git.cli.refusal
import dev.sluide.git.cli.trackedChangeCount
import dev.sluide.git.cli.unmergedEntries
import dev.sluide.git.cli.unmergedPaths
import dev.sluide.git.cli.unreachableCount
import dev.sluide.git.cli.untrackedPaths
import dev.sluide.git.cli.workingTreeChanges
import dev.sluide.git.parse.GitNameStatusParser
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale

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
    /** Whether the JVM can name the working-tree file at a path (ADR 0038); tests take the fallback through git. */
    internal var canNameFile: (String) -> Boolean = { canPassArgument(it) }

    private val conflicts = GitConflictCheck(git) { canNameFile(it) }

    /** What git is in the middle of, `null` if nothing or it cannot be read. */
    fun inProgress(root: Path): GitInProgress? = git.inProgress(root).getOrNull()

    /** The commit `HEAD` points at (a full hash); `null` if there is none or it cannot be read. */
    fun headCommit(
        root: Path,
        cancellation: GitCancellation? = null,
    ): String? = git.headCommit(root, cancellation = cancellation)

    /**
     * What can be committed in the repository at [root] (ADR 0048): the tracked changes against `HEAD` (renames
     * included; with no commit yet, every file in the index as added), the untracked files that are not ignored (each
     * one, also inside untracked folders; a nested repository is listed as its folder with a trailing `/`, and
     * committing it records a submodule entry without a `.gitmodules`), what git is in the middle of (also unmerged
     * paths alone, [GitPendingConflicts]), and whether `HEAD` is already on the current branch's upstream. `null` if
     * git fails.
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
        return GitCommitChanges(files, untracked, busy, head != null && isPushed(root, head))
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
     * amend has no commit to amend, git is in the middle of anything ([inProgress], or that cannot be read; also
     * unmerged paths). Checked untracked files are staged first (`git --literal-pathspecs add`), then
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
        val busy = git.inProgress(root).getOrElse { return refused(IN_PROGRESS_UNREADABLE) }
        // Unmerged paths count as in progress (GitPendingConflicts); any after this read, git refuses itself.
        if (busy != null) return refused(busyMessage(busy))
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
        val busy = git.inProgress(root).getOrElse { return GitPickResult.Failed(IN_PROGRESS_UNREADABLE) }
        if (busy != null) return GitPickResult.Failed(busyMessage(busy))
        val head = git.headCommit(root)
        val arguments =
            buildList {
                add(kind.command)
                if (kind == GitPick.REVERT) add("--no-edit")
                if (firstParent) addAll(listOf("-m", "1"))
                addAll(listOf("--end-of-options", commit))
            }
        val (result, exit) = git.writeWithExit(root, arguments, timeout = GitCli.CHECKOUT_TIMEOUT, writesFiles = true)
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
        val pending = git.inProgress(root).getOrElse { return GitPickResult.Failed(IN_PROGRESS_UNREADABLE) }
        if (pending != null && pending !is GitPendingPick) return GitPickResult.Failed(busyMessage(pending))
        if (pending !is GitPendingPick || pending.kind != kind) {
            return GitPickResult.Failed("No ${kind.command} is in progress.")
        }
        val entries = git.unmergedEntries(root) ?: return GitPickResult.Failed(CONFLICTS_UNREADABLE)
        if (entries.isNotEmpty()) {
            val check = conflicts.check(root, entries) ?: return GitPickResult.Failed(CONFLICTS_UNREADABLE)
            if (check.markers.isNotEmpty()) return GitPickResult.MarkersLeft(check.markers)
            check.refusal?.let { return GitPickResult.Failed(it) }
            if (!confirmed.containsAll(check.unchecked)) return GitPickResult.Unchecked(check.unchecked)
            val staged = conflicts.stage(root, entries)
            if (!staged.success) return GitPickResult.Failed(staged.message)
        }
        val head = git.headCommit(root)
        val (result, exit) =
            git.writeWithExit(
                root,
                listOf(kind.command, "--continue", "--no-edit"),
                timeout = GitCli.CHECKOUT_TIMEOUT,
                writesFiles = true,
            )
        return outcome(root, kind, pending.commit, head, result, exit)
    }

    /**
     * Aborts the cherry-pick or revert ([kind]) in progress: `git cherry-pick --abort`, `git revert --abort`. Refused
     * without running git if there is none of that kind, or a sequence or another operation is in progress.
     */
    fun abortPick(
        root: Path,
        kind: GitPick,
    ): GitResult {
        val pending = git.inProgress(root).getOrElse { return GitResult(false, IN_PROGRESS_UNREADABLE) }
        if (pending != null && pending !is GitPendingPick) return GitResult(false, busyMessage(pending))
        if (pending !is GitPendingPick || pending.kind != kind) {
            return GitResult(false, "No ${kind.command} is in progress.")
        }
        return git.write(root, listOf(kind.command, "--abort"), timeout = GitCli.CHECKOUT_TIMEOUT, writesFiles = true)
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
        val cleared =
            git.write(
                root,
                listOf(kind.command, "--skip"),
                timeout = GitCli.CHECKOUT_TIMEOUT,
                writesFiles = true,
            )
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
        val message =
            git.gitPaths(root, listOf("MERGE_MSG"))?.single() ?: return GitPickResult.Failed(IN_PROGRESS_UNREADABLE)
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
     * How many files have staged changes that differ from the file on disk, e.g. staged in part with `git add -p`: their
     * staged version is neither in `HEAD` nor on disk, so a mixed reset, which replaces the index, loses it. Those whose
     * index differs both from `HEAD` and from the working tree; not unmerged ones (their stages come from commits) nor
     * submodules whose checkout only has changes inside. `null` if that cannot be read.
     */
    fun stagedOnlyCount(root: Path): Int? {
        val staged = git.changedPaths(root, staged = true) ?: return null
        val unstaged = git.changedPaths(root, staged = false) ?: return null
        return staged.count { it in unstaged }
    }

    /**
     * Whether git refuses a soft reset: during a merge (`MERGE_HEAD`) or while paths are unmerged ("Cannot do a soft
     * reset in the middle of a merge."); `null` if that cannot be read.
     */
    fun softResetRefused(root: Path): Boolean? {
        val unmerged = git.unmergedPaths(root) ?: return null
        return unmerged.isNotEmpty() || git.commitOfRevision(root, "MERGE_HEAD") != null
    }

    /**
     * What on disk a hard reset to [target] (a full hash) would overwrite or delete although it is not tracked, as git
     * does it without a word, by path relative to [root]: for the files and submodules of [target] that are not in the
     * index (`git diff-index --cached`, status `D`), an untracked or ignored file or link at that path, at a folder of
     * it (then nothing below it is checked: git replaces the file or link alone), or, for a file, the untracked and
     * ignored files in a folder at that path, which git deletes with it (a submodule's folder is kept). Tracked files
     * are left out, also under another case where `core.ignorecase` is set (a rename `README.md` to `Readme.md`).
     * Checked by the JVM where it can name the path; else (non-ASCII characters in a plain ASCII locale) by git's list
     * of every file not in the index. `null` if that cannot be read.
     */
    fun overwrittenFiles(
        root: Path,
        target: String,
    ): List<String>? {
        if (refusal(target) != null) return null
        val output = git.indexChanges(root, target) ?: return null
        val ignoreCase = git.configFlag(root, "core.ignorecase") ?: return null
        // :<target's mode> <index's mode> <object> <object> <status> NUL <path> NUL
        val records =
            output.split('\u0000').chunked(2).filter { it.size == 2 && it[0].startsWith(':') }.map { (info, path) ->
                val fields = info.removePrefix(":").split(' ')
                Triple(fields.first(), fields.last().firstOrNull(), path)
            }
        val key: (String) -> String = { if (ignoreCase) it.lowercase(Locale.ROOT) else it }
        // Paths only the index has (A), and unmerged ones: tracked, whatever the target says.
        val indexed = records.filter { it.second == 'A' || it.second == 'U' }.mapTo(HashSet()) { key(it.third) }
        val disk = Disk(root) { key(it) in indexed }
        val found = LinkedHashSet<String>()
        for ((mode, status, path) in records) {
            if (status == 'D') found += disk.overwritten(path, submodule = mode == GITLINK) ?: return null
        }
        return found.toList()
    }

    /**
     * What a cherry-pick or revert ([kind]) of [commit] (a full hash; a merge commit relative to its first parent if
     * [firstParent]) would overwrite or delete although git ignores it, as git does it without a word (for untracked
     * files that are not ignored it refuses), by path relative to [root]: what [overwrittenFiles] finds for the tree
     * git's merge would give (`git merge-tree`, with the commit's parent, or the empty tree for a root commit, as the
     * merge base for a cherry-pick, as the other side for a revert), without the untracked files that are not ignored.
     * Onto a branch without commits (not a `HEAD` that cannot be read) it merges onto the empty tree. Empty for a merge
     * commit without [firstParent] or with staged changes, which git refuses; `null` if that cannot be read (also with
     * git older than 2.40, or before 2.44 for a root commit or a branch without commits, which take a tree to merge).
     */
    fun pickOverwrites(
        root: Path,
        kind: GitPick,
        commit: String,
        firstParent: Boolean,
    ): List<String>? {
        if (refusal(commit) != null) return null
        val parents = git.parents(root, commit) ?: return null
        if (parents.size > 1 && !firstParent) return emptyList()
        // Staged changes: git refuses ("your local changes would be overwritten"). Without a HEAD commit there is none.
        if (git.indexMatchesHead(root) == false) return emptyList()
        val emptyTree by lazy { git.emptyTree(root) }
        val parent = parents.firstOrNull() ?: emptyTree ?: return null
        // Onto a branch without commits git picks onto nothing; a HEAD that cannot be read is no answer.
        val unborn = git.isHeadUnborn(root) ?: return null
        val ours = if (unborn) emptyTree ?: return null else "HEAD"
        val tree =
            when (kind) {
                GitPick.CHERRY_PICK -> git.mergeTree(root, base = parent, ours = ours, theirs = commit)
                GitPick.REVERT -> git.mergeTree(root, base = commit, ours = ours, theirs = parent)
            } ?: return null
        val overwritten = overwrittenFiles(root, tree) ?: return null
        if (overwritten.isEmpty()) return overwritten
        val untracked = git.untrackedPaths(root)?.toSet() ?: return null
        return overwritten.filterNot { it in untracked }
    }

    /** What is on disk at a path, as a reset to a commit sees it. */
    private enum class Entry { MISSING, FOLDER, FILE }

    /**
     * The working tree at [root] as the JVM sees it, or git for the paths the JVM cannot name; [tracked] tells the
     * paths in the index.
     */
    private inner class Disk(
        private val root: Path,
        private val tracked: (String) -> Boolean,
    ) {
        /** Every file not in the index, read once if needed; `null` if git failed. */
        private val others by lazy { git.otherPaths(root)?.toSet() }

        /**
         * What a checkout of the file or [submodule] at [path] overwrites or deletes that is not tracked; `null` if that
         * cannot be read.
         */
        fun overwritten(
            path: String,
            submodule: Boolean,
        ): List<String>? {
            val folders = path.split('/').dropLast(1).runningReduce { folder, name -> "$folder/$name" }
            for (folder in folders) {
                // A tracked file in place of the folder: a local change at most, and nothing can be below it.
                if (tracked(folder)) return emptyList()
                when (entry(folder) ?: return null) {
                    Entry.MISSING -> {
                        return emptyList()
                    }

                    Entry.FILE -> {
                        return listOf(folder)
                    }

                    Entry.FOLDER -> {}
                }
            }
            if (tracked(path)) return emptyList()
            return when (entry(path) ?: return null) {
                Entry.MISSING -> emptyList()
                Entry.FILE -> listOf(path)
                Entry.FOLDER -> if (submodule) emptyList() else untrackedBelow(path)
            }
        }

        /** What is at [path], not following a link (a link is a file); `null` if that cannot be read. */
        private fun entry(path: String): Entry? {
            val file = localFile(root, path, canNameFile)
            if (file == null) {
                val listed = others ?: return null
                return when {
                    path in listed -> Entry.FILE
                    listed.any { it.startsWith("$path/") } -> Entry.FOLDER
                    else -> Entry.MISSING
                }
            }
            return when {
                Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS) -> Entry.FOLDER
                Files.exists(file, LinkOption.NOFOLLOW_LINKS) -> Entry.FILE
                else -> Entry.MISSING
            }
        }

        /**
         * The files and links below the folder [path] that are not tracked, a nested repository (a folder with `.git`)
         * once as its folder with a trailing `/`, as git lists it; `null` if that cannot be read.
         */
        private fun untrackedBelow(path: String): List<String>? {
            val folder =
                localFile(root, path, canNameFile) ?: return others?.filter { it.startsWith("$path/") && !tracked(it) }
            val found = mutableListOf<String>()

            fun relative(file: Path) =
                (
                    listOf(
                        path,
                    ) + folder.relativize(file).map(Path::toString)
                ).filter(String::isNotEmpty).joinToString("/")
            return try {
                Files.walkFileTree(
                    folder,
                    object : SimpleFileVisitor<Path>() {
                        override fun preVisitDirectory(
                            dir: Path,
                            attributes: BasicFileAttributes,
                        ): FileVisitResult {
                            val repository = Files.exists(dir.resolve(".git"), LinkOption.NOFOLLOW_LINKS)
                            if (!repository) return FileVisitResult.CONTINUE
                            // Its history goes with it: one entry, not its objects.
                            found += relative(dir) + "/"
                            return FileVisitResult.SKIP_SUBTREE
                        }

                        override fun visitFile(
                            file: Path,
                            attributes: BasicFileAttributes,
                        ): FileVisitResult {
                            relative(file).takeIf { !tracked(it) }?.let(found::add)
                            return FileVisitResult.CONTINUE
                        }
                    },
                )
                found.sorted()
            } catch (_: IOException) {
                null
            }
        }
    }

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
            // A soft reset moves only the branch, a mixed one writes the index at once, as git always does.
            writesFiles = mode == GitResetMode.HARD,
        )
    }

    private companion object {
        const val LITERAL = "--literal-pathspecs"
    }
}
