package dev.sluide.previews.preview

import dev.sluide.fileviewers.StyledRange
import kotlin.test.Test
import kotlin.test.assertEquals

class PreviewHighlightMirrorTest {
    /** Records the calls; ranges in [visible] are in view. */
    private class FakePage : HighlightPage {
        val calls = mutableListOf<String>()
        var visible: (Int, Int) -> Boolean = { _, _ -> true }

        override fun mark(
            name: String,
            start: Int,
            end: Int,
        ) {
            calls += "mark $name $start $end"
        }

        override fun clear(name: String) {
            calls += "clear $name"
        }

        override fun reveal(start: Int) {
            calls += "reveal $start"
        }

        override fun isVisible(
            start: Int,
            end: Int,
        ) = visible(start, end)
    }

    private val page = FakePage()
    private var alone = false
    private var matches = true
    private val mirror = PreviewHighlightMirror(page, { alone }, { matches })

    private fun sentence(
        start: Int,
        end: Int,
    ) = StyledRange(start, end, "speech-sentence")

    private fun word(
        start: Int,
        end: Int,
    ) = StyledRange(start, end, "speech-word")

    @Test
    fun `each shown style is a mark, set only when it changes and cleared with its layer`() {
        mirror.overlaid("read-aloud", listOf(sentence(0, 20), word(0, 5)))
        mirror.overlaid("read-aloud", listOf(sentence(0, 20), word(6, 9)))
        mirror.overlaid("read-aloud", emptyList())

        assertEquals(
            listOf(
                "mark speech-sentence 0 20",
                "mark speech-word 0 5",
                "mark speech-word 6 9",
                "clear speech-sentence",
                "clear speech-word",
            ),
            page.calls,
        )
    }

    @Test
    fun `classes the page does not style are not mirrored, and their layers change nothing`() {
        mirror.overlaid("diagnostics", listOf(StyledRange(0, 3, "diagnostic-error")))
        mirror.overlaid("read-aloud", listOf(word(4, 7), StyledRange(8, 9, "other")))
        mirror.overlaid("diagnostics", emptyList())

        assertEquals(listOf("mark speech-word 4 7"), page.calls)
    }

    @Test
    fun `a class in two layers covers both, and clearing one layer leaves the other's`() {
        mirror.overlaid("a", listOf(word(10, 12)))
        mirror.overlaid("b", listOf(word(2, 4)))
        mirror.overlaid("a", emptyList())

        assertEquals(listOf("mark speech-word 10 12", "mark speech-word 2 12", "mark speech-word 2 4"), page.calls)
    }

    @Test
    fun `a mark set before one already shown sets both again, so that the later one paints over it`() {
        mirror.overlaid("read-aloud", listOf(word(3, 5)))
        page.calls.clear()

        mirror.overlaid("read-aloud", listOf(sentence(0, 9), word(3, 5)))

        assertEquals(listOf("clear speech-word", "mark speech-sentence 0 9", "mark speech-word 3 5"), page.calls)
    }

    @Test
    fun `nothing is marked while the source has unsaved changes the page does not show`() {
        matches = false
        mirror.overlaid("read-aloud", listOf(sentence(0, 9), word(3, 5)))

        assertEquals(emptyList(), page.calls)
    }

    @Test
    fun `a disposed mirror neither marks nor clears`() {
        mirror.overlaid("read-aloud", listOf(word(3, 5)))
        page.calls.clear()

        mirror.dispose()
        mirror.overlaid("read-aloud", emptyList())
        mirror.overlaid("read-aloud", listOf(word(6, 9)))
        mirror.loaded()

        assertEquals(emptyList(), page.calls)
    }

    @Test
    fun `a loaded page gets every mark again`() {
        mirror.overlaid("read-aloud", listOf(sentence(0, 9), word(3, 5)))
        page.calls.clear()

        mirror.loaded()

        assertEquals(listOf("mark speech-sentence 0 9", "mark speech-word 3 5"), page.calls)
    }

    @Test
    fun `the last range is revealed only while the preview shows alone and the range is out of view`() {
        page.visible = { start, _ -> start < 100 }
        mirror.overlaid("read-aloud", listOf(sentence(90, 200), word(150, 155)))
        alone = true
        mirror.overlaid("read-aloud", listOf(sentence(90, 200), word(50, 55)))
        mirror.overlaid("read-aloud", listOf(sentence(90, 200), word(160, 165)))

        assertEquals(listOf("reveal 160"), page.calls.filter { it.startsWith("reveal") })
    }
}
