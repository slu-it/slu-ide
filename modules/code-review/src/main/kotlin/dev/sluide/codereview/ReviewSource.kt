package dev.sluide.codereview

import dev.sluide.fileviewers.LineChange
import java.nio.file.Path
import java.time.Instant

/** What a review shows (ADR 0168). */
sealed interface ReviewScope {
    /** The branch [head] (a full refname or `HEAD`) since it left [base] (a full refname, `null` for the default). */
    data class Branch(
        val head: String,
        val base: String? = null,
    ) : ReviewScope

    /** The commits from [oldest]'s first parent to [newest] (full hashes; equal for one commit). */
    data class Commits(
        val oldest: String,
        val newest: String,
    ) : ReviewScope
}

/** The newer side of a diff: a commit or the working tree. */
sealed interface ReviewRevision {
    data class Commit(
        val hash: String,
    ) : ReviewRevision

    data object WorkingTree : ReviewRevision
}

/** A branch a branch review can be based on: [ref] is the full refname, [label] what the base picker shows. */
data class ReviewBase(
    val ref: String,
    val label: String,
    val isDefault: Boolean,
)

/** A commit of the reviewed range, as the commit picker shows it. */
data class ReviewCommit(
    val hash: String,
    val shortHash: String,
    val subject: String,
    val author: String,
    val time: Instant,
    val firstParent: String?,
)

/**
 * A [ReviewScope] resolved to commits: the diff runs from [from] (`null` for the empty tree) to [head], or to the
 * working tree when [uncommitted] (the reviewed head is the checked-out `HEAD` and has uncommitted changes).
 */
data class ResolvedReview(
    val title: String,
    val base: ReviewBase?,
    val from: String?,
    val head: String,
    /** Newest first. */
    val commits: List<ReviewCommit>,
    val uncommitted: Boolean,
)

/** How a file changed. */
enum class ReviewChange { ADDED, MODIFIED, DELETED, RENAMED, COPIED, TYPE_CHANGED }

/** A changed file: [path] is the new path (the old one for a deleted file), [oldPath] set for renames and copies. */
data class ReviewFile(
    val change: ReviewChange,
    val path: String,
    val oldPath: String? = null,
)

/** The content of a changed file's diff. */
sealed interface ReviewFileContent {
    /** Both texts and git's line changes (`-U0`) between them. */
    data class Text(
        val original: String,
        val current: String,
        val changes: List<LineChange>,
    ) : ReviewFileContent

    data object Binary : ReviewFileContent

    /** Over git's size limit for showing a file. */
    data object TooLarge : ReviewFileContent

    /** A submodule moved from commit [old] to [new]; `null` for a missing side. */
    data class Submodule(
        val old: String?,
        val new: String?,
    ) : ReviewFileContent
}

/** A failure to read a review; shown as "Could not read the review: <message>". */
class ReviewException(
    message: String,
) : Exception(message)

/** Reads reviews from a repository. All calls block and never run on the FX thread. */
interface ReviewSource {
    /** The bases a branch review can pick: the default candidates first, then local, then remote branches. */
    fun bases(root: Path): List<ReviewBase>

    /** Resolves [scope] to commits; throws [ReviewException] with git's reason. */
    fun resolve(
        root: Path,
        scope: ReviewScope,
        cancellation: ReviewCancellation,
    ): ResolvedReview

    /** The files changed from [from] (`null` for the empty tree) to [to]. */
    fun changedFiles(
        root: Path,
        from: String?,
        to: ReviewRevision,
        cancellation: ReviewCancellation,
    ): List<ReviewFile>

    /** The diffs of [files] from [from] to [to], keyed by path. */
    fun fileDiffs(
        root: Path,
        from: String?,
        to: ReviewRevision,
        files: List<ReviewFile>,
        ignoreWhitespace: Boolean,
        cancellation: ReviewCancellation,
    ): Map<String, ReviewFileContent>

    /** A value that changes when what [review] shows changes, `null` when it cannot be read. */
    fun fingerprint(
        root: Path,
        review: ResolvedReview,
    ): String?
}
