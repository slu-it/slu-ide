package dev.sluide.pdf.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class CharBoxesTest {
    private fun glyph(
        unicode: String,
        x: Double,
        width: Double = 10.0,
    ) = GlyphBox(unicode, 0, x, 5.0, width, 8.0)

    @Test
    fun `each glyph gives its box to its char`() {
        val glyphs = listOf(glyph("a", 0.0), glyph("b", 10.0))
        assertEquals(glyphs, charBoxes("ab", glyphs))
    }

    @Test
    fun `a ligature glyph splits its box among the chars it stands for`() {
        val boxes = charBoxes("ffix", listOf(glyph("\uFB03", 0.0, width = 30.0), glyph("x", 30.0)))
        assertEquals(listOf(0.0, 10.0, 20.0, 30.0), boxes.map { it?.x })
        assertEquals(listOf(10.0, 10.0, 10.0, 10.0), boxes.map { it?.width })
    }

    @Test
    fun `a presentation form whose normal form PDFBox trims counts without the space`() {
        // U+FE70 (Arabic fathatan isolated form) is a space and U+064B in NFKC; PDFBox trims it to one char.
        val boxes = charBoxes("\u064Bfi", listOf(glyph("\uFE70", 0.0), glyph("\uFB01", 10.0, width = 20.0)))
        assertEquals(listOf(0.0, 10.0, 20.0), boxes.map { it?.x })
    }

    @Test
    fun `the first ligature of the presentation forms counts as two chars`() {
        val boxes = charBoxes("ff", listOf(glyph("\uFB00", 0.0)))
        assertEquals(listOf(0.0, 5.0), boxes.map { it?.x })
    }

    @Test
    fun `an Arabic presentation form counts as the chars of its normal form`() {
        // U+FEFB (lam with alef, isolated) is U+0644 U+0627 in NFKC.
        val boxes = charBoxes("\u0644\u0627", listOf(glyph("\uFEFB", 0.0)))
        assertEquals(listOf(0.0, 5.0), boxes.map { it?.x })
    }

    @Test
    fun `a char outside the presentation forms counts as one, whatever its normal form`() {
        // U+2026 (ellipsis) is "..." in NFKC, but PDFBox leaves it as it is.
        val boxes = charBoxes("\u2026fi", listOf(glyph("\u2026", 0.0), glyph("\uFB01", 10.0, width = 20.0)))
        assertEquals(listOf(0.0, 10.0, 20.0), boxes.map { it?.x })
    }

    @Test
    fun `when the counts do not add up each char gets the glyph at its relative place`() {
        val glyphs = listOf(glyph("a", 0.0), glyph("b", 10.0))
        assertEquals(listOf(glyphs[0], glyphs[0], glyphs[1], glyphs[1]), charBoxes("abcd", glyphs))
    }

    @Test
    fun `text without glyphs has no boxes`() {
        assertEquals(listOf(null, null), charBoxes("ab", emptyList()))
    }
}
