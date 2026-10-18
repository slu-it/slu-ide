package dev.sluide.git

import dev.sluide.git.cli.FilteredBlob
import dev.sluide.git.cli.GitCli
import dev.sluide.git.cli.blob
import dev.sluide.git.cli.branchesContaining
import dev.sluide.git.cli.canPassArgument
import dev.sluide.git.cli.changedFiles
import dev.sluide.git.cli.commitOf
import dev.sluide.git.cli.comparisonCounts
import dev.sluide.git.cli.configValues
import dev.sluide.git.cli.currentBranch
import dev.sluide.git.cli.diffBlobs
import dev.sluide.git.cli.diffFiles
import dev.sluide.git.cli.fileHistory
import dev.sluide.git.cli.filteredBlob
import dev.sluide.git.cli.headCommit
import dev.sluide.git.cli.inProgress
import dev.sluide.git.cli.log
import dev.sluide.git.cli.logCommit
import dev.sluide.git.cli.objectInfo
import dev.sluide.git.cli.objectSize
import dev.sluide.git.cli.refs
import dev.sluide.git.cli.resolveRevisions
import dev.sluide.git.cli.show
import dev.sluide.git.cli.storeFiles
import dev.sluide.git.cli.treePaths
import dev.sluide.git.cli.userName
import dev.sluide.git.cli.workingTreeChanges
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeBytes

/**
 * The commits of a repository (ADR 0035): those of all local branches, remote-tracking branches, tags and `HEAD`, in
 * date order, or those a [GitLogFilter] selects (ADR 0039). Stash and notes commits are left out. Also the refs
 * pointing at them and the details of a commit (ADR 0037), and the files a commit changed with their diffs (ADR 0038).
 * Also what a comparison of a ref with `HEAD` counts, and how the working tree differs from a commit (ADR 0044), and
 * the history of a file (ADR 0046).
 */
