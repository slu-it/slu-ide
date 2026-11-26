package dev.sluide.pdf.view

import dev.sluide.pdf.TestPdfs
import dev.sluide.pdf.engine.PageRect
import dev.sluide.pdf.engine.PageTarget
import javafx.geometry.Bounds
import javafx.geometry.Point2D
import javafx.scene.Cursor
import javafx.scene.input.KeyCode
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The edges of the selection's input handling: events in the wrong order or place, texts that arrive after the press,
 * pages beyond the first, keys that are not the selection's, and the copy waiting for its texts. Needs a scene.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
internal class PdfSelectionEdgesUiTest : PdfSelectionUiBase() {
    @Start
    fun start(stage: Stage) = startStage(stage)

    private val errors = mutableListOf<Throwable>()

    /** Collects what the FX thread's callbacks throw: a text arriving late runs outside any event of the test. */
    private fun catchErrors() =
        fx {
            Thread.currentThread().uncaughtExceptionHandler =
                Thread.UncaughtExceptionHandler { _, error -> synchronized(errors) { errors += error } }
        }

    private fun thrown() = synchronized(errors) { errors.toList() }

    private fun move(
        view: PdfFileView,
        at: Point2D,
    ) = fire(view, mouse(MouseEvent.MOUSE_MOVED, at))

    @Test
    fun `a secondary press starts no selection, and a secondary release does not end a primary press`() {
        val view = textView()
        val second = scene(view, "second")
        val secondary = MouseButton.SECONDARY
        fire(view, mouse(MouseEvent.MOUSE_PRESSED, second.left(), button = secondary))
        fire(view, mouse(MouseEvent.MOUSE_DRAGGED, second.right(), button = secondary))
        fire(view, mouse(MouseEvent.MOUSE_RELEASED, second.right(), button = secondary))
        assertFalse(selected(view))
        key(view, KeyCode.A, shortcut = true)
        fire(view, mouse(MouseEvent.MOUSE_PRESSED, second.centre()))
        fire(view, mouse(MouseEvent.MOUSE_RELEASED, second.centre(), button = secondary))
        assertTrue(selected(view))
    }

    @Test
    fun `drags and releases without a press, and presses and drags off the pages, are harmless`() {
        val view = textView()
        val second = scene(view, "second")
        key(view, KeyCode.A, shortcut = true)
        fire(view, mouse(MouseEvent.MOUSE_DRAGGED, second.centre()))
        fire(view, mouse(MouseEvent.MOUSE_RELEASED, second.centre()))
        assertTrue(selected(view))
        // Beside the page, in the column's margin.
        val edge = fx { view.pages.localToScene(view.pages.layoutBounds) }
        val outside = Point2D(edge.minX + 2, second.centerY)
        drag(view, second.left(), outside)
        assertTrue(selected(view))
        click(view, outside)
        assertFalse(selected(view))
    }

    /** Zooms in until both scroll bars of the pages show, and gives the horizontal bar and the corner. */
    private fun withBothBars(view: PdfFileView): Pair<javafx.scene.Node, javafx.scene.Node> {
        repeat(6) { fx { view.pageColumn.zoomIn() } }
        await {
            view.pages
                .lookupAll(".scroll-bar")
                .filterIsInstance<javafx.scene.control.ScrollBar>()
                .count { it.isVisible } == 2
        }
        val bar =
            fx {
                view.pages
                    .lookupAll(".scroll-bar")
                    .filterIsInstance<javafx.scene.control.ScrollBar>()
                    .first { it.orientation == javafx.geometry.Orientation.HORIZONTAL }
            }
        return bar to fx { view.pages.lookup(".corner") }
    }

    private fun centreOf(node: javafx.scene.Node): Point2D =
        fx {
            val bounds = node.localToScene(node.layoutBounds)
            Point2D(bounds.centerX, bounds.centerY)
        }

