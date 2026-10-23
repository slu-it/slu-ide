package dev.sluide.fileviewers.ignore

import dev.sluide.fileviewers.IgnoreFileEffects
import dev.sluide.fileviewers.IgnoreLineEffect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IgnoreRuleDotsTest {
    private fun effect(
        ignored: Int = 0,
        unignored: Int = 0,
        tracked: Int = 0,
        examples: List<String> = emptyList(),
        trackedExamples: List<String> = emptyList(),
    ) = IgnoreLineEffect(ignored, unignored, tracked, examples, trackedExamples)

    private val text = "# c\nbuild/\n\n*.log\n!keep.log\nold\n"
    private val effects =
        IgnoreFileEffects(
            mapOf(
                1 to effect(ignored = 1, examples = listOf("build/")),
                3 to effect(tracked = 2, trackedExamples = listOf("a.txt", "b.txt")),
                4 to effect(unignored = 1, examples = listOf("keep.log")),
            ),
            complete = true,
        )

    @Test
    fun `comments and blank lines are no patterns`() {
        assertFalse(IgnoreRuleDots.isPattern("# c"))
        assertTrue(IgnoreRuleDots.isPattern("   # c"))
        assertFalse(IgnoreRuleDots.isPattern(""))
        assertFalse(IgnoreRuleDots.isPattern("  \t"))
        assertTrue(IgnoreRuleDots.isPattern("build/"))
        assertTrue(IgnoreRuleDots.isPattern("!keep"))
    }

    @Test
    fun `effects map to dot kinds`() {
        val dots = IgnoreRuleDots.of(text, effects)
        assertEquals(
            mapOf(1 to DotKind.ACTIVE, 3 to DotKind.TRACKED, 4 to DotKind.ACTIVE, 5 to DotKind.NONE),
            dots.mapValues { it.value.kind },
        )
    }

    @Test
    fun `tooltips use singular and plural`() {
        assertEquals(
            "Ignores 1 path: a",
            IgnoreRuleDots.tooltip(DotKind.ACTIVE, effect(ignored = 1, examples = listOf("a")), true),
        )
        assertEquals(
            "Ignores 2 paths: a, b",
            IgnoreRuleDots.tooltip(DotKind.ACTIVE, effect(ignored = 2, examples = listOf("a", "b")), true),
        )
        assertEquals(
            "Un-ignores 1 path: keep.log",
            IgnoreRuleDots.tooltip(DotKind.ACTIVE, effect(unignored = 1, examples = listOf("keep.log")), true),
        )
    }

    @Test
    fun `tooltips name how many more paths there are`() {
        val examples = listOf("build/", "a.log")
        assertEquals(
            "Ignores 14 paths: build/, a.log, … and 12 more",
            IgnoreRuleDots.tooltip(DotKind.ACTIVE, effect(ignored = 14, examples = examples), true),
        )
    }

    @Test
    fun `tooltips add the tracked files`() {
        assertEquals(
            "Ignores 1 path: a\nAlso matches 2 tracked files, which git keeps tracking: x, y",
            IgnoreRuleDots.tooltip(
                DotKind.ACTIVE,
                effect(ignored = 1, tracked = 2, examples = listOf("a"), trackedExamples = listOf("x", "y")),
                true,
            ),
        )
        assertEquals(
            "Ignores 1 path: a\nAlso matches 1 tracked file, which git keeps tracking: x",
            IgnoreRuleDots.tooltip(
                DotKind.ACTIVE,
                effect(ignored = 1, tracked = 1, examples = listOf("a"), trackedExamples = listOf("x")),
                true,
            ),
        )
        assertEquals(
            "Matches only 2 tracked files, which git keeps tracking: a.txt, … and 1 more",
            IgnoreRuleDots.tooltip(DotKind.TRACKED, effect(tracked = 2, trackedExamples = listOf("a.txt")), true),
        )
    }

    @Test
    fun `a line without effect says so, and an incomplete answer adds a note`() {
        assertEquals(
            "No effect: this line decides no untracked path",
            IgnoreRuleDots.tooltip(DotKind.NONE, null, true),
        )
        assertEquals(
            "No effect: this line decides no untracked path\nNot all paths could be checked: too many, or git took too long.",
            IgnoreRuleDots.tooltip(DotKind.NONE, null, false),
        )
    }

    @Test
    fun `tooltips without examples have no empty list`() {
        assertEquals("Ignores 3 paths", IgnoreRuleDots.tooltip(DotKind.ACTIVE, effect(ignored = 3), true))
        assertEquals(
            "Matches only 1 tracked file, which git keeps tracking",
            IgnoreRuleDots.tooltip(DotKind.TRACKED, effect(tracked = 1), true),
        )
    }

    private val dots = IgnoreRuleDots.of(text, effects)

    private fun moved(new: String) = IgnoreRuleDots.edited(text, new, dots).keys

    @Test
    fun `dots move along with edits`() {
        assertEquals(setOf(2, 4, 5, 6), moved("new\n$text"))
        assertEquals(setOf(0, 2, 3, 4), moved(text.removePrefix("# c\n")))
        assertEquals(setOf(1, 4, 5), moved(text.replace("*.log", "*.logs")))
        assertEquals(setOf(1, 3, 4, 5), moved(text + "x\n"))
    }

    @Test
    fun `two separate edits keep the dots in between`() {
        assertEquals(setOf(2, 4, 5), moved("new\n" + text.replace("old", "older")))
    }

    @Test
    fun `a pasted block of lines moves the dots below it`() {
        assertEquals(setOf(1, 3, 4, 8), moved(text.replace("!keep.log\n", "!keep.log\nx\ny\nz\n")))
        assertEquals(setOf(4, 6, 7, 8), moved("p\nq\nr\n$text"))
    }

    @Test
    fun `a deleted pattern line loses its dot and the others stay`() {
        assertEquals(setOf(1, 3, 4), moved(text.replace("*.log\n", "")))
    }

    @Test
    fun `undoing back to the saved text brings all dots back`() {
        assertEquals(setOf(1, 4, 5), moved(text.replace("*.log", "x")))
        assertEquals(setOf(1, 3, 4, 5), moved(text))
    }

    @Test
    fun `big files are shifted as one edit`() {
        val big = (0 until 6000).joinToString("\n") { "p$it" } + "\n"
        val bigDots = IgnoreRuleDots.of(big, IgnoreFileEffects(emptyMap(), true))
        val shifted = IgnoreRuleDots.edited(big, "new\n$big", bigDots)
        assertEquals(6000, shifted.size)
        assertEquals(6000, shifted.keys.max())
    }

    @Test
    fun `a table of lines too big to compare falls back to one edit`() {
        // 600 x 600 lines that differ in the middle: over the table limit.
        val old = (0 until 600).joinToString("\n") { "a$it" } + "\nkeep\n" + (0 until 600).joinToString("\n") { "z$it" }
        val new = (0 until 600).joinToString("\n") { "b$it" } + "\nkeep\n" + (0 until 600).joinToString("\n") { "y$it" }
        val bigDots = IgnoreRuleDots.of(old, IgnoreFileEffects(emptyMap(), true))
        // One edit from the first to the last change: every line is replaced, so no dot stays (the line diff would keep "keep").
        assertEquals(emptySet(), IgnoreRuleDots.edited(old, new, bigDots).keys)
    }
}
