package dev.sluide.git

import dev.sluide.git.cli.GitCli
import dev.sluide.git.cli.MAX_BATCH_OUTPUT
import dev.sluide.git.cli.blobs
import dev.sluide.git.cli.branchRefs
import dev.sluide.git.cli.changedFiles
import dev.sluide.git.cli.commitOfRevision
import dev.sluide.git.cli.currentBranch
import dev.sluide.git.cli.emptyTree
import dev.sluide.git.cli.isAncestor
import dev.sluide.git.cli.mergeBase
import dev.sluide.git.cli.objectInfos
import dev.sluide.git.cli.porcelainStatus
import dev.sluide.git.cli.refusedName
import dev.sluide.git.cli.remoteNames
import dev.sluide.git.cli.reviewLog
import dev.sluide.git.cli.untrackedPaths
import dev.sluide.git.cli.workingTreeChanges
import dev.sluide.git.parse.GitLogParser
import dev.sluide.git.parse.GitNameStatusParser
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.HexFormat

/** The newer side of a review's diff (ADR 0168): a commit, or the working tree with its untracked files. */
sealed interface GitReviewSide {
    /** The commit [hash], a full object name. */
    data class Commit(
        val hash: String,
    ) : GitReviewSide

    data object WorkingTree : GitReviewSide
}

/**
 * What the review window reads from git (ADR 0168): the base candidates and branches, the commits and files of a range
 * and their diffs, and a fingerprint that tells when they changed. A range runs from `from`, a full hash or `null` for
 * the empty tree (the parent of a root commit), to a [GitReviewSide]. Hashes given are full object names (40 or 64
 * hexadecimal characters); anything else is refused with an [IllegalArgumentException]. Every method runs git on the
 * calling thread, never the JavaFX thread; those that return `null` do so when git is not available, fails, takes too
 * long or is stopped by its cancellation.
 */
