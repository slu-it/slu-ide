package dev.sluide.ebooks.view

import dev.sluide.ebooks.BookPage
import dev.sluide.ebooks.ChapterSpan
import dev.sluide.fileviewers.ViewActivity
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.value.ObservableValue
import javafx.scene.Node
import javafx.scene.input.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BookReadingSurfaceTest {
    private class FakeView(
        override var page: BookPage?,
    ) : ReaderView {
        var start = 0
        val marks = mutableListOf<String>()
        val positions = mutableListOf<Int>()
        val jumps = mutableListOf<Int>()
        val reveals = mutableListOf<Int>()
        var chapterRange: IntRange? = null
        var visible = true
        override var startClicks = true
        var backButton = false

        override fun startOffset() = start

        override fun markRange(
            name: String,
            start: Int,
            end: Int,
        ) {
            marks += "$name $start-$end"
        }

        override fun clearMark(name: String) {
            marks += "clear $name"
        }

        override fun showPosition(offset: Int) {
            positions += offset
        }

        override fun jumpTo(offset: Int) {
            jumps += offset
        }

        override fun toLocal(offset: Int) = if (chapterRange?.contains(offset) != false) offset else null

        override fun isVisible(
            localStart: Int,
            localEnd: Int,
        ) = visible

        override fun revealLocal(local: Int) {
            reveals += local
        }

        override fun showBackButton(show: Boolean) {
            backButton = show
        }
    }

    private class FakeControls : ReaderControls {
        override val toolbar: Node get() = error("unused")
        override val notice: Node get() = error("unused")
        override val activity: ObservableValue<ViewActivity> = SimpleObjectProperty(ViewActivity.NONE)
        override var reading = false
        override var onFinished: () -> Unit = {}
        var plays = 0
        var stops = 0
        val jumps = mutableListOf<Int>()
        var onPlay: () -> Unit = {}

        override fun play() {
            plays++
            onPlay()
        }

        override fun stop() {
            stops++
        }

        override fun jumpTo(offset: Int) {
            jumps += offset
        }

        override fun handleKey(event: KeyEvent) = false

        override fun dispose() {}
    }

    private fun page(length: Int = 300) =
        BookPage(
            "",
            "x".repeat(length),
            listOf(1..2),
            listOf(ChapterSpan(0, "A", 0, 100), ChapterSpan(1, "B", 100, 200), ChapterSpan(2, "C", 200, 300)),
            emptyList(),
            emptyMap(),
        )

    private fun oneChapter(text: String) =
        BookPage("", text, emptyList(), listOf(ChapterSpan(0, "A", 0, text.length)), emptyList(), emptyMap())

    private fun surface(view: FakeView = FakeView(page())): Triple<FakeView, FakeControls, BookReadingSurface> {
        val controls = FakeControls()
        val surface = BookReadingSurface(view).also { it.controls = controls }
        return Triple(view, controls, surface)
    }

    @Test
    fun `no page reads nothing`() {
        assertNull(BookReadingSurface(FakeView(null)).readingText())
    }

    @Test
    fun `reads from the start of the view to the end of its chapter with the skip ranges`() {
        val (view, _, surface) = surface()
        view.start = 120
        val text = checkNotNull(surface.readingText())
        assertEquals(120, text.start)
        assertEquals(200, text.end)
        assertEquals(listOf(1..2), text.skip)
    }

    @Test
    fun `a reading is capped at a million chars, cut at the last paragraph break`() {
        val view = FakeView(oneChapter("a".repeat(1_500_000)))
        val surface = BookReadingSurface(view)
        view.start = 200_000
        assertEquals(1_200_000, surface.readingText()?.end)
        view.page = oneChapter("p".repeat(400_000) + "\n\n" + "q".repeat(900_000))
        view.start = 0
        assertEquals(400_000, surface.readingText()?.end)
    }

    @Test
    fun `a chapter finished reads on in the next one from its start, which beats the marked start`() {
        val (view, controls, surface) = surface()
        view.start = 10
        surface.readingText()
        val started = mutableListOf<Int>()
        controls.onPlay = { started += checkNotNull(surface.readingText()).start }
        controls.onFinished()
        assertEquals(listOf(100), started)
        // Used once: the next Play starts from the marked start again.
        assertEquals(10, surface.readingText()?.start)
    }

    @Test
    fun `nothing follows the last chapter`() {
        val (view, controls, surface) = surface()
        view.start = 250
        surface.readingText()
        controls.onFinished()
        assertEquals(0, controls.plays)
    }

    @Test
    fun `a reading cut at the limit goes on where it ended`() {
        val (_, controls, surface) = surface(FakeView(oneChapter("a".repeat(1_300_000))))
        surface.readingText()
        val started = mutableListOf<Int>()
        controls.onPlay = { started += checkNotNull(surface.readingText()).start }
        controls.onFinished()
        assertEquals(listOf(1_000_000), started)
    }

    @Test
    fun `highlight marks sentence and word and shows the position of a new sentence only`() {
        val (view, _, surface) = surface()
        surface.highlight(5..9, 5..6)
        surface.highlight(5..9, 7..9)
        surface.highlight(10..14, null)
        assertEquals(
            listOf("sentence 5-10", "word 5-7", "word 7-10", "sentence 10-15", "clear word"),
            view.marks,
        )
        assertEquals(listOf(5, 10), view.positions)
        surface.highlight(null, null)
        assertEquals(listOf("clear sentence", "clear word"), view.marks.takeLast(2))
        assertEquals(listOf(5, 10), view.positions)
    }

    @Test
    fun `locking switches the start clicks off`() {
        val (view, _, surface) = surface()
        surface.lock(true)
        assertFalse(view.startClicks)
        surface.lock(false)
        assertTrue(view.startClicks)
    }

    @Test
    fun `reveal scrolls only when the range is not visible`() {
        val (view, _, surface) = surface()
        view.visible = true
        surface.reveal(50, 55)
        assertTrue(view.reveals.isEmpty())
        view.visible = false
        surface.reveal(50, 55)
        assertEquals(listOf(50), view.reveals)
    }

    @Test
    fun `reveal in a chapter that is not shown jumps there once`() {
        val (view, _, surface) = surface()
        view.chapterRange = 0..99
        surface.reveal(150, 155)
        surface.reveal(156, 160)
        assertEquals(listOf(150), view.jumps)
        assertTrue(view.reveals.isEmpty())
        view.chapterRange = 100..199
        view.visible = true
        surface.reveal(170, 175)
        view.chapterRange = 0..99
        surface.reveal(250, 255)
        assertEquals(listOf(150, 250), view.jumps)
    }

    @Test
    fun `a user scroll while reading stops the following and the button brings it back`() {
        val (view, controls, surface) = surface()
        controls.reading = true
        surface.lock(true)
        surface.highlight(5..9, 5..6)
        view.visible = false
        surface.userScrolled()
        assertFalse(surface.following)
        assertTrue(view.backButton)
        surface.reveal(60, 65)
        assertTrue(view.reveals.isEmpty())
        surface.backToReading()
        assertTrue(surface.following)
        assertFalse(view.backButton)
        assertEquals(listOf(5), view.reveals)
    }

    @Test
    fun `a user scroll while idle does nothing`() {
        val (view, _, surface) = surface()
        surface.userScrolled()
        assertTrue(surface.following)
        assertFalse(view.backButton)
    }

    @Test
    fun `scrolling the sentence back into view resumes the following`() {
        val (view, controls, surface) = surface()
        controls.reading = true
        surface.lock(true)
        surface.highlight(5..9, null)
        view.visible = false
        surface.userScrolled()
        surface.userScrolled()
        assertFalse(surface.following)
        view.visible = true
        surface.userScrolled()
        assertTrue(surface.following)
        assertFalse(view.backButton)
        assertTrue(view.reveals.isEmpty())
    }

    @Test
    fun `a new Play follows again, a reading on from a chapter keeps the choice`() {
        val (view, controls, surface) = surface()
        controls.reading = true
        surface.lock(true)
        surface.userScrolled()
        surface.lock(false)
        surface.lock(true)
        assertTrue(surface.following)
        assertFalse(view.backButton)
        surface.userScrolled()
        surface.readingText()
        controls.onPlay = {
            surface.lock(false)
            surface.lock(true)
        }
        controls.onFinished()
        assertFalse(surface.following)
        assertTrue(view.backButton)
    }

    @Test
    fun `scrolling within the gesture that paused does not resume at once`() {
        val (view, controls, surface) = surface()
        controls.reading = true
        surface.lock(true)
        surface.highlight(5..9, null)
        view.visible = true
        surface.userScrolled()
        surface.userScrolled()
        assertFalse(surface.following)
        assertTrue(view.backButton)
    }

    @Test
    fun `navigating while reading stops the following, so the reading does not scroll back`() {
        val (view, controls, surface) = surface()
        controls.reading = true
        surface.lock(true)
        surface.highlight(5..9, null)
        view.visible = false
        surface.userNavigated()
        assertFalse(surface.following)
        assertTrue(view.backButton)
        surface.reveal(10, 12)
        assertTrue(view.reveals.isEmpty())
        assertTrue(view.jumps.isEmpty())
    }

    @Test
    fun `navigating while idle does nothing`() {
        val (view, _, surface) = surface()
        surface.userNavigated()
        assertTrue(surface.following)
        assertFalse(view.backButton)
    }

    @Test
    fun `a page load lets the next reveal in the same chapter jump again`() {
        val (view, _, surface) = surface()
        view.chapterRange = 0..99
        surface.reveal(150, 155)
        surface.reveal(156, 160)
        surface.pageLoaded()
        surface.reveal(157, 160)
        assertEquals(listOf(150, 157), view.jumps)
    }

    @Test
    fun `a double-click inside the reading jumps and follows again`() {
        val (view, controls, surface) = surface()
        surface.readingText()
        controls.reading = true
        surface.lock(true)
        surface.userScrolled()
        assertFalse(surface.doubleClicked(null))
        assertTrue(surface.doubleClicked(40))
        assertEquals(listOf(40), controls.jumps)
        assertTrue(surface.following)
        assertFalse(view.backButton)
        assertEquals(0, controls.stops)
    }

    @Test
    fun `a double-click outside the reading stops it and plays from there`() {
        val (view, controls, surface) = surface()
        view.start = 10
        surface.readingText()
        controls.reading = true
        val started = mutableListOf<Int>()
        controls.onPlay = { started += checkNotNull(surface.readingText()).start }
        assertTrue(surface.doubleClicked(150))
        assertEquals(1, controls.stops)
        assertEquals(listOf(150), started)
        assertTrue(controls.jumps.isEmpty())
        // Used once.
        assertEquals(10, surface.readingText()?.start)
    }

    @Test
    fun `a double-click more than a megabyte before the end of the reading plays from there`() {
        val (view, controls, surface) = surface(FakeView(oneChapter("a".repeat(2_500_000))))
        view.start = 2_000_000
        surface.readingText()
        controls.reading = true
        val started = mutableListOf<Int>()
        controls.onPlay = { started += checkNotNull(surface.readingText()).start }
        assertTrue(surface.doubleClicked(100))
        assertEquals(listOf(100), started)
        assertTrue(controls.jumps.isEmpty())
    }

    @Test
    fun `a double-click while idle does nothing`() {
        val (_, controls, surface) = surface()
        assertFalse(surface.doubleClicked(40))
        assertTrue(controls.jumps.isEmpty())
    }
}