    @Test
    fun `a drag along a scroll bar of the pages selects nothing`() {
        val view = textView()
        val (bar, _) = withBothBars(view)
        val at = centreOf(bar)
        fx {
            javafx.event.Event.fireEvent(bar, mouse(MouseEvent.MOUSE_PRESSED, at))
            javafx.event.Event.fireEvent(bar, mouse(MouseEvent.MOUSE_DRAGGED, at.add(60.0, 0.0)))
            javafx.event.Event.fireEvent(bar, mouse(MouseEvent.MOUSE_RELEASED, at.add(60.0, 0.0)))
        }
        assertFalse(selected(view))
    }

    @Test
    fun `a click on the corner between the scroll bars keeps the selection`() {
        val view = textView()
        val (_, corner) = withBothBars(view)
        key(view, KeyCode.A, shortcut = true)
        assertTrue(selected(view))
        val at = centreOf(corner)
        fx {
            javafx.event.Event.fireEvent(corner, mouse(MouseEvent.MOUSE_PRESSED, at))
            javafx.event.Event.fireEvent(corner, mouse(MouseEvent.MOUSE_RELEASED, at))
        }
        assertTrue(selected(view))
    }

    @Test
    fun `a press that opens the context menu keeps the selection`() {
        val view = textView()
        val second = scene(view, "second")
        key(view, KeyCode.A, shortcut = true)
        // Ctrl+click on macOS: a primary press that is a popup trigger.
        fire(view, mouse(MouseEvent.MOUSE_PRESSED, second.centre(), popupTrigger = true, control = true))
        fire(view, mouse(MouseEvent.MOUSE_RELEASED, second.centre(), popupTrigger = true, control = true))
        assertTrue(selected(view))
    }

    @Test
    fun `a press gives the pages the focus`() {
        val view = textView()
        fx { view.toolbar.pageField.requestFocus() }
        assertFalse(fx { view.pages.scene.focusOwner === view.pages })
        fire(view, mouse(MouseEvent.MOUSE_PRESSED, scene(view, "second").centre()))
        assertTrue(fx { view.pages.scene.focusOwner === view.pages })
    }

    @Test
    fun `a press alone selects nothing, and a move of up to 3 px is not a drag`() {
        val view = textView()
        val from = scene(view, "second").centre()
        fire(view, mouse(MouseEvent.MOUSE_PRESSED, from))
        assertFalse(selected(view))
        fire(view, mouse(MouseEvent.MOUSE_DRAGGED, from.add(2.0, 0.0)))
        assertFalse(selected(view))
        fire(view, mouse(MouseEvent.MOUSE_DRAGGED, from.add(3.0, 0.0)))
        assertFalse(selected(view))
        fire(view, mouse(MouseEvent.MOUSE_DRAGGED, from.add(4.0, 0.0)))
        assertTrue(selected(view))
        fire(view, mouse(MouseEvent.MOUSE_RELEASED, from.add(4.0, 0.0)))
        assertTrue(selected(view))
    }

    @Test
    fun `a selection paints exactly the boxes of its chars`() {
        val view = textView()
        val second = scene(view, "second")
        drag(view, second.left(), second.right())
        val rects =
            fx {
                view.overlays
                    .on(0)
                    .filter { it.first == OverlayLayer.SELECTION }
                    .map { it.second }
            }
        val word = boxOf(textOf(view), "second")
        assertEquals(1, rects.size)
        assertEquals(word.x, rects[0].x, 1e-6)
        assertEquals(word.y, rects[0].y, 1e-6)
        assertEquals(word.width, rects[0].width, 1e-6)
        assertEquals(word.height, rects[0].height, 1e-6)
    }

    @Test
    fun `select all on a page without text paints nothing and throws nothing`() {
        val view = open(TestPdfs.blank)
        awaitRendered(view, 0)
        catchErrors()
        key(view, KeyCode.A, shortcut = true)
        textOf(view)
        assertFalse(selected(view))
        // Presses and drags on a page of no text (every page of a scan) find no char and select nothing.
        val centre = sceneRect(view, 0, PageRect(200.0, 300.0, 10.0, 10.0)).centre()
        click(view, centre)
        click(view, centre, clicks = 2)
        drag(view, centre, centre.add(80.0, 60.0))
        assertFalse(selected(view))
        assertEquals(emptyList(), thrown())
    }

