package dev.sluide.fileviewers

import kotlin.test.Test
import kotlin.test.assertEquals

class WordDiffTest {
    private val none = WordChanges(emptyMap(), emptyMap())

    @Test
    fun `only the changed words of a modified line are marked`() {
        assertEquals(
            WordChanges(mapOf(0 to listOf(4..8, 25..25)), mapOf(0 to listOf(4..8, 25..25))),
            wordChanges(
                listOf("val count = items.size + 1"),
                listOf("val total = items.size + 2"),
                listOf(LineChange(0..0, 0..0)),
            ),
        )
    }

    @Test
    fun `a line inserted into a block is marked whole and the lines after it are compared with their own`() {
        assertEquals(
            WordChanges(
                mapOf(0 to listOf(4..4), 1 to listOf(4..4)),
                mapOf(0 to listOf(4..5), 1 to listOf(0..7), 2 to listOf(4..5)),
            ),
            wordChanges(
                listOf("a = 1", "b = 2"),
                listOf("a = 10", "new line", "b = 20"),
                listOf(LineChange(0..1, 0..2)),
            ),
        )
    }

    @Test
    fun `the end of a changed line is matched with its own line, not with a line added after it`() {
        assertEquals(
            WordChanges(mapOf(0 to listOf(7..11)), mapOf(0 to listOf(7..12), 1 to listOf(0..11))),
            wordChanges(
                listOf("print(\"world\")"),
                listOf("print(\"SluIde\")", "log(\"added\")"),
                listOf(LineChange(0..0, 0..1)),
            ),
        )
    }

    @Test
    fun `unchanged lines of a modified block have no changed words`() {
        assertEquals(
            WordChanges(mapOf(0 to listOf(0..0), 2 to listOf(0..0)), mapOf(0 to listOf(0..1), 2 to listOf(0..1))),
            wordChanges(listOf("x", "same", "y"), listOf("x2", "same", "y2"), listOf(LineChange(0..2, 0..2))),
        )
    }

    @Test
    fun `added and removed lines have no changed words`() {
        assertEquals(
            none,
            wordChanges(
                listOf("a", "b"),
                listOf("x", "a"),
                listOf(LineChange(0 until 0, 0..0), LineChange(1..1, 2 until 2)),
            ),
        )
    }

    @Test
    fun `changed whitespace is marked`() {
        assertEquals(
            WordChanges(mapOf(0 to listOf(1..1)), mapOf(0 to listOf(1..2))),
            wordChanges(listOf("a b"), listOf("a  b"), listOf(LineChange(0..0, 0..0))),
        )
    }

    @Test
    fun `adjacent changed words are one range`() {
        assertEquals(
            WordChanges(mapOf(0 to listOf(0..7)), mapOf(0 to listOf(0..7))),
            wordChanges(listOf("foo(bar)"), listOf("baz[qux]"), listOf(LineChange(0..0, 0..0))),
        )
    }

    @Test
    fun `ranges beyond the lines are cut off`() {
        assertEquals(
            WordChanges(mapOf(0 to listOf(0..0)), mapOf(0 to listOf(0..0))),
            wordChanges(listOf("a"), listOf("b"), listOf(LineChange(0..5, 0..5))),
        )
    }

    @Test
    fun `blocks too large to compare are left out`() {
        assertEquals(
            none,
            wordChanges(listOf("a b c"), listOf("x y z"), listOf(LineChange(0..0, 0..0)), maxCells = 4),
        )
    }

    @Test
    fun `a common start does not count toward the limit`() {
        assertEquals(
            WordChanges(mapOf(0 to listOf(10..10)), mapOf(0 to listOf(10..10))),
            wordChanges(listOf("same same a"), listOf("same same b"), listOf(LineChange(0..0, 0..0)), maxCells = 1),
        )
    }
}
