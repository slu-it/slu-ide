package dev.sluide.codereview.list

import dev.sluide.codereview.model.ExpandDirection
import dev.sluide.codereview.model.NoticeKind
import dev.sluide.codereview.model.ReviewLine
import dev.sluide.codereview.model.ReviewLineSource
import dev.sluide.codereview.model.ReviewRow
import dev.sluide.codereview.model.ReviewSide
import dev.sluide.fileicons.fileIconFor
import dev.sluide.fileicons.show
import dev.sluide.fileviewers.DiffKind
import javafx.geometry.Orientation
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.Label
import javafx.scene.control.Tooltip
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.text.Text
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignC

/** What the cells of a row call when the user acts on them. */
internal class RowActions(
    val expand: (path: String, gap: Int, direction: ExpandDirection) -> Unit,
    val viewed: (path: String, viewed: Boolean) -> Unit,
    val collapsed: (path: String, collapsed: Boolean) -> Unit,
    val loadDiff: (path: String) -> Unit,
)

/** The node showing [row]. */
internal fun createRowCell(
    row: ReviewRow,
    lines: ReviewLineSource,
    actions: RowActions,
): Node =
    when (row) {
        is ReviewRow.FileHeader -> createHeaderCell(row, actions)
        is ReviewRow.Gap -> createGapCell(row, actions)
        is ReviewRow.UnifiedLine -> createUnifiedCell(row, lines)
        is ReviewRow.SplitLine -> createSplitCell(row, lines)
        is ReviewRow.Notice -> createNoticeCell(row, actions)
        is ReviewRow.Spacer -> createSpacerCell()
    }

/** A row of controls: it asks for a small width and lets its text shorten, so that it never widens the list. */
private class NarrowRow : HBox() {
    override fun computePrefWidth(height: Double): Double = 120.0

    override fun computeMinWidth(height: Double): Double = 0.0
}

private fun flatButton(
    text: String,
    styleClass: String,
    action: () -> Unit,
) = Button(text).apply {
    this.styleClass += styleClass
    isFocusTraversable = false
    setOnAction { action() }
}

private fun grow(label: Label) = label.apply { HBox.setHgrow(this, Priority.ALWAYS) }.also { it.minWidth = 0.0 }

internal fun createHeaderCell(
    row: ReviewRow.FileHeader,
    actions: RowActions,
): Node {
    val chevron =
        Button().apply {
            styleClass += "review-chevron"
            isFocusTraversable = false
            graphic = FontIcon(if (row.collapsed) MaterialDesignC.CHEVRON_RIGHT else MaterialDesignC.CHEVRON_DOWN)
            val label = if (row.collapsed) "Expand the file" else "Collapse the file"
            tooltip = Tooltip(label)
            accessibleText = label
            setOnAction { actions.collapsed(row.path, !row.collapsed) }
        }
    val icon =
        FontIcon().apply {
            styleClass += "review-header-icon"
            show(fileIconFor(row.path.substringAfterLast('/'), directory = false, expanded = false) { true })
        }
    val name = row.file.oldPath?.let { "$it → ${row.path}" } ?: row.path
    val path =
        grow(Label(name)).apply {
            styleClass += listOf("review-header-path", changeStyleClass(row.file.change))
        }
    val viewed =
        CheckBox("Viewed").apply {
            styleClass += "review-viewed"
            isFocusTraversable = false
            isSelected = row.viewed
            setOnAction { actions.viewed(row.path, isSelected) }
            // Never shortened, so that it stays lined up when a narrow header shortens the path; a list narrower
            // than the header's fixed parts (about 200 px) cuts it off at the right instead.
            minWidth = Region.USE_PREF_SIZE
        }
    return NarrowRow().apply {
        styleClass += "review-file-header"
        alignment = Pos.CENTER_LEFT
        spacing = 6.0
        children += listOf(chevron, icon, path)
        if (row.added + row.removed > 0) {
            children +=
                listOf(
                    Label("+${row.added}").apply {
                        styleClass += "review-count-added"
                        minWidth = Region.USE_PREF_SIZE
                    },
                    Label("−${row.removed}").apply {
                        styleClass += "review-count-removed"
                        minWidth = Region.USE_PREF_SIZE
                    },
                )
        }
        // Viewed at the right end, so that it lines up on every header while scrolling.
        val spacer = Region().apply { minWidth = 0.0 }
        HBox.setHgrow(spacer, Priority.ALWAYS)
        children += listOf(spacer, viewed)
    }
}

internal fun createGapCell(
    row: ReviewRow.Gap,
    actions: RowActions,
): Node =
    NarrowRow().apply {
        styleClass += "review-gap"
        alignment = Pos.CENTER_LEFT
        spacing = 6.0
        children += grow(Label(row.hunkHeader)).apply { styleClass += "review-gap-header" }
        for (button in gapButtons(row.up, row.down, row.hidden)) {
            children +=
                flatButton(button.label, "review-gap-button") { actions.expand(row.path, row.gap, button.direction) }
                    .apply { button.tooltip?.let { tooltip = Tooltip(it) } }
        }
    }

internal fun createNoticeCell(
    row: ReviewRow.Notice,
    actions: RowActions,
): Node =
    NarrowRow().apply {
        styleClass += "review-notice"
        alignment = Pos.CENTER_LEFT
        val text = noticeText(row.kind, row.detail)
        children +=
            if (row.kind == NoticeKind.LOAD_DIFF) {
                flatButton(text, "review-load-diff") { actions.loadDiff(row.path) }
            } else {
                grow(Label(text))
            }
    }