    @Test
    fun `copy of a selection on a page without text leaves the clipboard alone`() {
        val view = open(TestPdfs.blank)
        awaitRendered(view, 0)
        clearClipboard()
        key(view, KeyCode.A, shortcut = true)
        key(view, KeyCode.C, shortcut = true)
        textOf(view) // served after the copy's own request for the same text
        assertEquals(UNTOUCHED, clipboard())
    }

    @Test
    fun `copy of a range that starts after the first page does not touch the pages before it`() {
        val view = open(TestPdfs.threePages)
        awaitRendered(view, 0)
        fx {
            view.pageColumn.zoomOut()
            view.pageColumn.zoomOut()
        }
        textOf(view, 0)
        val word = sceneRect(view, 1, boxOf(textOf(view, 1), "Page 2"))
        clearClipboard()
        drag(view, word.left(), word.right())
        key(view, KeyCode.C, shortcut = true)
        awaitClipboard("Page 2")
    }

    @Test
    fun `the pointer of a drag that a newer press replaced is not used when its text arrives`() {
        val view =
            heldPages(cached = listOf(0)) { view, first, second ->
                drag(view, first().left(), second().right())
                click(view, sceneRect(view, 0, PageRect(400.0, 400.0, 10.0, 10.0)).centre())
            }
        textOf(view, 1)
        assertFalse(selected(view, 0))
        assertFalse(selected(view, 1))
    }

    @Test
    fun `select all paints the shown pages only`() {
        val view = open(TestPdfs.threePages)
        awaitRendered(view, 0)
        textOf(view, 0)
        fx { view.scrollTo(PageTarget(2)) }
        awaitRendered(view, 2)
        key(view, KeyCode.A, shortcut = true)
        await { view.overlays.on(2).isNotEmpty() }
        assertFalse(selected(view, 0))
    }

    /** Whether [event], fired at the pages, bubbles up to the view's root: the pages' handlers did not take it. */
    private fun bubbles(
        view: PdfFileView,
        event: javafx.scene.input.KeyEvent,
    ): Boolean {
        var reached = false
        val handler = javafx.event.EventHandler<javafx.scene.input.KeyEvent> { reached = true }
        fx {
            view.node.addEventHandler(javafx.scene.input.KeyEvent.KEY_PRESSED, handler)
            javafx.event.Event.fireEvent(view.pages, event)
            view.node.removeEventHandler(javafx.scene.input.KeyEvent.KEY_PRESSED, handler)
        }
        return reached
    }

    @Test
    fun `Escape is taken only while something is selected, the other keys only with Shortcut and no more`() {
        val view = textView()
        assertTrue(bubbles(view, keyPress(KeyCode.ESCAPE)))
        val others =
            listOf(
                keyPress(KeyCode.A),
                keyPress(KeyCode.C),
                keyPress(KeyCode.X, shortcut = true),
                keyPress(KeyCode.A, shortcut = true, shift = true),
                keyPress(KeyCode.C, shortcut = true, alt = true),
            )
        for (other in others) assertTrue(bubbles(view, other), other.toString())
        assertFalse(selected(view))
        assertFalse(bubbles(view, keyPress(KeyCode.A, shortcut = true)))
        assertTrue(selected(view))
        // A key that is not Escape, such as the bare Ctrl or Cmd before C, leaves the selection alone.
        assertTrue(bubbles(view, keyPress(KeyCode.CONTROL, shortcut = true)))
        assertTrue(bubbles(view, keyPress(KeyCode.SHIFT, shift = true)))
        assertTrue(selected(view))
        assertFalse(bubbles(view, keyPress(KeyCode.C, shortcut = true)))
        assertFalse(bubbles(view, keyPress(KeyCode.ESCAPE)))
        assertFalse(selected(view))
    }

    @Test
    fun `select all without an open document does nothing`() {
        val view = open(TestPdfs.notPdf)
        await { view.pageNotice.shown }
        key(view, KeyCode.A, shortcut = true)
        assertFalse(selected(view))
    }

