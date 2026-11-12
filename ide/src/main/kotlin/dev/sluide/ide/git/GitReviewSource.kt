package dev.sluide.ide.git

import dev.sluide.codereview.ResolvedReview
import dev.sluide.codereview.ReviewBase
import dev.sluide.codereview.ReviewCancellation
import dev.sluide.codereview.ReviewChange
import dev.sluide.codereview.ReviewCommit
import dev.sluide.codereview.ReviewException
import dev.sluide.codereview.ReviewFile
import dev.sluide.codereview.ReviewFileContent
import dev.sluide.codereview.ReviewRevision
import dev.sluide.codereview.ReviewScope
import dev.sluide.codereview.ReviewSource
import dev.sluide.fileviewers.LineChange
import dev.sluide.git.GitCancellation
import dev.sluide.git.GitChange
import dev.sluide.git.GitChangedFile
import dev.sluide.git.GitCommit
import dev.sluide.git.GitFileDiff
import dev.sluide.git.GitPulls
import dev.sluide.git.GitReviewSide
import dev.sluide.git.GitReviews
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.util.concurrent.CancellationException

private const val SHORT_HASH = 7
private const val HEADS = "refs/heads/"
private const val REMOTES = "refs/remotes/"

/** What the review window shows of a branch: its refname without `refs/heads/` or `refs/remotes/`. */
private fun shortName(ref: String) = ref.removePrefix(HEADS).removePrefix(REMOTES)

