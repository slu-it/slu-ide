package dev.sluide.codereview.model

import dev.sluide.codereview.ReviewFile
import dev.sluide.fileviewers.DiffKind
import dev.sluide.fileviewers.HighlightSpan

/** How the diff shows the two sides. */
internal enum class ReviewLayout { UNIFIED, SPLIT }

/** A side of a file's diff. */
internal enum class ReviewSide { ORIGINAL, CURRENT }

/** Which part of a gap to reveal: 20 lines at its bottom ([UP]), 20 at its top ([DOWN]), or all of it. */
internal enum class ExpandDirection { UP, DOWN, ALL }

/** What a [ReviewRow.Notice] says instead of lines. */
internal enum class NoticeKind { LOADING, BINARY, TOO_LARGE, SUBMODULE, NO_CONTENT_CHANGE, LOAD_DIFF }

/** A row of the review's diff list. Line numbers are 0-based within the file's whole original or current text. */
internal sealed interface ReviewRow {
    val path: String

    /** The header of a file; [added] and [removed] are 0 until its content is there. */
    data class FileHeader(
        override val path: String,
        val file: ReviewFile,
        val added: Int,
        val removed: Int,
        val collapsed: Boolean,
        val viewed: Boolean,
    ) : ReviewRow

    /**
     * Hidden unchanged lines: the [gap]-th unchanged run of the file (0 before the first change), [hidden] lines of it
     * not shown. [up] means more can be revealed from the bottom of the hidden part, [down] from its top. [hunkHeader]
     * is git's `@@ -a,b +c,d @@` of the shown section below, empty for the gap after the last change.
     */
    data class Gap(
        override val path: String,
        val gap: Int,
        val hidden: Int,
        val up: Boolean,
        val down: Boolean,
        val hunkHeader: String,
    ) : ReviewRow

    /** A line of the unified view: both lines set for context, one for a changed line. */
    data class UnifiedLine(
        override val path: String,
        val original: Int?,
        val current: Int?,
        val kind: DiffKind?,
    ) : ReviewRow

    /** A row of the split view: a `null` line is a filler that keeps the sides aligned. */
    data class SplitLine(
        override val path: String,
        val original: Int?,
        val current: Int?,
        val kind: DiffKind?,
    ) : ReviewRow

    /** A notice instead of the file's lines. */
    data class Notice(
        override val path: String,
        val kind: NoticeKind,
        val detail: String? = null,
    ) : ReviewRow

    /** The space after a file. */
    data class Spacer(
        override val path: String,
    ) : ReviewRow
}

/** A line to show: its [text], its syntax [tokens] and the column ranges of its changed words, all line-relative. */
internal data class ReviewLine(
    val text: String,
    val tokens: List<HighlightSpan>,
    val changedWords: List<IntRange>,
)

/** Gives the text of the lines the rows refer to. */
internal interface ReviewLineSource {
    fun line(
        path: String,
        side: ReviewSide,
        line: Int,
    ): ReviewLine
}

/**
 * A place in the review that survives filters, layout toggles and reloads: a line of a side of a file, or the file
 * itself ([side] and [line] `null`).
 */
internal data class ReviewAnchor(
    val path: String,
    val side: ReviewSide?,
    val line: Int?,
)
