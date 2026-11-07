package dev.sluide.fileviewers.code

import dev.sluide.fileviewers.SyntaxHighlighter
import org.fxmisc.richtext.CodeArea
import org.fxmisc.richtext.model.StyleSpans

/**
 * The styles of a code view's [area], from the bottom up: the tokens of [highlighter] (none without one), the
 * [maskedRanges], the matches of [findBar] and the extensions' overlay [layers]. [highlight] styles a text at once,
 * [restyle] is what background highlighting ([BackgroundHighlighting]) adds to its token styles. FX thread.
 */
internal class CodeViewStyles(
    private val area: CodeArea,
    private val highlighter: SyntaxHighlighter?,
    private val findBar: FindBar,
    private val layers: OverlayLayers,
) {
    /**
     * The ranges of a text that are styled `masked` over the token styles, below the find bar's matches, e.g. the
     * values of `.env` files ([dev.sluide.fileviewers.env.EnvFileView]); applied with every highlighting, and by
     * [restyleMasks].
     */
    var maskedRanges: ((text: String) -> List<IntRange>)? = null

    /** Highlights [text], the area's text, at once, with everything that goes over the tokens. */
    fun highlight(text: String) {
        if (text.isNotEmpty() && highlighter != null) {
            area.setStyleSpans(0, restyle(text, codeStyleSpans(text, highlighter)))
        }
    }

    /** [spans], the token styles of [text], with the masks, the find bar's matches and the overlay layers added. */
    fun restyle(
        text: String,
        spans: StyleSpans<Collection<String>>,
    ): StyleSpans<Collection<String>> = layers.applyTo(findBar.overlay(withMasks(text, spans)))

    /** Styles the [maskedRanges] of the text anew, e.g. when what they reveal changed; restyles only what differs. */
    fun restyleMasks() {
        if (area.length == 0) return
        val styled = area.getStyleSpans(0, area.length)
        val unmasked = styled.mapStyles { style -> if (MASKED in style) style - MASKED else style }
        val wanted = withMasks(area.text, unmasked)
        val range = changedSpanRange(styled, wanted) ?: return
        area.setStyleSpans(range.first, wanted.subView(range.first, range.last + 1))
    }

    /** [spans], the styles of [text], with `masked` added on the [maskedRanges]. */
    private fun withMasks(
        text: String,
        spans: StyleSpans<Collection<String>>,
    ): StyleSpans<Collection<String>> = overlayRanges(spans, maskedRanges?.invoke(text).orEmpty()) { listOf(MASKED) }
}

/** The style class of [CodeViewStyles.maskedRanges] (`syntax.css`). */
private const val MASKED = "masked"
