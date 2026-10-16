package dev.sluide.git

import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * One page of the git log: up to the asked number of [commits], newest first. [complete] means there are no more
 * after them; a first page that is empty and complete is a repository without commits yet.
 */
data class GitLogPage(
    val commits: List<GitCommit>,
    val complete: Boolean,
)

/**
 * The commits of a repository (ADR 0035): those of all local branches, remote-tracking branches, tags and `HEAD`, in
 * date order. Stash and notes commits are left out. Also the refs pointing at them and the details of a commit
 * (ADR 0037), and the files a commit changed with their diffs (ADR 0038).
 */
@Component
class GitLog(
    private val git: GitCli,
) {
    /**
     * The commits after the first [skip] ones, at most [limit] of them, in the repository at [root]; `null` if git is
     * not available, fails or takes longer than 10 s. Runs git on the calling thread.
     */
    fun commits(
        root: Path,
        skip: Int,
        limit: Int,
    ): GitLogPage? {
        require(skip >= 0) { "skip must not be negative: $skip" }
        require(limit > 0) { "limit must be positive: $limit" }
        val output = git.log(root, skip, limit) ?: return null
        val commits = GitLogParser.parse(output)
        return GitLogPage(commits, complete = commits.size < limit)
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
