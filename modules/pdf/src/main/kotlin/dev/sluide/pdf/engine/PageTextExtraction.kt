package dev.sluide.pdf.engine

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import java.io.StringWriter
import java.text.Normalizer

/**
 * A glyph's box in the frame of its text direction [dir] (0, 90, 180 or 270): x runs along the text, y downwards, as
 * PDFBox's `DirAdj` values give it; [unicode] is the text PDFBox gives the glyph.
 */
internal data class GlyphBox(
    val unicode: String,
    val dir: Int,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
)

/**
 * The box of each char of [text], which PDFBox wrote for [glyphs]. A glyph stands for as many chars as PDFBox's word
 * normalisation makes of it (a ligature such as U+FB01 becomes "fi"); each of them gets an equal share of its box
 * along the text. When the counts do not add up to [text] (PDFBox replaced a char by another count), char `i` gets
 * the whole box of the glyph at the same relative place. Without glyphs every char is `null`.
 */
internal fun charBoxes(
    text: String,
    glyphs: List<GlyphBox>,
): List<GlyphBox?> {
    if (glyphs.isEmpty()) return List(text.length) { null }
    val counts = glyphs.map { normalizedLength(it.unicode) }
    if (counts.sum() != text.length) return List(text.length) { glyphs[it * glyphs.size / text.length] }
    return glyphs.zip(counts).flatMap { (glyph, count) ->
        val share = glyph.width / count
        List(count) { glyph.copy(x = glyph.x + it * share, width = share) }
    }
}

/** How many chars PDFBox's `normalizeWord` makes of [unicode]: presentation forms become their NFKC form, trimmed. */
private fun normalizedLength(unicode: String): Int =
    unicode.sumOf { char ->
        if (char in '\uFB00'..'\uFDFF' || char in '\uFE70'..'\uFEFF') {
            Normalizer.normalize(char.toString(), Normalizer.Form.NFKC).trim().length
        } else {
            1
        }
    }

/**
 * Extracts the text of one page, sorted by position, the same way for the plain text and for the text with boxes, so
 * both give the same string. Glyphs entirely outside the crop box are skipped: they are not shown. Separators the
 * extraction writes itself (line ends, word gaps) get a `null` box.
 */
internal class PageTextExtraction(
    private val geometry: PageGeometry,
    private val withBoxes: Boolean,
) : PDFTextStripper() {
    private val written = StringWriter()
    private val boxes = ArrayList<PageRect?>()

    init {
        sortByPosition = true
        lineSeparator = "\n"
    }

    fun extract(
        document: PDDocument,
        page: Int,
    ): PageText {
        startPage = page + 1
        endPage = page + 1
        writeText(document, written)
        padToWritten()
        return PageText(written.toString(), boxes)
    }

    override fun processTextPosition(text: TextPosition) {
        if (!geometry.outside(geometry.unrotated(glyphBox(text)))) super.processTextPosition(text)
    }

    override fun writeString(
        text: String,
        textPositions: List<TextPosition>,
    ) {
        if (withBoxes) {
            padToWritten()
            charBoxes(text, textPositions.map(::glyphBox)).mapTo(boxes) { box ->
                box?.let { geometry.displayedRect(geometry.unrotated(it)) }
            }
        }
        super.writeString(text, textPositions)
    }

    private fun padToWritten() {
        while (boxes.size < written.buffer.length) boxes.add(null)
    }

    private fun glyphBox(position: TextPosition) =
        GlyphBox(
            position.unicode,
            position.dir.toInt(),
            position.xDirAdj.toDouble(),
            (position.yDirAdj - position.heightDir).toDouble(),
            position.widthDirAdj.toDouble(),
            position.heightDir.toDouble(),
        )
}