@Component
class GitLog(
    private val git: GitCli,
) {
    /** Whether the JVM can name the working-tree file at a path (ADR 0044); tests take the fallback through git. */
    internal var canNameFile: (String) -> Boolean = { canPassArgument(it) }

    /** Where the temporary files of a working-tree diff go; `null` for the JVM's temporary folder. */
    internal var temporaryFolder: Path? = null

    /**
     * The commits that pass [filter] (ADR 0039), after the first [skip] ones, at most [limit] of them, in the
     * repository at [root]; `null` if git is not available, fails (also for an invalid regular expression) or takes
     * longer than 10 s, or if a revision or path of the filter has a line break or they are too many (above 15 KB).
     * Runs git on the calling thread.
     */
    fun commits(
        root: Path,
        filter: GitLogFilter = GitLogFilter(),
        skip: Int,
        limit: Int,
    ): GitLogPage? {
        require(skip >= 0) { "skip must not be negative: $skip" }
        require(limit > 0) { "limit must be positive: $limit" }
        val output = git.log(root, filter, skip, limit) ?: return null
        val commits = GitLogParser.parse(output)
        return GitLogPage(commits, complete = commits.size < limit)
    }

    /**
     * The history of the file [path] (relative to [root], `/`-separated) from [start] on (a full hash; `null` for
     * `HEAD`), following renames (ADR 0046): the commits that changed it, newest first, each with the file's path and
     * status there; after the first [skip], at most [limit]. Empty for a file without commits (untracked) and while
     * `HEAD` has no commit. `git log --skip` would not follow renames in the commits it skips, so every page reads the
     * history from its start up to its end and drops the first [skip]. A [cancellation] stops git. `null` if git is not
     * available, fails, takes longer than 60 s or is cancelled, or for a path with a line break. Runs git on the calling
     * thread.
     */
    fun fileHistory(
        root: Path,
        path: String,
        start: String? = null,
        skip: Int,
        limit: Int,
        cancellation: GitCancellation? = null,
    ): GitFileHistoryPage? {
        require(skip >= 0) { "skip must not be negative: $skip" }
        require(limit > 0) { "limit must be positive: $limit" }
        start?.let(::requireHash)
        if (path.isEmpty()) return null
        val wanted = skip + limit
        val output = git.fileHistory(root, path, start ?: "HEAD", wanted, cancellation) ?: return null
        val entries = GitFileHistoryParser.parse(output, path)
        return GitFileHistoryPage(entries.drop(skip), complete = entries.size < wanted)
    }

    /**
     * The commit the text of [filter] names, if it is 4 to 64 hexadecimal characters (trimmed) and names exactly one
     * commit in the repository at [root], and if that commit passes the users, paths and no merges of [filter]; its
     * revisions are not applied (ADR 0039). `null` otherwise, and if git is not available, fails or takes too long.
     * Its parents are all its parents. Runs git on the calling thread.
     */
    fun hashMatch(
        root: Path,
        filter: GitLogFilter,
    ): GitCommit? {
        val prefix = filter.text.trim()
        if (!HASH_PREFIX.matches(prefix)) return null
        val hash = git.commitOf(root, prefix) ?: return null
        // A branch or tag of that name would be resolved first: only a hash counts.
        if (!HASH.matches(hash) || !hash.startsWith(prefix.lowercase())) return null
        val output = git.logCommit(root, hash, filter) ?: return null
        return GitLogParser.parse(output).singleOrNull { it.hash == hash }
    }

    /**
     * The `user.name` git uses in the repository at [root] (ADR 0039), trimmed; `null` if it is not set, or if git is
     * not available, fails or takes longer than 3 s. Runs git on the calling thread.
     */
    fun userName(root: Path): String? = git.userName(root)

    /**
     * The folders and files of `HEAD`'s tree in the repository at [root] (ADR 0039), each folder before what it
     * holds; empty while `HEAD` has no commit yet; `null` if git is not available, fails or takes longer than 10 s.
     * Submodules are files. Runs git on the calling thread.
     */
    fun paths(root: Path): List<GitTreePath>? {
        val head = git.headCommit(root) ?: return if (git.currentBranch(root) != null) emptyList() else null
        val output = git.treePaths(root, head) ?: return null
        return output.split('\u0000').mapNotNull { record ->
            // <mode> <type> <object>\t<path>
            val info = record.substringBefore('\t', "").split(' ')
            if (info.size != 3) return@mapNotNull null
            GitTreePath(record.substringAfter('\t'), folder = info[1] == "tree")
        }
    }

    /**
     * The branches, remote branches, tags and `HEAD` of the repository at [root] (ADR 0037); `null` if git is not
     * available, fails or takes too long. Runs git on the calling thread.
     */
    fun refs(root: Path): GitRefs? {
        val output = git.refs(root) ?: return null
        // Read right after the refs; either fails for an unborn HEAD (no commit) or a detached one (no branch).
        return GitRefsParser.parse(output, git.headCommit(root), git.currentBranch(root))
    }

    /**
     * What git is in the middle of in the repository at [root] (ADR 0042): a single cherry-pick or revert with the
     * paths still unmerged, or a merge, rebase or sequence; `null` if nothing, or if git is not available or fails. Runs
     * git on the calling thread.
     */
    fun inProgress(root: Path): GitInProgress? = git.inProgress(root).getOrNull()

    /**
     * The details of the commit [hash] (its full object name: 40 or 64 hexadecimal characters) in the repository at
     * [root]; `null` if git is not available, fails, takes longer than 3 s or there is no such commit. Runs git on the
     * calling thread.
     */
    fun details(
        root: Path,
        hash: String,
    ): GitCommitDetails? {
        requireHash(hash)
        return git.show(root, hash)?.let(GitCommitDetailsParser::parse)
    }

    /**
     * The full refnames of the local and remote branches that contain the commit [hash] (its full object name) in the
     * repository at [root] (ADR 0045), in git's order, without symbolic refs such as `refs/remotes/origin/HEAD`; `null`
     * if git is not available, fails or takes longer than 10 s. Runs git on the calling thread.
     */
    fun branchesContaining(
        root: Path,
        hash: String,
    ): List<String>? {
        requireHash(hash)
        val output = git.branchesContaining(root, hash) ?: return null
        return output
            .lineSequence()
            .map { it.split('\u001f') }
            .filter { it.size == 2 && it[0].isNotEmpty() && it[1].isEmpty() }
            .map { it[0] }
            .toList()
    }

    /**
     * What each of [revisions] names in the repository at [root] (ADR 0045): a commit (annotated tags peeled), nothing,
     * or several objects (an ambiguous short hash), with one `git cat-file` for all of them; a revision is anything git
     * understands (a full or short hash, a short or full refname, `HEAD~3`, `v1.0^{}`). The revisions go to git's
     * standard input, so non-ASCII names work in any locale. `null` if a revision is blank or has a line break, if they
     * are too many (above about 15 KB), or if git is not available, fails (also for a revision git cannot even look up,
     * such as `@{upstream}` without one) or takes longer than 10 s. Runs git on the calling thread.
     */
    fun resolveCommits(
        root: Path,
        revisions: List<String>,
    ): List<GitRevisionResolution>? {
        if (revisions.isEmpty()) return emptyList()
        if (revisions.any { it.isBlank() }) return null
        val output = git.resolveRevisions(root, revisions) ?: return null
        return GitRevisionParser.parse(output, revisions.size)
    }

    /**
     * The files [commit] changed in the repository at [root] (ADR 0038): against its [firstParent], so a merge is
     * compared with its first parent only, or for a root commit (`null`) against nothing, every file added. Renames
     * are detected as git's `-M` does. Both are full object names (40 or 64 hexadecimal characters). `null` if git
     * is not available, fails or takes longer than 10 s. Runs git on the calling thread.
     */
    fun changedFiles(
        root: Path,
        commit: String,
        firstParent: String?,
    ): List<GitChangedFile>? {
        requireHash(commit)
        firstParent?.let(::requireHash)
        return git.changedFiles(root, commit, firstParent)?.let(GitNameStatusParser::parse)
    }

    /**
     * What [commit] changed in [file], one of its [changedFiles] against [firstParent] (ADR 0038): the raw contents
     * of both sides (no filters such as line endings) and git's hunks between them; [GitFileDiff.Submodule] for a
     * submodule, [GitFileDiff.Binary] or [GitFileDiff.TooLarge] if either side is binary or larger than 5 MB. `null`
     * if git is not available, fails or takes longer than 10 s per call, or if a path has a line break (git reads the
     * names line by line). Runs git on the calling thread.
     */
    fun fileDiff(
        root: Path,
        commit: String,
        firstParent: String?,
        file: GitChangedFile,
    ): GitFileDiff? = fileDiff(root, commit, firstParent, file, MAX_BLOB_SIZE)

    /** [fileDiff] with files above [maxSize] bytes too large. */
    internal fun fileDiff(
        root: Path,
        commit: String,
        firstParent: String?,
        file: GitChangedFile,
        maxSize: Long,
    ): GitFileDiff? {
        requireHash(commit)
        firstParent?.let(::requireHash)
        val added = file.change == GitChange.ADDED || firstParent == null
        val original = if (added) null else "$firstParent:${file.oldPath ?: file.path}"
        val current = if (file.change == GitChange.DELETED) null else "$commit:${file.path}"
        val names = listOfNotNull(original, current)
        // One name per line: a path with a line break would make git read another object.
        if (names.any { '\n' in it }) return null
        // Object name, type and size of each side, the paths given on git's standard input.
        val objects =
            git
                .objectInfo(root, names)
                ?.lines()
                ?.take(names.size)
                ?.map { line -> GitObject.of(line) ?: return null }
                ?: return null
        if (objects.size != names.size) return null
        val originalObject = if (original != null) objects.first() else null
        val currentObject = if (current != null) objects.last() else null
        if (objects.any { it.submodule }) {
            return GitFileDiff.Submodule(
                originalObject?.takeIf { it.submodule }?.name,
                currentObject?.takeIf { it.submodule }?.name,
            )
        }
        if (objects.any { it.size > maxSize }) return GitFileDiff.TooLarge
        val originalBytes = originalObject?.let { git.blob(root, it.name) ?: return null } ?: ByteArray(0)
        val currentBytes = currentObject?.let { git.blob(root, it.name) ?: return null } ?: ByteArray(0)
        if (isBinary(originalBytes) || isBinary(currentBytes)) return GitFileDiff.Binary
        val originalText = originalBytes.toString(Charsets.UTF_8)
        val currentText = currentBytes.toString(Charsets.UTF_8)
        val hunks =
            when {
                originalObject == null -> {
                    wholeFile(lineCount(currentText), added = true)
                }

                currentObject == null -> {
                    wholeFile(lineCount(originalText), added = false)
                }

                // The same content: renamed or only its mode changed.
                originalObject.name == currentObject.name -> {
                    emptyList()
                }

                else -> {
                    git.diffBlobs(root, originalObject.name, currentObject.name)?.let(GitDiffParser::parse)
                        ?: return null
                }
            }
        return GitFileDiff.Text(originalText, currentText, hunks)
    }

    /**
     * How many commits that pass [filter] [revision] (a full refname) has that `HEAD` has not, and the other way round,
     * in the repository at [root] (ADR 0044): the filter's text, users, paths, first parents only and no merges apply as
     * in [commits], its revisions not. The revision and paths go to git's standard input. `null` if git is not
     * available, fails (also if the revision names no commit, or `HEAD` has none) or takes longer than 10 s, or for
     * revisions and paths as [commits] refuses them. Runs git on the calling thread.
     */
    fun comparisonCounts(
        root: Path,
        revision: String,
        filter: GitLogFilter = GitLogFilter(),
    ): GitComparisonCounts? =
        git.comparisonCounts(root, revision, "HEAD", filter)?.let { (refOnly, headOnly) ->
            GitComparisonCounts(refOnly, headOnly)
        }

    /**
     * The tracked files that differ between the commit [revision] names (a full refname, `HEAD` or a full hash) and the
     * working tree at [root] (ADR 0044), renames detected as git's `-M` does; untracked files are not among them.
     * [GitWorkingTreeChanges.Missing] if the revision names no commit. The revision is resolved on git's standard
     * input, so refnames with any characters work. `null` if git is not available, fails or takes longer than 10 s, or
     * for a revision with a line break. Runs git on the calling thread.
     */
    fun workingTreeChanges(
        root: Path,
        revision: String,
    ): GitWorkingTreeChanges? {
        if ('\n' in revision) return null
        val line = git.objectInfo(root, listOf("$revision^{commit}"))?.lineSequence()?.firstOrNull() ?: return null
        val commit =
            COMMIT_OBJECT.matchEntire(line)?.groupValues?.get(1)
                ?: return if (line.endsWith(" missing")) GitWorkingTreeChanges.Missing else null
        val files = git.workingTreeChanges(root, commit)?.let(GitNameStatusParser::parse) ?: return null
        return GitWorkingTreeChanges.Files(commit, files)
    }

    /**
     * How [file], one of the [workingTreeChanges] against [commit] (a full object name), differs between the commit and
     * the working tree at [root] (ADR 0044): the content in the commit as a checkout would write it (with the filters of
     * its path, such as line endings; at its old path if renamed; empty if added) and the file's bytes on disk (empty if
     * deleted), with git's hunks between them, compared as they are; [GitFileDiff.Submodule] (with the
     * commit's side only) for a submodule, [GitFileDiff.Binary] or [GitFileDiff.TooLarge] as for [fileDiff]. `null` if
     * git is not available, fails or takes longer than 10 s per call, if the file cannot be read, or if a path has a
     * line break. Runs git on the calling thread.
     */
    fun workingTreeFileDiff(
        root: Path,
        commit: String,
        file: GitChangedFile,
    ): GitFileDiff? = workingTreeFileDiff(root, commit, file, MAX_BLOB_SIZE)

    /** [workingTreeFileDiff] with files above [maxSize] bytes too large. */
    internal fun workingTreeFileDiff(
        root: Path,
        commit: String,
        file: GitChangedFile,
        maxSize: Long,
    ): GitFileDiff? {
        requireHash(commit)
        val added = file.change == GitChange.ADDED
        val deleted = file.change == GitChange.DELETED
        val original = if (added) null else "$commit:${file.oldPath ?: file.path}"
        // The index tells whether the working tree's side is a submodule, whose folder has no content to compare.
        val index = if (deleted) null else ":${file.path}"
        val names = listOfNotNull(original, index)
        if (names.any { '\n' in it }) return null
        val lines = git.objectInfo(root, names)?.lines()?.take(names.size) ?: return null
        if (lines.size != names.size) return null
        val originalObject = if (original != null) GitObject.of(lines.first()) ?: return null else null
        // Not in the index (e.g. unmerged): no submodule there.
        val indexObject = if (index != null) GitObject.of(lines.last()) else null
        if (originalObject?.submodule == true || indexObject?.submodule == true) {
            return GitFileDiff.Submodule(originalObject?.takeIf { it.submodule }?.name, null)
        }
        if (originalObject != null && originalObject.size > maxSize) return GitFileDiff.TooLarge
        val current =
            if (deleted) ByteArray(0) else workingTreeBytes(root, file.path, maxSize, indexObject) ?: return null
        if (current === TOO_LARGE) return GitFileDiff.TooLarge
        val originalBytes =
            when (
                val blob =
                    originalObject?.let {
                        git.filteredBlob(
                            root,
                            it.name,
                            file.oldPath ?: file.path,
                            maxSize,
                        )
                    }
            ) {
                null -> if (originalObject == null) ByteArray(0) else return null
                FilteredBlob.TooLarge -> return GitFileDiff.TooLarge
                is FilteredBlob.Content -> blob.bytes
            }
        if (isBinary(originalBytes) || isBinary(current)) return GitFileDiff.Binary
        val originalText = originalBytes.toString(Charsets.UTF_8)
        val currentText = current.toString(Charsets.UTF_8)
        val hunks =
            when {
                originalObject == null -> wholeFile(lineCount(currentText), added = true)
                deleted -> wholeFile(lineCount(originalText), added = false)
                originalBytes.contentEquals(current) -> emptyList()
                else -> diffTexts(root, originalBytes, current) ?: return null
            }
        return GitFileDiff.Text(originalText, currentText, hunks)
    }

    /**
     * The bytes of the file [path] in the working tree at [root], as they are on disk; for a symbolic link the path it
     * points to, as git stores a link. Read by the JVM where it can name the file; else (non-ASCII characters in a plain
     * ASCII locale) stored by git from a path on its standard input and read back as a blob (ADR 0044), which follows a
     * symbolic link to the file it points to. [TOO_LARGE] above [maxSize]; `null` if it cannot be read. Before storing a
     * file, the size of its [indexEntry] is taken as a hint: above [maxSize] it is not stored at all.
     */
    private fun workingTreeBytes(
        root: Path,
        path: String,
        maxSize: Long,
        indexEntry: GitObject?,
    ): ByteArray? {
        val local =
            try {
                if (canNameFile(path)) root.resolve(path) else null
            } catch (_: InvalidPathException) {
                null
            }
        if (local == null) {
            // Stored whole by git; a file staged that large is not.
            if (indexEntry != null && indexEntry.size > maxSize) return TOO_LARGE
            val name = git.storeFiles(root, listOf(path))?.singleOrNull() ?: return null
            val size = git.objectSize(root, name) ?: return null
            return if (size > maxSize) TOO_LARGE else git.blob(root, name)
        }
        return try {
            when {
                Files.isSymbolicLink(local) -> {
                    Files.readSymbolicLink(local).toString().toByteArray(Charsets.UTF_8)
                }

                !Files.isRegularFile(local, LinkOption.NOFOLLOW_LINKS) -> {
                    null
                }

                Files.size(local) > maxSize -> {
                    TOO_LARGE
                }

                else -> {
                    Files.readAllBytes(local)
                }
            }
        } catch (_: IOException) {
            null
        }
    }

    /**
     * Git's hunks from [original] to [current]: both written to temporary files with plain names and compared as they
     * are with `git diff --no-index` (ADR 0044), so no repository path goes on the command line, with the repository's
     * `diff.algorithm`; `null` on failure. The temporary folder is deleted afterwards.
     */
    private fun diffTexts(
        root: Path,
        original: ByteArray,
        current: ByteArray,
    ): List<GitHunk>? {
        val algorithm = git.configValues(root, "diff.algorithm")?.lastOrNull()
        val folder =
            try {
                temporaryFolder?.let { Files.createTempDirectory(it, "sluide-diff") }
                    ?: Files.createTempDirectory("sluide-diff")
            } catch (_: IOException) {
                return null
            }
        val files = listOf("original", "current", "attributes").map(folder::resolve)
        return try {
            files[0].writeBytes(original)
            files[1].writeBytes(current)
            git.diffFiles(folder, "original", "current", algorithm)?.let(GitDiffParser::parse)
        } catch (_: IOException) {
            null
        } finally {
            try {
                files.forEach { it.deleteIfExists() }
                folder.deleteIfExists()
            } catch (_: IOException) {
                // Left in the temporary folder.
            }
        }
    }

    private fun requireHash(hash: String) = require(HASH.matches(hash)) { "not a commit hash: $hash" }

    /** One hunk of all [lines] of a file added or deleted as a whole; none for an empty file. */
    private fun wholeFile(
        lines: Int,
        added: Boolean,
    ): List<GitHunk> {
        if (lines == 0) return emptyList()
        val none = 0 until 0
        val all = 0 until lines
        return listOf(if (added) GitHunk(none, all) else GitHunk(all, none))
    }

    /** The lines of [text] as git counts them: a final line break does not start another line. */
    private fun lineCount(text: String): Int =
        if (text.isEmpty()) 0 else text.count { it == '\n' } + if (text.endsWith('\n')) 0 else 1

    /** Git's own test: a NUL byte in the first 8000 bytes. */
    private fun isBinary(bytes: ByteArray): Boolean {
        for (index in 0 until minOf(bytes.size, BINARY_SAMPLE)) if (bytes[index] == 0.toByte()) return true
        return false
    }

    private companion object {
        val HASH = Regex("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}")

        val HASH_PREFIX = Regex("[0-9a-fA-F]{4,64}")

        /** A line of `git cat-file --batch-check` for a commit. */
        val COMMIT_OBJECT = Regex("([0-9a-f]{40}|[0-9a-f]{64}) commit \\d+")

        /** Stands for a working-tree file above the size limit; compared by identity. */
        val TOO_LARGE = ByteArray(0)

        const val MAX_BLOB_SIZE = 5L * 1024 * 1024

        const val BINARY_SAMPLE = 8000
    }
}

/**
 * A line of `git cat-file --batch-check`: a blob (`<name> blob <size>`) or a submodule's commit (`<name> submodule`,
 * a gitlink, whose object is not in the repository).
 */
private class GitObject(
    val name: String,
    val size: Long,
    val submodule: Boolean,
) {
    companion object {
        private val BLOB = Regex("([0-9a-f]{40}|[0-9a-f]{64}) blob (\\d+)")
        private val SUBMODULE = Regex("([0-9a-f]{40}|[0-9a-f]{64}) submodule")

        fun of(line: String): GitObject? {
            BLOB.matchEntire(line)?.let { match ->
                val size = match.groupValues[2].toLongOrNull() ?: return null
                return GitObject(match.groupValues[1], size, submodule = false)
            }
            return SUBMODULE.matchEntire(line)?.let { GitObject(it.groupValues[1], 0, submodule = true) }
        }
    }
}