    @Test
    fun `a press whose text arrives after a newer press does not select`() {
        val view =
            heldView { view, second, line ->
                drag(view, second.left(), second.right())
                click(view, line.centre())
            }
        textOf(view)
        assertFalse(selected(view))
    }

    @Test
    fun `a double-click whose text arrives after a newer press does not select its word`() {
        val view =
            heldView { view, second, line ->
                click(view, line.centre(), clicks = 1)
                click(view, line.centre(), clicks = 2)
                click(view, second.centre())
            }
        textOf(view)
        assertFalse(selected(view))
    }

    /**
     * The three pages, reloaded (their texts gone), with the texts of [cached] pages and the plain texts of [plain]
     * pages loaded again, and the text worker held while [act] runs; [act] gets the scene boxes of the first "Page" of
     * page 1 and of "Page 2" of page 2.
     */
    private fun heldPages(
        cached: List<Int>,
        plain: List<Int> = emptyList(),
        act: (PdfFileView, () -> Bounds, () -> Bounds) -> Unit,
    ): PdfFileView {
        val file = dir.resolve("held-pages.pdf")
        Files.copy(TestPdfs.threePages, file)
        var reloads = 0
        val view = open(file) { it.onReload += { reloads++ } }
        awaitRendered(view, 0)
        val first = boxOf(textOf(view, 0), "Page")
        val second = boxOf(textOf(view, 1), "Page 2")
        reload(view, file) { reloads }
        fx {
            view.pageColumn.zoomOut()
            view.pageColumn.zoomOut()
        }
        cached.forEach { textOf(view, it) }
        for (page in plain) {
            var arrived = false
            fx { view.texts.plainTextOf(page) { arrived = true } }
            await { arrived }
        }
        val latch = CountDownLatch(1)
        fx { view.textWorker.submit(WorkPriority.VISIBLE, { latch.await() }, {}) }
        try {
            clearClipboard()
            act(view, { sceneRect(view, 0, first) }, { sceneRect(view, 1, second) })
        } finally {
            latch.countDown()
        }
        return view
    }

    @Test
    fun `a drag from a page whose text is late to a page whose text is there selects across them`() {
        val view =
            heldPages(cached = listOf(1)) { view, first, second ->
                drag(view, first().left(), second().right())
            }
        textOf(view, 0)
        assertTrue(selected(view, 0))
        key(view, KeyCode.C, shortcut = true)
        awaitClipboard("Page 1\n\nPage 2")
    }

    @Test
    fun `the anchor of a press that a newer press replaced is not used when its text arrives`() {
        val view =
            heldPages(cached = listOf(1)) { view, first, second ->
                drag(view, first().left(), second().right())
                click(view, sceneRect(view, 0, PageRect(400.0, 400.0, 10.0, 10.0)).centre())
            }
        textOf(view, 0)
        assertFalse(selected(view, 0))
        assertFalse(selected(view, 1))
    }

    @Test
    fun `copy of a range of shown pages and a loaded plain text does not wait for the worker`() {
        val view =
            heldPages(cached = listOf(0, 1), plain = listOf(2)) { view, _, _ ->
                key(view, KeyCode.A, shortcut = true)
                key(view, KeyCode.C, shortcut = true)
                awaitClipboard("Page 1\n\nPage 2\n\nPage 3")
            }
        assertTrue(selected(view, 0))
    }

    @Test
    fun `copy waits for all the texts of its range before it sets the clipboard, and throws nothing`() {
        val view = open(TestPdfs.threePages)
        awaitRendered(view, 0)
        catchErrors()
        clearClipboard()
        key(view, KeyCode.A, shortcut = true)
        key(view, KeyCode.C, shortcut = true)
        awaitClipboard("Page 1\n\nPage 2\n\nPage 3")
        assertEquals(emptyList(), thrown())
    }

    private fun pageTwo(): PdfFileView {
        val view = open(TestPdfs.pageTwoLinks)
        awaitRendered(view, 0)
        fx { view.scrollTo(PageTarget(1)) }
        awaitRendered(view, 1)
        textOf(view, 1)
        return view
    }

    private fun longer(view: PdfFileView): Bounds = sceneRect(view, 1, boxOf(textOf(view, 1), "longer"))