/** The review window's [ReviewSource] over [GitReviews] (ADR 0168); maps git's types and cancellation. */
@Component
class GitReviewSource(
    private val reviews: GitReviews,
    private val pulls: GitPulls,
) : ReviewSource {
    /** The default bases of [head] first (the order of [GitReviews.defaultBases]), then the other branches. */
    override fun bases(
        root: Path,
        head: String,
    ): List<ReviewBase> {
        val defaults = reviews.defaultBases(root, head)
        val others = reviews.branches(root).filterNot { it in defaults }
        return defaults.map { ReviewBase(it, shortName(it), true) } +
            others.map { ReviewBase(it, shortName(it), false) }
    }

    override fun resolve(
        root: Path,
        scope: ReviewScope,
        cancellation: ReviewCancellation,
    ): ResolvedReview =
        bridged(cancellation) { git ->
            when (scope) {
                is ReviewScope.Branch -> resolveBranch(root, scope, git)
                is ReviewScope.Commits -> resolveCommits(root, scope, git)
            }
        }

    private fun resolveBranch(
        root: Path,
        scope: ReviewScope.Branch,
        git: GitCancellation,
    ): ResolvedReview {
        val head = scope.head
        val current = pulls.currentBranch(root)
        // Only the checked-out branch can be unborn; any other head that does not resolve is gone.
        val headHash =
            reviews.resolveCommit(root, head)
                ?: throw failure(
                    git,
                    if (isCheckedOut(head, current)) {
                        "The branch has no commits yet."
                    } else {
                        "The branch ${shortName(head)} no longer exists."
                    },
                )
        val headLabel = if (head == "HEAD") current ?: headHash.take(SHORT_HASH) else shortName(head)
        val defaults = reviews.defaultBases(root, head)
        val baseRef = scope.base ?: defaults.firstOrNull()
        val base = baseRef?.let { ReviewBase(it, shortName(it), it in defaults) }
        if (base == null) return ResolvedReview(headLabel, null, null, headHash, emptyList(), false, head)
        if (reviews.resolveCommit(root, base.ref) == null) {
            throw failure(git, "The base ${base.label} no longer exists.")
        }
        val mergeBase =
            reviews.mergeBase(root, base.ref, headHash, git)
                ?: throw failure(git, "$headLabel and ${base.label} have no common history.")
        val commits =
            reviews.commits(root, mergeBase, headHash, cancellation = git)
                ?: throw failure(git, "Git could not list the commits.")
        val uncommitted = isCheckedOut(head, current) && reviews.hasUncommittedChanges(root) == true
        return ResolvedReview(
            title = "$headLabel into ${base.label}",
            base = base,
            from = mergeBase,
            head = headHash,
            commits = commits.map(::reviewCommit),
            uncommitted = uncommitted,
            headRef = head,
        )
    }

    private fun resolveCommits(
        root: Path,
        scope: ReviewScope.Commits,
        git: GitCancellation,
    ): ResolvedReview {
        val onOneLine =
            reviews.isAncestor(root, scope.oldest, scope.newest)
                ?: throw failure(git, "Git could not compare the selected commits.")
        if (!onOneLine) throw failure(git, "The selected commits are not on one line of history.")
        val oldest =
            reviews.commits(root, null, scope.oldest, limit = 1, cancellation = git)?.firstOrNull()
                ?: throw failure(git, "Git could not read the oldest commit.")
        val from = oldest.parents.firstOrNull()
        val commits =
            reviews.commits(root, from, scope.newest, cancellation = git)
                ?: throw failure(git, "Git could not list the commits.")
        val short = scope.oldest.take(SHORT_HASH)
        val title =
            if (scope.oldest == scope.newest) "$short ${oldest.subject}" else "$short..${scope.newest.take(SHORT_HASH)}"
        return ResolvedReview(title, null, from, scope.newest, commits.map(::reviewCommit), false)
    }

    override fun changedFiles(
        root: Path,
        from: String?,
        to: ReviewRevision,
        cancellation: ReviewCancellation,
    ): List<ReviewFile> =
        bridged(cancellation) { git ->
            val files =
                reviews.changedFiles(root, from, side(to), git) ?: throw failure(git, "Git could not list the files.")
            files.map { ReviewFile(ReviewChange.valueOf(it.change.name), it.path, it.oldPath) }
        }

    override fun fileDiffs(
        root: Path,
        from: String?,
        to: ReviewRevision,
        files: List<ReviewFile>,
        ignoreWhitespace: Boolean,
        cancellation: ReviewCancellation,
    ): Map<String, ReviewFileContent> =
        bridged(cancellation) { git ->
            val gitFiles = files.map { GitChangedFile(GitChange.valueOf(it.change.name), it.path, it.oldPath) }
            val diffs =
                reviews.fileDiffs(root, from, side(to), gitFiles, ignoreWhitespace, git)
                    ?: throw failure(git, "Git could not read the diffs.")
            diffs.mapValues { (_, diff) -> content(diff) }
        }

    override fun fingerprint(
        root: Path,
        review: ResolvedReview,
    ): String? {
        val revisions = listOfNotNull(review.base?.ref, review.headRef, review.head, review.from)
        val workingTree = review.headRef?.let { isCheckedOut(it, pulls.currentBranch(root)) } == true
        return reviews.fingerprint(root, revisions, workingTree)
    }

    /** Whether [head] (a full refname or `HEAD`) is what is checked out, given the [current] branch's short name. */
    private fun isCheckedOut(
        head: String,
        current: String?,
    ) = head == "HEAD" || (current != null && head == HEADS + current)

    private fun <T> bridged(
        cancellation: ReviewCancellation,
        block: (GitCancellation) -> T,
    ): T {
        val git = GitCancellation()
        cancellation.onCancel { git.cancel() }
        return block(git)
    }

    /** A cancelled load stops quietly; otherwise git's `null` becomes a message for the user. */
    private fun failure(
        git: GitCancellation,
        reason: String,
    ): Exception = if (git.cancelled) CancellationException() else ReviewException(reason)

    private fun side(revision: ReviewRevision) =
        when (revision) {
            is ReviewRevision.Commit -> GitReviewSide.Commit(revision.hash)
            ReviewRevision.WorkingTree -> GitReviewSide.WorkingTree
        }

    private fun reviewCommit(commit: GitCommit) =
        ReviewCommit(
            hash = commit.hash,
            shortHash = commit.hash.take(SHORT_HASH),
            subject = commit.subject,
            author = commit.author,
            time = commit.authorTime,
            firstParent = commit.parents.firstOrNull(),
        )

    private fun content(diff: GitFileDiff): ReviewFileContent =
        when (diff) {
            is GitFileDiff.Text -> {
                val changes = diff.hunks.map { LineChange(it.original, it.current) }
                ReviewFileContent.Text(diff.original, diff.current, changes)
            }

            GitFileDiff.Binary -> {
                ReviewFileContent.Binary
            }

            GitFileDiff.TooLarge -> {
                ReviewFileContent.TooLarge
            }

            is GitFileDiff.Submodule -> {
                ReviewFileContent.Submodule(diff.old, diff.new)
            }
        }
}
