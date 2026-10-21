package dev.sluide.previews.markdown

import dev.sluide.previews.preview.Heading
import dev.sluide.previews.preview.HeadingOutline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MarkdownOutlineTest {
    private fun headings(vararg lines: String) = MarkdownOutline.headings(lines.joinToString("\n"))

    @Test
    fun `reads ATX and setext headings with level and line`() {
        val result = headings("# One", "", "Two", "===", "", "Three", "---", "", "### Four")
        assertEquals(
            listOf(Heading(1, "One", 0), Heading(1, "Two", 2), Heading(2, "Three", 5), Heading(3, "Four", 8)),
            result,
        )
    }

    @Test
    fun `text is the plain text of the inline content`() {
        assertEquals("The code part", headings("# The `code` *part*").single().text)
    }

    @Test
    fun `headings in fenced code and math blocks are ignored`() {
        val result = headings("```", "# not", "```", "", "$$", "# nor", "$$", "", "# yes")
        assertEquals(listOf(Heading(1, "yes", 8)), result)
    }

    @Test
    fun `finds headings inside lists and quotes`() {
        val result = headings("- # In list", "", "> ## In quote")
        assertEquals(listOf(Heading(1, "In list", 0), Heading(2, "In quote", 2)), result)
    }

    @Test
    fun `depth is the rank among the levels in use`() {
        assertEquals(listOf(0, 1, 0), HeadingOutline.depths(headings("## a", "#### b", "## c")))
        assertEquals(listOf(0, 1, 2), HeadingOutline.depths(headings("# a", "## b", "### c")))
    }

    @Test
    fun `section at a line`() {
        val result = headings("text", "# a", "x", "## b", "y")
        assertNull(HeadingOutline.sectionAt(result, 0))
        assertEquals(0, HeadingOutline.sectionAt(result, 1))
        assertEquals(0, HeadingOutline.sectionAt(result, 2))
        assertEquals(1, HeadingOutline.sectionAt(result, 3))
        assertEquals(1, HeadingOutline.sectionAt(result, 99))
    }

    @Test
    fun `an empty document has no headings and no section`() {
        assertEquals(emptyList(), MarkdownOutline.headings(""))
        assertNull(HeadingOutline.sectionAt(emptyList(), 0))
        assertEquals(emptyList(), HeadingOutline.depths(emptyList()))
    }

    @Test
    fun `inline math in a heading keeps its TeX`() {
        assertEquals("Energy E=mc^2 now", headings("# Energy ${'$'}E=mc^2${'$'} now").single().text)
    }
}
