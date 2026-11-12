package dev.sluide.codereview.list

import dev.sluide.codereview.ReviewChange
import dev.sluide.codereview.model.ExpandDirection
import dev.sluide.codereview.model.NoticeKind
import dev.sluide.codereview.model.ReviewRow

/** Lines a gap's up and down buttons reveal. */
internal const val EXPAND_STEP = 20

/** Digits a line number column has room for at least. */
private const val MIN_NUMBER_DIGITS = 5

/** The digits a number column makes room for: 5, or more for a number that needs them. */
internal fun numberColumnDigits(number: Int?): Int = maxOf(MIN_NUMBER_DIGITS, number?.toString()?.length ?: 0)

/** The sign column of a unified row: − for a removed line, + for an added one, blank for context. */
internal fun unifiedSign(row: ReviewRow.UnifiedLine): String =
    when {
        row.original != null && row.current == null -> "−"
        row.original == null && row.current != null -> "+"
        else -> ""
    }

/** What a notice says; for [NoticeKind.LOAD_DIFF] the label of its button. */
internal fun noticeText(
    kind: NoticeKind,
    detail: String?,
): String =
    when (kind) {
        NoticeKind.LOADING -> "Loading…"
        NoticeKind.BINARY -> "Binary file not shown"
        NoticeKind.TOO_LARGE -> "File too large to show"
        NoticeKind.SUBMODULE -> "Submodule ${detail.orEmpty()}"
        NoticeKind.NO_CONTENT_CHANGE -> detail.orEmpty()
        NoticeKind.UNREADABLE -> "Could not read this file"
        NoticeKind.LOAD_DIFF -> "Load diff (${detail.orEmpty()} lines)"
    }

/** A button of a gap row: its [label], its [tooltip] (`null` for the long labels) and what it reveals. */
internal data class GapButton(
    val label: String,
    val tooltip: String?,
    val direction: ExpandDirection,
)

/** The buttons of a gap of [hidden] lines: one for a short gap, else the step buttons that apply and Expand all. */
internal fun gapButtons(
    up: Boolean,
    down: Boolean,
    hidden: Int,
): List<GapButton> {
    if (hidden <= EXPAND_STEP) {
        val label = if (hidden == 1) "Expand 1 line" else "Expand $hidden lines"
        return listOf(GapButton(label, null, ExpandDirection.ALL))
    }
    return listOfNotNull(
        GapButton("↑ $EXPAND_STEP", "Show $EXPAND_STEP more lines above", ExpandDirection.UP).takeIf { up },
        GapButton("↓ $EXPAND_STEP", "Show $EXPAND_STEP more lines below", ExpandDirection.DOWN).takeIf { down },
        GapButton("Expand all ($hidden)", null, ExpandDirection.ALL),
    )
}

/** The style class colouring the path of a file header by its change. */
internal fun changeStyleClass(change: ReviewChange): String =
    when (change) {
        ReviewChange.ADDED, ReviewChange.COPIED -> "review-change-added"
        ReviewChange.DELETED -> "review-change-deleted"
        ReviewChange.MODIFIED, ReviewChange.RENAMED, ReviewChange.TYPE_CHANGED -> "review-change-modified"
    }
