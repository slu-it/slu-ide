package dev.sluide.ide

import dev.sluide.codereview.ResolvedReview
import dev.sluide.codereview.ReviewBase
import dev.sluide.codereview.ReviewCancellation
import dev.sluide.codereview.ReviewFile
import dev.sluide.codereview.ReviewFileContent
import dev.sluide.codereview.ReviewRevision
import dev.sluide.codereview.ReviewScope
import dev.sluide.codereview.ReviewSource
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** A review source with no changes that records the scopes it resolved and how often it was asked for a fingerprint. */
internal class RecordingReviewSource : ReviewSource {
    /** The roots and scopes resolved, in order. */
    val resolved = CopyOnWriteArrayList<Pair<Path, ReviewScope>>()
    val fingerprints = AtomicInteger()

    override fun bases(root: Path): List<ReviewBase> = emptyList()

    override fun resolve(
        root: Path,
        scope: ReviewScope,
        cancellation: ReviewCancellation,
    ): ResolvedReview {
        resolved += root to scope
        return ResolvedReview("Review", null, null, "a".repeat(40), emptyList(), false)
    }

    override fun changedFiles(
        root: Path,
        from: String?,
        to: ReviewRevision,
        cancellation: ReviewCancellation,
    ): List<ReviewFile> = emptyList()

    override fun fileDiffs(
        root: Path,
        from: String?,
        to: ReviewRevision,
        files: List<ReviewFile>,
        ignoreWhitespace: Boolean,
        cancellation: ReviewCancellation,
    ): Map<String, ReviewFileContent> = emptyMap()

    override fun fingerprint(
        root: Path,
        review: ResolvedReview,
    ): String {
        fingerprints.incrementAndGet()
        return "same"
    }
}