@Component
class GitReviews(
    private val git: GitCli,
) {
    /** The size in bytes above which a file is too large to be diffed. */
    internal var maxSize = MAX_BLOB_SIZE

    /** The most bytes of blobs one `git cat-file --batch` reads, a single larger blob aside. */
    internal var maxBatchOutput = MAX_BATCH_OUTPUT

    /** Reads the working-tree diffs as the git log's comparison with the working tree does (ADR 0044). */
    private val workingTree = GitLog(git)

    /**
     * The full refnames of the default branches to review [head] (`HEAD`, or a full refname) against, best first: the
     * remote ones (first the remote of [head]'s upstream, or for a remote branch its own remote; then `origin`, then
     * the others by name; for each the target of `refs/remotes/<remote>/HEAD`, else its `main`, then `master`), then
     * the local `main` and `master`, then local branches that track one of the same name. Only refs that exist and are
     * not symbolic (a `main` that stands for `master`); empty if there are none or git fails.
     */
    fun defaultBases(
        root: Path,
        head: String = "HEAD",
    ): List<String> {
        val refs = git.branchRefs(root) ?: return emptyList()
        val remotes = git.remoteNames(root) ?: return emptyList()
        val branch = if (head == "HEAD") git.currentBranch(root) else head
        // A remote branch is its own remote's; a local one its upstream's (a remote branch, or empty).
        val upstream = branch?.takeIf { it.startsWith(REMOTE) } ?: refs.firstOrNull { it.name == branch }?.upstream
        return reviewBases(refs, remotes, upstream.orEmpty())
    }

    /**
     * The full refnames of the local branches, then of the remote branches, each sorted, without symbolic refs such as
     * `refs/remotes/origin/HEAD`; empty if git fails.
     */
    fun branches(root: Path): List<String> {
        val names =
            git
                .branchRefs(root)
                ?.filter { it.target.isEmpty() && !(it.name.startsWith(REMOTE) && it.name.endsWith("/HEAD")) }
                ?.map { it.name }
                ?: return emptyList()
        return names.filter { it.startsWith(LOCAL) }.sorted() + names.filter { it.startsWith(REMOTE) }.sorted()
    }

    /** The full hash of the commit [revision] (a full refname, `HEAD` or a hash) names; `null` if none. */
    fun resolveCommit(
        root: Path,
        revision: String,
    ): String? = git.commitOfRevision(root, revision)

    /** The best common ancestor of the commits [a] and [b] (hashes or full refnames); `null` also if they have none. */
    fun mergeBase(
        root: Path,
        a: String,
        b: String,
        cancellation: GitCancellation? = null,
    ): String? = git.mergeBase(root, a, b, cancellation)

    /** Whether [ancestor] is an ancestor of (or the same commit as) [descendant] (hashes or full refnames). */
    fun isAncestor(
        root: Path,
        ancestor: String,
        descendant: String,
    ): Boolean? = git.isAncestor(root, ancestor, descendant)

    /**
     * The commits [to] has and [from] has not (all of [to]'s history for a `null` [from]), at most [limit], newest
     * first in topological order; their parents are all their parents.
     */
    fun commits(
        root: Path,
        from: String?,
        to: String,
        limit: Int = 1000,
        cancellation: GitCancellation? = null,
    ): List<GitCommit>? {
        require(limit > 0) { "limit must be positive: $limit" }
        from?.let(::requireHash)
        requireHash(to)
        return git.reviewLog(root, from, to, limit, cancellation)?.let(GitLogParser::parse)
    }

    /** Whether tracked files have changes, staged or not, or there are untracked files that are not ignored. */
    fun hasUncommittedChanges(root: Path): Boolean? = git.porcelainStatus(root)?.isNotEmpty()

    /**
     * The files that differ from [from] to [to], renames detected as git's `-M` does: to a commit as the git log's
     * changed files; to the working tree the tracked files (staged or not) and the untracked ones that are not ignored
     * ([GitChange.ADDED]), sorted by path. A tracked file that is no longer in the index but still on disk is
     * [GitChange.MODIFIED].
     */
    fun changedFiles(
        root: Path,
        from: String?,
        to: GitReviewSide,
        cancellation: GitCancellation? = null,
    ): List<GitChangedFile>? {
        from?.let(::requireHash)
        val files =
            when (to) {
                is GitReviewSide.Commit -> {
                    requireHash(to.hash)
                    git.changedFiles(root, to.hash, from, cancellation)?.let(GitNameStatusParser::parse)
                }

                GitReviewSide.WorkingTree -> {
                    workingTreeFiles(root, from, cancellation)
                }
            }
        return files?.takeUnless { cancellation?.cancelled == true }
    }

    /**
     * How each of [files] (some of [changedFiles] between the same sides) differs from [from] to [to], by its path:
     * the texts and git's hunks, ignoring all whitespace if [ignoreWhitespace] (a file whose changes are all whitespace
     * then has none), or why there are none to show, as the git log's diffs of a commit (ADR 0038) and of the working
     * tree (ADR 0044). A file that cannot be read is left out; a path with a line break or a carriage return too.
     */
    fun fileDiffs(
        root: Path,
        from: String?,
        to: GitReviewSide,
        files: List<GitChangedFile>,
        ignoreWhitespace: Boolean,
        cancellation: GitCancellation? = null,
    ): Map<String, GitFileDiff>? {
        from?.let(::requireHash)
        val diffs =
            when (to) {
                is GitReviewSide.Commit -> {
                    requireHash(to.hash)
                    commitDiffs(root, from, to.hash, files, ignoreWhitespace, cancellation)
                }

                GitReviewSide.WorkingTree -> {
                    workingTreeDiffs(root, from, files, ignoreWhitespace, cancellation)
                }
            }
        return diffs?.takeUnless { cancellation?.cancelled == true }
    }

    /**
     * A text that changes when one of [revisions] (refnames or hashes, in this order) names another commit or none, and
     * with [workingTree] when the status of a tracked or untracked file changes, or the size or modification time of
     * one with changes; the same otherwise.
     */
    fun fingerprint(
        root: Path,
        revisions: List<String>,
        workingTree: Boolean,
    ): String? {
        val digest = MessageDigest.getInstance("SHA-256")

        fun add(text: String) = digest.update("$text\u0000".toByteArray(Charsets.UTF_8))
        if (revisions.isNotEmpty()) {
            git.objectInfos(root, revisions.map { "$it^{commit}" })?.forEach(::add) ?: return null
        }
        if (workingTree) {
            val status = git.porcelainStatus(root) ?: return null
            // The records are sorted by path; each `XY <path>`.
            for (record in status) {
                add(record)
                add(fileStamp(root, record.drop(STATUS_PREFIX)))
            }
        }
        return HexFormat.of().formatHex(digest.digest())
    }

    private fun workingTreeFiles(
        root: Path,
        from: String?,
        cancellation: GitCancellation?,
    ): List<GitChangedFile>? {
        val base = from ?: git.emptyTree(root) ?: return null
        val tracked = git.workingTreeChanges(root, base, cancellation)?.let(GitNameStatusParser::parse) ?: return null
        val untracked = git.untrackedPaths(root, cancellation) ?: return null
        val untrackedPaths = untracked.toSet()
        // Removed from the index only (`git rm --cached`): deleted for git, but still on disk, so compared with it.
        val files =
            tracked.map { file ->
                val kept = file.change == GitChange.DELETED && file.path in untrackedPaths
                if (kept) file.copy(change = GitChange.MODIFIED) else file
            }
        val listed = files.mapTo(HashSet()) { it.path }
        val added = untracked.filterNot { it in listed }.map { GitChangedFile(GitChange.ADDED, it) }
        return (files + added).sortedBy { it.path }
    }

    private fun commitDiffs(
        root: Path,
        from: String?,
        to: String,
        files: List<GitChangedFile>,
        ignoreWhitespace: Boolean,
        cancellation: GitCancellation?,
    ): Map<String, GitFileDiff>? {
        // One name per line: a path with a line break or a carriage return would make git read another object.
        val readable = files.filter { file -> listOfNotNull(file.path, file.oldPath).none(::refusedName) }
        val sides =
            readable.map { file ->
                val original =
                    if (file.change == GitChange.ADDED || from == null) null else "$from:${file.oldPath ?: file.path}"
                val current = if (file.change == GitChange.DELETED) null else "$to:${file.path}"
                original to current
            }
        val names = sides.flatMap { listOfNotNull(it.first, it.second) }.distinct()
        val infos = git.objectInfos(root, names, cancellation) ?: return null
        val objects = names.zip(infos) { name, line -> name to GitObject.of(line) }.toMap()
        // Only the blobs that are compared: no submodules, none of a file with a side too large.
        val wanted =
            sides
                .map { (original, current) -> listOfNotNull(original, current).map { objects[it] } }
                .filter { side -> side.none { it == null || it.submodule || it.size > maxSize } }
                .flatMap { side -> side.filterNotNull() }
                .associate { it.name to it.size }
        val blobs = git.blobs(root, wanted, cancellation, maxBatchOutput) ?: return null
        val diffs = LinkedHashMap<String, GitFileDiff>()
        for ((file, side) in readable.zip(sides)) {
            val (original, current) = side
            val originalObject = original?.let(objects::get)
            val currentObject = current?.let(objects::get)
            // Not found on a side it should be on: left out.
            if ((original != null && originalObject == null) || (current != null && currentObject == null)) continue
            val diff =
                git.blobDiff(root, originalObject, currentObject, maxSize, cancellation, ignoreWhitespace) {
                    blobs[it.name]
                }
            if (diff != null) {
                diffs[file.path] = diff
            } else if (cancellation?.cancelled == true) {
                return null
            }
        }
        return diffs
    }

    private fun workingTreeDiffs(
        root: Path,
        from: String?,
        files: List<GitChangedFile>,
        ignoreWhitespace: Boolean,
        cancellation: GitCancellation?,
    ): Map<String, GitFileDiff>? {
        val base = from ?: git.emptyTree(root) ?: return null
        val diffs = LinkedHashMap<String, GitFileDiff>()
        for (file in files) {
            if (cancellation?.cancelled == true) return null
            workingTree.workingTreeFileDiff(root, base, file, maxSize, ignoreWhitespace, cancellation)?.let {
                diffs[file.path] = it
            }
        }
        return diffs
    }

    /** The size and modification time of the file [path] (not following a link), or that it cannot be read. */
    private fun fileStamp(
        root: Path,
        path: String,
    ): String =
        try {
            val attributes =
                Files.readAttributes(root.resolve(path), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            "${attributes.size()} ${attributes.lastModifiedTime().toInstant()}"
        } catch (_: IOException) {
            "missing"
        } catch (_: InvalidPathException) {
            "unnamed"
        }

    private fun requireHash(hash: String) = require(HASH.matches(hash)) { "not a commit hash: $hash" }

    private companion object {
        val HASH = Regex("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}")

        const val LOCAL = "refs/heads/"

        const val REMOTE = "refs/remotes/"

        /** The status letters and the space before the path in a line of `git status --porcelain`. */
        const val STATUS_PREFIX = 3
    }
}
