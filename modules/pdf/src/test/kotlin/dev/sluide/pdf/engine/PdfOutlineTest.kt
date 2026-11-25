package dev.sluide.pdf.engine

import dev.sluide.pdf.HostilePdfs
import dev.sluide.pdf.TestPdfs
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class PdfOutlineTest {
    private fun outline(file: Path): List<OutlineItem> =
        (PdfDocument.open(file) as PdfOpenResult.Opened).document.use { it.outline() }

    private fun item(
        title: String,
        target: PageTarget?,
        open: Boolean = false,
        children: List<OutlineItem> = emptyList(),
    ) = OutlineItem(title, target, open, children)

    @Test
    fun `the outline resolves explicit, named and GoTo destinations and keeps the tree and its open items`() {
        val pageHeight = 792.0
        val expected =
            listOf(
                item(
                    "1 Explicit",
                    PageTarget(0),
                    open = true,
                    children =
                        listOf(
                            item(
                                "1.1 XYZ",
                                PageTarget(1, pageHeight - TestPdfs.XYZ_TOP),
                                children = listOf(item("1.1.1 GoTo", PageTarget(3))),
                            ),
                        ),
                ),
                item("2 Named in tree", PageTarget(2, pageHeight - 500)),
                item("3 Named in Dests", PageTarget(4)),
                item("4 GoTo named", PageTarget(2, pageHeight - 500)),
                item("5 Page number", PageTarget(5)),
                item("6 Missing", null),
                item("7 FitH", PageTarget(1, pageHeight - TestPdfs.FIT_WIDTH_TOP)),
                item("8 Page number out of range", null),
                item("9 XYZ above the page", PageTarget(0, 0.0)),
                item("10 Bad destination", null),
                item("", PageTarget(0)),
            )
        assertEquals(expected, outline(TestPdfs.outline))
    }

    @Test
    fun `on a rotated page the top of a destination is measured on the page as displayed`() {
        // Turned clockwise, the left of the unrotated page becomes its top.
        val expected =
            listOf(
                item("XYZ", PageTarget(0, TestPdfs.ROTATED_LEFT.toDouble())),
                item("XYZ null", PageTarget(0, null)),
                item("XYZ with a name as top", PageTarget(0, null)),
                item("FitV on 90", PageTarget(0, TestPdfs.ROTATED_LEFT.toDouble())),
                // Turned counter-clockwise, the left of the unrotated page (612 wide) becomes its bottom; a top alone
                // says nothing about the height on the page as displayed.
                item("FitH on 270", PageTarget(1, null)),
                item("XYZ on 270", PageTarget(1, 612.0 - TestPdfs.ROTATED_LEFT)),
                item("FitH on 180", PageTarget(2, TestPdfs.ROTATED_TOP.toDouble())),
            )
        assertEquals(expected, outline(TestPdfs.rotatedOutline))
    }

    @Test
    fun `a destination whose page is in no page tree has no target, even when its parents loop`() {
        val items =
            assertTimeoutPreemptively<List<OutlineItem>>(Duration.ofSeconds(20)) { outline(HostilePdfs.parentLoop) }
        assertEquals(listOf(item("loop", null)), items)
    }

    @Test
    fun `a name tree that contains itself ends the search for a name`() {
        val items =
            assertTimeoutPreemptively<List<OutlineItem>>(Duration.ofSeconds(20)) { outline(HostilePdfs.nameTreeLoop) }
        assertEquals(listOf(item("x", null)), items)
    }

    @Test
    fun `a name is found down to the depth limit of the name tree, not below`() {
        val limit = DocumentNavigation.MAX_NAME_TREE_DEPTH
        assertEquals(listOf(item("x", PageTarget(0))), outline(HostilePdfs.deepNameTree(limit)))
        assertEquals(listOf(item("x", null)), outline(HostilePdfs.deepNameTree(limit + 1)))
    }

    @Test
    fun `a broken name tree gives what it holds and nothing for the rest`() {
        val expected = HostilePdfs.ODD_NAMES.map { item(it, if (it == "a") PageTarget(0) else null) }
        assertEquals(expected, outline(HostilePdfs.oddNameTree))
    }

    @Test
    fun `a Names entry that is no dictionary leaves the Dests dictionary`() {
        assertEquals(listOf(item("a", PageTarget(0))), outline(HostilePdfs.namesNotDictionary))
    }

    @Test
    fun `a name tree of destinations that is no dictionary finds no name`() {
        assertEquals(listOf(item("a", null)), outline(HostilePdfs.destsTreeNotDictionary))
    }

    @Test
    fun `a name tree value may hold its destination under D`() {
        assertEquals(listOf(item("x", PageTarget(0))), outline(HostilePdfs.nameTreeDictionaryValue))
    }

    @Test
    fun `a PDF without an outline has an empty one`() {
        assertEquals(emptyList(), outline(TestPdfs.text))
    }

    @Test
    fun `a deeply nested outline is cut at the depth limit`() {
        var depth = 0
        var level = outline(TestPdfs.deepOutline)
        while (level.isNotEmpty()) {
            depth++
            level = level.single().children
        }
        assertEquals(DocumentNavigation.MAX_OUTLINE_DEPTH, depth)
    }

    @Test
    fun `an outline item that leads back to an ancestor ends the walk there`() {
        val expected =
            listOf(
                item("Parent", PageTarget(0), children = listOf(item("Child", PageTarget(0)))),
                item("Sibling", PageTarget(0)),
            )
        assertEquals(expected, outline(TestPdfs.loopOutline))
    }

    @Test
    fun `an outline with too many items is cut at the item limit`() {
        val items = outline(TestPdfs.wideOutline)
        assertEquals(DocumentNavigation.MAX_OUTLINE_ITEMS, items.size)
        assertEquals("Item ${DocumentNavigation.MAX_OUTLINE_ITEMS - 1}", items.last().title)
    }
}
