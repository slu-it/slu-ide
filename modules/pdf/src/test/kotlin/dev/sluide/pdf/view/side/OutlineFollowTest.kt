package dev.sluide.pdf.view.side

import dev.sluide.pdf.engine.OutlineItem
import dev.sluide.pdf.engine.PageTarget
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class OutlineFollowTest {
    private fun item(
        title: String,
        page: Int?,
        vararg children: OutlineItem,
        open: Boolean = true,
    ) = OutlineItem(title, page?.let { PageTarget(it) }, open, children.toList())

    /** Pages 1 to 7 (0-based 1..7): nested items, two without a target, ties on pages 1 and 4. */
    private val outline =
        listOf(
            item(
                "A",
                1,
                item("A.1", 1),
                item("A.2", 2, item("A.2.a", 3)),
            ),
            item("B", null, item("B.1", 4)),
            item("C", 4),
            item("D", 6, item("D.1", 7), open = false),
            item("E", null),
        )

    private val follow = OutlineFollow(outline)

    private fun titleFor(
        page: Int,
        collapsed: Set<String> = emptySet(),
    ) = follow.itemFor(page) { it.title !in collapsed }?.title

    @Test
    fun `the entries are the items in document order, each with its parent`() {
        assertEquals(
            listOf(
                "A" to null,
                "A.1" to "A",
                "A.2" to "A",
                "A.2.a" to "A.2",
                "B" to null,
                "B.1" to "B",
                "C" to null,
                "D" to null,
                "D.1" to "D",
                "E" to null,
            ),
            follow.entries.map { it.item.title to it.parent?.item?.title },
        )
    }

    @ParameterizedTest
    @CsvSource(
        // A child on its parent's page wins: it comes later in document order.
        "1, A.1",
        "2, A.2",
        "3, A.2.a",
        // B.1 and C share page 4: the last in document order wins; B has no target and never counts.
        "4, C",
        "5, C",
        "7, D.1",
        "100, D.1",
    )
    fun `the item of a page is the one with the latest target page at or before it, the last of a tie`(
        page: Int,
        title: String,
    ) {
        assertEquals(title, titleFor(page))
    }

    @Test
    fun `an item later in the document with an earlier target loses to one with a later target`() {
        val back = OutlineFollow(listOf(item("Late", 5), item("Back", 2)))
        assertEquals("Late", back.itemFor(6) { true }?.title)
        assertEquals("Back", back.itemFor(4) { true }?.title)
    }

    @Test
    fun `a page before the first target gives no item, also after an item without a target or without items`() {
        assertNull(titleFor(0))
        val untargetedFirst = OutlineFollow(listOf(item("None", null), item("One", 1)))
        assertNull(untargetedFirst.itemFor(0) { true })
        assertEquals("One", untargetedFirst.itemFor(1) { true }?.title)
        assertNull(OutlineFollow(emptyList()).itemFor(3) { true })
    }

    @ParameterizedTest
    @CsvSource(
        "3, 'A.2', A.2",
        "3, 'A', A",
        "3, 'A;A.2', A",
        "7, 'D', D",
        // The collapsed item itself shows: it is selected, its parent is expanded.
        "2, 'A.2', A.2",
    )
    fun `an item hidden under a collapsed parent gives its nearest shown ancestor`(
        page: Int,
        collapsed: String,
        title: String,
    ) {
        assertEquals(title, titleFor(page, collapsed.split(";").toSet()))
    }

    @Test
    fun `items are told apart by identity, so the second of two equal ones wins its tie`() {
        val first = item("Same", 1)
        val second = item("Same", 1)
        assertSame(second, OutlineFollow(listOf(first, second)).itemFor(1) { true })
    }

    @Test
    fun `a title shows on one line`() {
        assertEquals("Part one: the start", outlineTitle("Part one:\r\n  the start"))
        assertEquals("A B C", outlineTitle("A\nB \u2028 C"))
        assertEquals("Tab\tkept", outlineTitle("Tab\tkept"))
        assertEquals("One two", outlineTitle("One\r\n \r\ntwo"))
    }
}
