package dev.sluide.codeintelligence.ui

import javafx.geometry.Orientation
import javafx.scene.Node
import javafx.scene.control.ScrollPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.scene.text.Text
import javafx.scene.text.TextFlow
import kotlin.math.min

/** The widest documentation in a popup, in pixels; longer lines wrap. */
internal const val DOC_MAX_WIDTH = 600.0

/**
 * Documentation blocks ([DocBlock]) as JavaFX text for a popup (ADR 0147): paragraphs and headings as wrapping text
 * flows, code in monospace on a light background, lists with bullets or numbers, rules as lines. Its preferred width is
 * that of its longest line, at most [widthCap]; its preferred height is that of the text wrapped at that width, so a
 * popup or scroll pane sized to it shows the wrapped text whole. Styled by `code-intelligence-popups.css` (looked-up
 * colours only). Nothing in it takes the focus. FX thread.
 */
internal class MarkupView(
    blocks: List<DocBlock>,
    private val widthCap: Double = DOC_MAX_WIDTH,
) : VBox() {
    init {
        styleClass += "markup"
        maxWidth = widthCap
        children.setAll(blocks.map(::blockNode))
    }

    override fun getContentBias(): Orientation = Orientation.HORIZONTAL

    override fun computePrefWidth(height: Double): Double = min(super.computePrefWidth(height), widthCap)

    // Without a width (a popup's or scroll pane's first question), the height of the text wrapped at the preferred width.
    override fun computePrefHeight(width: Double): Double =
        super.computePrefHeight(if (width < 0) prefWidth(-1.0) else width)
}

/** [blocks] in a vertical scroll pane at most [maxHeight] high, for a popup that must not grow past the screen. */
internal fun scrollingMarkup(
    blocks: List<DocBlock>,
    maxHeight: Double,
    widthCap: Double = DOC_MAX_WIDTH,
): ScrollPane =
    ScrollPane(MarkupView(blocks, widthCap)).apply {
        styleClass += "markup-scroll"
        isFitToWidth = true
        hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
        isFocusTraversable = false
        this.maxHeight = maxHeight
    }

private fun blockNode(block: DocBlock): Node =
    when (block) {
        is DocBlock.Paragraph -> {
            flow(block.spans, "markup-paragraph")
        }

        is DocBlock.Heading -> {
            flow(
                block.spans.map { it.copy(styles = it.styles + SpanStyle.STRONG) },
                "markup-heading",
            )
        }

        is DocBlock.Code -> {
            flow(listOf(DocSpan(block.text, setOf(SpanStyle.CODE))), "markup-code-block")
        }

        is DocBlock.Items -> {
            items(block)
        }

        is DocBlock.Quote -> {
            stack(block.blocks, "markup-quote")
        }

        DocBlock.Rule -> {
            Region().apply { styleClass += "markup-rule" }
        }
    }

private fun flow(
    spans: List<DocSpan>,
    styleClass: String,
): TextFlow = TextFlow(*spans.map(::text).toTypedArray()).apply { this.styleClass += styleClass }

private fun text(span: DocSpan): Text =
    Text(span.text).apply {
        styleClass += "markup-text"
        if (SpanStyle.STRONG in span.styles) styleClass += "markup-strong"
        if (SpanStyle.EMPHASIS in span.styles) styleClass += "markup-emphasis"
        if (SpanStyle.CODE in span.styles) styleClass += "markup-code"
    }

private fun items(list: DocBlock.Items): VBox =
    VBox().apply {
        styleClass += "markup-list"
        list.items.forEachIndexed { index, item ->
            val marker = if (list.ordered) "${list.start + index}." else "•"
            val content = stack(item, "markup-list-item").apply { HBox.setHgrow(this, Priority.ALWAYS) }
            val markerNode =
                TextFlow(text(DocSpan(marker))).apply {
                    styleClass += "markup-marker"
                    minWidth = Region.USE_PREF_SIZE
                }
            children += HBox(markerNode, content)
        }
    }

private fun stack(
    blocks: List<DocBlock>,
    styleClass: String,
): VBox =
    VBox().apply {
        this.styleClass += styleClass
        children.setAll(blocks.map(::blockNode))
    }
