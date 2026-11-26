package dev.sluide.pdf.view

import dev.sluide.pdf.TestPdfs
import dev.sluide.pdf.engine.PageRect
import javafx.geometry.Point2D
import javafx.scene.Cursor
import javafx.scene.control.Tooltip
import javafx.scene.input.Clipboard
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
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Selection, copy and links in the PDF view, through events fired at the pages. Needs a scene: event routing, scene
 * coordinates, the clipboard, painting, the context menu and the cursor.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
internal class PdfSelectionUiTest : PdfSelectionUiBase() {
    @Start
    fun start(stage: Stage) = startStage(stage)

    @Test
    fun `a drag over a word selects it and paints it, and the word beside it stays unpainted`() {
        val view = textView()
        val second = scene(view, "second")
        val line = scene(view, "line")
        assertFalse(colours(paint(), second).hasSelectionBlue())
        drag(view, second.left(), second.right())
        assertTrue(selected(view))
        val painted = paint()
        assertTrue(colours(painted, second).hasSelectionBlue())
        assertFalse(colours(painted, line).hasSelectionBlue())
    }

    @Test
    fun `Shortcut+C puts the dragged word on the clipboard, drawn forwards or backwards`() {
        val view = textView()
        val second = scene(view, "second")
        for ((from, to) in listOf(second.left() to second.right(), second.right() to second.left())) {
            clearClipboard()
            drag(view, from, to)
            key(view, KeyCode.C, shortcut = true)
            awaitClipboard("second")
        }
    }

    @Test
    fun `a drag that starts beside the text starts at the nearest char of the page`() {
        val view = textView()
        val second = scene(view, "second")
        val page = sceneRect(view, 0, PageRect(0.0, 0.0, 612.0, 792.0))
        clearClipboard()
        drag(view, Point2D(page.minX + 5, second.centerY), second.right())
        key(view, KeyCode.C, shortcut = true)
        awaitClipboard("second")
    }

    @Test
    fun `a double-click selects the word under it`() {
        val view = textView()
        val line = scene(view, "line")
        clearClipboard()
        click(view, line.centre(), clicks = 1)
        click(view, line.centre(), clicks = 2)
        assertTrue(selected(view))
        key(view, KeyCode.C, shortcut = true)
        awaitClipboard("line")
    }

    @Test
    fun `Shortcut+A and Shortcut+C over three pages copy them joined by blank lines`() {
        val view = open(TestPdfs.threePages)
        awaitRendered(view, 0)
        clearClipboard()
        key(view, KeyCode.A, shortcut = true)
        await { selected(view) } // the page text is extracted first
        key(view, KeyCode.C, shortcut = true)
        awaitClipboard("Page 1\n\nPage 2\n\nPage 3")
    }

    @Test
    fun `Shortcut+Shift+A does not select all`() {
        val view = textView()
        fire(view, keyPress(KeyCode.A, shortcut = true, shift = true))
        assertFalse(selected(view))
    }

    @Test
    fun `Shortcut+Alt+C does not copy`() {
        val view = textView()
        key(view, KeyCode.A, shortcut = true)
        clearClipboard()
        fire(view, keyPress(KeyCode.C, shortcut = true, alt = true))
        Thread.sleep(300) // negative check: a copy that does not happen gives no signal
        assertEquals(UNTOUCHED, clipboard())
    }

    @Test
    fun `Escape clears the selection, and with none selected copy does nothing`() {
        val view = textView()
        key(view, KeyCode.A, shortcut = true)
        assertTrue(selected(view))
        key(view, KeyCode.ESCAPE)
        assertFalse(selected(view))
        clearClipboard()
        key(view, KeyCode.C, shortcut = true)
        // The copy runs on the FX thread; a plain text request settles in the background. Nothing may reach it.
        Thread.sleep(300) // negative check: there is no signal for a copy that does not happen
        assertEquals(UNTOUCHED, clipboard())
    }

    @Test
    fun `a click without a drag clears the selection, one that moved a little too, a drag does not`() {
        val view = textView()
        val second = scene(view, "second")
        key(view, KeyCode.A, shortcut = true)
        click(view, second.centre())
        assertFalse(selected(view))
        key(view, KeyCode.A, shortcut = true)
        val from = second.centre()
        fire(view, mouse(MouseEvent.MOUSE_PRESSED, from))
        fire(view, mouse(MouseEvent.MOUSE_DRAGGED, from.add(2.0, 0.0)))
        fire(view, mouse(MouseEvent.MOUSE_RELEASED, from.add(2.0, 0.0)))
        assertFalse(selected(view))
        drag(view, second.left(), second.right())
        assertTrue(selected(view))
    }