    @Test
    fun `a drag on the second page selects and copies its text`() {
        val view = pageTwo()
        val word = longer(view)
        clearClipboard()
        drag(view, word.left(), word.right())
        key(view, KeyCode.C, shortcut = true)
        awaitClipboard("longer")
        assertFalse(selected(view, 0))
    }

    @Test
    fun `a double-click on the second page selects the word under it`() {
        val view = pageTwo()
        val word = longer(view)
        clearClipboard()
        click(view, word.centre(), clicks = 1)
        click(view, word.centre(), clicks = 2)
        key(view, KeyCode.C, shortcut = true)
        awaitClipboard("longer")
    }

    @Test
    fun `the cursor on the second page is the text cursor over its chars`() {
        val view = pageTwo()
        awaitCursor(view, longer(view).centre(), Cursor.TEXT)
    }

    @Test
    fun `a link on the second page shows the hand and its address, and a click follows it`() {
        val host = FakeHost()
        val view = open(TestPdfs.pageTwoLinks) { it.host = host }
        awaitRendered(view, 0)
        fx { view.scrollTo(PageTarget(1)) }
        awaitRendered(view, 1)
        val link = sceneRect(view, 1, TestPdfs.PAGE_TWO_LINK_RECT).centre()
        awaitCursor(view, link, Cursor.HAND)
        assertEquals(TestPdfs.PAGE_TWO_LINK_URI, fx { PdfSelection.of(view).tooltip.text })
        click(view, link)
        assertEquals(listOf(URI(TestPdfs.PAGE_TWO_LINK_URI)), host.webPages)
    }

    @Test
    fun `moving and clicking over a page before its links arrived do nothing`() {
        val host = FakeHost()
        var reached = false
        var failure: Throwable? = null
        val view =
            open(TestPdfs.selectionLinks) { opening ->
                opening.host = host
                opening.shownPages.subscribe { pages ->
                    // The same FX task that shows the pages: the links are asked for, but cannot be there yet.
                    if (!pages.isEmpty() && !reached) {
                        reached = true
                        try {
                            val link = checkNotNull(opening.pageRectToScene(0, TestPdfs.selectionLinkRect(0)))
                            val at = Point2D(link.centerX, link.centerY)
                            check(opening.pagePointAt(at.x, at.y) != null)
                            javafx.event.Event.fireEvent(opening.pages, mouse(MouseEvent.MOUSE_MOVED, at))
                            javafx.event.Event.fireEvent(opening.pages, mouse(MouseEvent.MOUSE_PRESSED, at))
                            javafx.event.Event.fireEvent(opening.pages, mouse(MouseEvent.MOUSE_RELEASED, at))
                        } catch (e: Throwable) {
                            failure = e
                        }
                    }
                }
            }
        await { reached }
        assertEquals(null, failure)
        assertEquals(emptyList(), host.webPages)
        assertFalse(fx { view.pageCursor == Cursor.HAND })
    }

    @Test
    fun `text that arrives late does not take the hand cursor off a link`() {
        val view =
            heldView { view, second, _ ->
                // The text under the pointer is on its way; the pointer moves on to the link before it arrives.
                move(view, second.centre())
                awaitCursor(view, sceneRect(view, 0, PageRect(72.0, 72.0, 200.0, 30.0)).centre(), Cursor.HAND)
            }
        textOf(view)
        assertEquals(Cursor.HAND, fx { view.pageCursor })
    }

    @Test
    fun `leaving a link hides its tooltip`() {
        val view = open(TestPdfs.selectionLinks)
        awaitRendered(view, 0)
        awaitCursor(view, sceneRect(view, 0, TestPdfs.selectionLinkRect(0)).centre(), Cursor.HAND)
        val tooltip = fx { PdfSelection.of(view).tooltip }
        fx { tooltip.show(view.pages, 100.0, 100.0) }
        assertTrue(fx { tooltip.isShowing })
        awaitCursor(view, sceneRect(view, 0, PageRect(400.0, 600.0, 10.0, 10.0)).centre(), null)
        assertFalse(fx { tooltip.isShowing })
    }
}