internal fun createSpacerCell(): Node =
    object : Region() {
        override fun computePrefHeight(width: Double) = SPACER_HEIGHT

        override fun computePrefWidth(height: Double) = 1.0
    }.apply { styleClass += "review-spacer" }

private const val SPACER_HEIGHT = 28.0

/** A gutter column: the line number or sign of one side, right-aligned in the width of [digits] digits. */
internal class GutterLabel(
    text: String,
    private val digits: Int,
) : Label(text) {
    init {
        styleClass += "lineno"
        alignment = Pos.TOP_RIGHT
    }

    private var cachedWidth = -1.0

    init {
        fontProperty().addListener { _ -> cachedWidth = -1.0 }
    }

    override fun computePrefWidth(height: Double): Double {
        if (cachedWidth >= 0) return cachedWidth
        // The padding and the font come from the stylesheet, which a node outside a scene has not had applied.
        val styled = scene != null
        if (styled) applyCss()
        val digitWidth = Text("0").apply { font = this@GutterLabel.font }.layoutBounds.width
        val result = digitWidth * digits + snappedLeftInset() + snappedRightInset()
        if (styled) cachedWidth = result
        return result
    }

    override fun computeMinWidth(height: Double): Double = computePrefWidth(height)

    override fun computeMaxWidth(height: Double): Double = computePrefWidth(height)
}

/**
 * The columns of a line row side by side: gutter columns at their own width, [LineText]s sharing the rest equally.
 * The row is as high as its highest column, so that the backgrounds fill it.
 */
internal class LineRowPane(
    columns: List<Node>,
) : Region() {
    init {
        styleClass += "review-line"
        children += columns
    }

    private val texts get() = children.count { it is LineText }

    private fun fixedWidth() = children.filter { it !is LineText }.sumOf { it.prefWidth(-1.0) }

    override fun getContentBias(): Orientation = Orientation.HORIZONTAL

    override fun computePrefWidth(height: Double): Double =
        fixedWidth() + children.filterIsInstance<LineText>().sumOf { it.prefWidth(-1.0) }

    override fun computeMinWidth(height: Double): Double = computePrefWidth(height)

    private fun textWidth(width: Double): Double = if (texts == 0) 0.0 else maxOf(1.0, (width - fixedWidth()) / texts)

    override fun computePrefHeight(width: Double): Double {
        val each = if (width < 0) -1.0 else textWidth(width)
        return children.maxOfOrNull { if (it is LineText) it.prefHeight(each) else it.prefHeight(-1.0) } ?: 0.0
    }

    override fun layoutChildren() {
        val each = textWidth(width)
        val rowHeight = height
        var x = 0.0
        for (child in children) {
            val w = if (child is LineText) each else child.prefWidth(-1.0)
            child.resizeRelocate(x, 0.0, w, rowHeight)
            x += w
        }
    }
}

private fun sideColumns(
    number: Int?,
    line: ReviewLine?,
    kind: DiffKind?,
): List<Node> {
    val classes = listOfNotNull(kind?.styleClass, "review-filler".takeIf { line == null })
    val numberLabel = GutterLabel(number?.plus(1)?.toString().orEmpty(), numberColumnDigits(number?.plus(1)))
    val text = LineText(line?.text.orEmpty(), line?.tokens.orEmpty())
    line?.let { text.setRanges(CHANGED_WORDS_LAYER, it.changedWords) }
    for (node in listOf(numberLabel, text)) node.styleClass += classes
    return listOf(numberLabel, text)
}

/** The layer of [LineText] that marks changed words. */
internal const val CHANGED_WORDS_LAYER = "review-changed-words"

internal fun createSplitCell(
    row: ReviewRow.SplitLine,
    lines: ReviewLineSource,
): Node {
    val left = row.original?.let { lines.line(row.path, ReviewSide.ORIGINAL, it) }
    val right = row.current?.let { lines.line(row.path, ReviewSide.CURRENT, it) }
    return LineRowPane(sideColumns(row.original, left, row.kind) + sideColumns(row.current, right, row.kind))
}

internal fun createUnifiedCell(
    row: ReviewRow.UnifiedLine,
    lines: ReviewLineSource,
): Node {
    val line =
        when {
            row.current != null -> lines.line(row.path, ReviewSide.CURRENT, row.current)
            row.original != null -> lines.line(row.path, ReviewSide.ORIGINAL, row.original)
            else -> null
        }
    val kind = row.kind?.styleClass
    val original =
        GutterLabel(
            row.original
                ?.plus(1)
                ?.toString()
                .orEmpty(),
            numberColumnDigits(row.original?.plus(1)),
        )
    val current =
        GutterLabel(
            row.current
                ?.plus(1)
                ?.toString()
                .orEmpty(),
            numberColumnDigits(row.current?.plus(1)),
        )
    val sign = GutterLabel(unifiedSign(row), 1).apply { styleClass += "review-sign" }
    val text = LineText(line?.text.orEmpty(), line?.tokens.orEmpty())
    line?.let { text.setRanges(CHANGED_WORDS_LAYER, it.changedWords) }
    val columns = listOf(original, current, sign, text)
    if (kind != null) for (node in columns) node.styleClass += kind
    return LineRowPane(columns)
}