    @Test
    fun `a secondary click keeps the selection`() {
        val view = textView()
        val second = scene(view, "second")
        key(view, KeyCode.A, shortcut = true)
        fire(view, mouse(MouseEvent.MOUSE_PRESSED, second.centre(), button = MouseButton.SECONDARY))
        fire(view, mouse(MouseEvent.MOUSE_RELEASED, second.centre(), button = MouseButton.SECONDARY))
        assertTrue(selected(view))
    }

    @Test
    fun `a reload clears the selection`() {
        val file = dir.resolve("changing.pdf")
        Files.copy(TestPdfs.text, file)
        var reloads = 0
        val view = textView(file) { it.onReload += { reloads++ } }
        key(view, KeyCode.A, shortcut = true)
        assertTrue(selected(view))
        Files.copy(TestPdfs.text, file, StandardCopyOption.REPLACE_EXISTING)
        Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 10_000))
        fx { view.reload() }
        await { reloads == 1 }
        assertFalse(selected(view))
        clearClipboard()
        key(view, KeyCode.C, shortcut = true)
        Thread.sleep(300) // negative check: nothing is selected, so no text is ever requested to wait for
        assertEquals(UNTOUCHED, clipboard())
    }

    @Test
    fun `the context menu has Copy and Select All, Copy disabled while nothing is selected`() {
        val view = textView()
        val items = fx { view.pageContextMenu.items.toList() }
        assertEquals(listOf("Copy", "Select All"), items.map { it.text })
        val (copy, selectAll) = items
        assertTrue(fx { copy.isDisable })
        assertFalse(fx { selectAll.isDisable })
        fx { selectAll.fire() }
        assertTrue(selected(view))
        assertFalse(fx { copy.isDisable })
        clearClipboard()
        fx { copy.fire() }
        await { Clipboard.getSystemClipboard().string?.startsWith("Alpha needle omega\nsecond line") == true }
        key(view, KeyCode.ESCAPE)
        assertTrue(fx { copy.isDisable })
    }

    @Test
    fun `the cursor is the text cursor over a char, the hand over a link and none elsewhere`() {
        val view = textView(TestPdfs.text)
        val second = scene(view, "second")
        awaitCursor(view, second.centre(), Cursor.TEXT)
        val blank = sceneRect(view, 0, PageRect(400.0, 400.0, 10.0, 10.0)).centre()
        awaitCursor(view, blank, null)
        // The link of the first line covers "Alpha": links win over text.
        awaitCursor(view, scene(view, "Alpha").centre(), Cursor.HAND)
        awaitCursor(view, second.centre(), Cursor.TEXT)
        val outside = Point2D(second.minX - 500, second.centerY)
        awaitCursor(view, outside, null)
    }

    @Test
    fun `a click on a link to a page scrolls to it`() {
        val view = open(TestPdfs.selectionLinks)
        awaitRendered(view, 0)
        val link = sceneRect(view, 0, TestPdfs.selectionLinkRect(1)).centre()
        awaitCursor(view, link, Cursor.HAND)
        assertEquals(0, fx { view.currentPage.get() })
        click(view, link)
        assertEquals(2, fx { view.currentPage.get() })
    }

    @Test
    fun `a click on a web link opens it through the host, and the tooltip says where it leads`() {
        val host = FakeHost()
        val view = open(TestPdfs.selectionLinks) { it.host = host }
        awaitRendered(view, 0)
        val link = sceneRect(view, 0, TestPdfs.selectionLinkRect(0)).centre()
        awaitCursor(view, link, Cursor.HAND)
        val tooltip = fx { view.pages.properties["javafx.scene.control.Tooltip"] as Tooltip? }
        assertEquals(TestPdfs.SELECTION_LINK_URI, tooltip?.text)
        click(view, link)
        assertEquals(listOf(URI(TestPdfs.SELECTION_LINK_URI)), host.webPages)
        val internal = sceneRect(view, 0, TestPdfs.selectionLinkRect(1)).centre()
        awaitCursor(view, internal, Cursor.HAND)
        assertEquals("Page 3", (fx { view.pages.properties["javafx.scene.control.Tooltip"] as Tooltip? })?.text)
        awaitCursor(view, sceneRect(view, 0, PageRect(400.0, 600.0, 10.0, 10.0)).centre(), null)
        assertEquals(null, fx { view.pages.properties["javafx.scene.control.Tooltip"] })
    }

    @Test
    fun `a click on a link without a host does nothing`() {
        val view = open(TestPdfs.selectionLinks)
        awaitRendered(view, 0)
        val link = sceneRect(view, 0, TestPdfs.selectionLinkRect(0)).centre()
        awaitCursor(view, link, Cursor.HAND)
        click(view, link)
        assertEquals(0, fx { view.currentPage.get() })
    }

    @Test
    fun `a drag that starts on a link selects the text and does not follow it, a plain click on it does`() {
        val host = FakeHost()
        val view = textView { it.host = host }
        val alpha = scene(view, "Alpha")
        awaitCursor(view, alpha.centre(), Cursor.HAND)
        drag(view, alpha.left(), scene(view, "needle").right())
        assertTrue(selected(view))
        assertEquals(emptyList(), host.webPages)
        click(view, alpha.centre())
        assertEquals(1, host.webPages.size)
    }

    @Test
    fun `the selection on page 2 keeps its rects across a scroll and a zoom`() {
        val view = open(TestPdfs.threePages)
        awaitRendered(view, 0)
        key(view, KeyCode.A, shortcut = true)
        fx {
            view.scrollTo(
                dev.sluide.pdf.engine
                    .PageTarget(1),
            )
        }
        awaitRendered(view, 1)
        val word = boxOf(textOf(view, 1), "Page")
        assertTrue(colours(paint(), sceneRect(view, 1, word)).hasSelectionBlue())
        fx { view.pageColumn.zoomIn() }
        fx { view.scrollIntoView(1, word) }
        awaitRendered(view, 1)
        assertTrue(colours(paint(), sceneRect(view, 1, word)).hasSelectionBlue())
        // The page that came into view after the selection was made is drawn too.
        fx {
            view.scrollTo(
                dev.sluide.pdf.engine
                    .PageTarget(2),
            )
        }
        awaitRendered(view, 2)
        assertNotNull(fx { view.overlays.on(2).firstOrNull() })
    }

    @Test
    fun `a drag released before the text of its page arrived still selects, and copies`() {
        val view = heldView { view, second, _ -> drag(view, second.left(), second.right()) }
        await { view.overlays.on(0).any { it.first == OverlayLayer.SELECTION } }
        key(view, KeyCode.C, shortcut = true)
        awaitClipboard("second")
    }

    @Test
    fun `a double-click on a page whose text arrives late still selects the word`() {
        val view =
            heldView { view, _, line ->
                click(view, line.centre(), clicks = 1)
                click(view, line.centre(), clicks = 2)
            }
        await { view.overlays.on(0).any { it.first == OverlayLayer.SELECTION } }
        key(view, KeyCode.C, shortcut = true)
        awaitClipboard("line")
    }

    @Test
    fun `copy of shown pages does not wait for the plain texts queued behind a search`() {
        val view = textView()
        val second = scene(view, "second")
        val latch = CountDownLatch(1)
        fx { view.textWorker.submit(WorkPriority.SEARCH, { latch.await() }, {}) }
        try {
            clearClipboard()
            drag(view, second.left(), second.right())
            key(view, KeyCode.C, shortcut = true)
            awaitClipboard("second")
        } finally {
            latch.countDown()
        }
    }

    @Test
    fun `disposing the view takes the link tooltip away`() {
        val view = open(TestPdfs.selectionLinks)
        awaitRendered(view, 0)
        awaitCursor(view, sceneRect(view, 0, TestPdfs.selectionLinkRect(0)).centre(), Cursor.HAND)
        val selection = fx { PdfSelection.of(view) }
        assertTrue(fx { selection.tooltipInstalled })
        fx { view.dispose() }
        assertFalse(fx { selection.tooltipInstalled })
    }

    @Test
    fun `the links are read again after a reload`() {
        val file = dir.resolve("links.pdf")
        Files.copy(TestPdfs.selectionLinks, file)
        var reloads = 0
        val view = open(file) { it.onReload += { reloads++ } }
        awaitRendered(view, 0)
        val link = sceneRect(view, 0, TestPdfs.selectionLinkRect(1)).centre()
        awaitCursor(view, link, Cursor.HAND)
        reload(view, file) { reloads }
        // The cursor stays a hand over the reload: move off the link first, so that the wait below sees new links.
        awaitCursor(view, sceneRect(view, 0, PageRect(400.0, 600.0, 10.0, 10.0)).centre(), null)
        // The reload keeps the page's top at the viewport's top: the link moved.
        val moved = sceneRect(view, 0, TestPdfs.selectionLinkRect(1)).centre()
        awaitCursor(view, moved, Cursor.HAND)
        click(view, moved)
        assertEquals(2, fx { view.currentPage.get() })
    }
}
