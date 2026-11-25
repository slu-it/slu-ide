package dev.sluide.pdf.view

import dev.sluide.pdf.TestPdfs
import dev.sluide.pdf.engine.PageRect
import dev.sluide.pdf.engine.PageTarget
import dev.sluide.pdf.engine.PageText
import javafx.event.Event
import javafx.scene.Cursor
import javafx.scene.control.Label
import javafx.scene.control.MenuItem
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One wiring test per hook the later PDF packages build on (selection and links, side panel, search): each fails if
 * the view stops connecting the hook. Needs a scene (event routing, painting, scene coordinates).
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
internal class PdfHooksUiTest : PdfViewUiFixture() {
    @Start
    fun start(stage: Stage) = startStage(stage)

    private fun textOf(view: PdfFileView): PageText {
        var text: PageText? = null
        fx { view.texts.textOf(0) { text = it } }
        await { text != null }
        return checkNotNull(text)
    }

    private fun boxOf(
        text: PageText,
        word: String,
    ): PageRect {
        val start = text.text.indexOf(word)
        val boxes = (start until start + word.length).mapNotNull { text.boxes[it] }
        val left = boxes.minOf { it.x }
        val top = boxes.minOf { it.y }
        return PageRect(left, top, boxes.maxOf { it.x + it.width } - left, boxes.maxOf { it.y + it.height } - top)
    }

    @Test
    fun `info gives the page count, sizes and outline of the open document`() {
        val view = open(TestPdfs.outline)
        await { view.info.value != null }
        val info = checkNotNull(fx { view.info.value })
        assertEquals(info.pageSizes.size, info.pageCount)
        assertTrue(info.pageCount > 1)
        assertTrue(info.outline.isNotEmpty())
    }

    @Test
    fun `the texts come from the text worker's document`() {
        val view = open(TestPdfs.text)
        assertTrue(textOf(view).text.startsWith(TestPdfs.FIRST_LINE))
    }

    @Test
    fun `scrollTo moves the pages without calling userScrolled, a wheel scroll calls it`() {
        val view = open(TestPdfs.manyPages)
        awaitRendered(view, 0)
        var scrolls = 0
        fx { view.userScrolled += { scrolls++ } }
        fx { view.scrollTo(PageTarget(3)) }
        assertEquals(3, fx { view.currentPage.get() })
        assertEquals(0, scrolls)
        val top = fx { view.pageColumn.viewTop }
        val slot = fx { view.pageColumn.pageSlots.getValue(3) }
        val centre = fx { slot.localToScene(slot.layoutBounds) }
        fx { Event.fireEvent(slot, wheel(centre.centerX, centre.centerY, -40.0)) }
        assertTrue(scrolls > 0)
        assertTrue(fx { view.pageColumn.viewTop } > top)
    }

    @Test
    fun `pagePointAt and pageRectToScene map the same points, and nothing outside the pages`() {
        val view = open(TestPdfs.manyPages)
        awaitRendered(view, 0)
        val bounds = sceneRect(view, 1, PageRect(100.0, 200.0, 50.0, 40.0))
        fx { view.scrollTo(PageTarget(1, 150.0)) }
        val moved = sceneRect(view, 1, PageRect(100.0, 200.0, 50.0, 40.0))
        assertNotEquals(bounds.minY, moved.minY)
        val point = checkNotNull(fx { view.pagePointAt(moved.centerX, moved.centerY) })
        assertEquals(1, point.page)
        assertEquals(125.0, point.x, 0.01)
        assertEquals(220.0, point.y, 0.01)
        val page = sceneRect(view, 1, PageRect(0.0, 0.0, 595.0, 842.0))
        assertNull(fx { view.pagePointAt(page.minX - 2, page.centerY) })
        assertNull(fx { view.pagePointAt(page.maxX + 2, page.centerY) })
        assertNull(fx { view.pagePointAt(page.centerX, page.minY - 2) })
        assertNull(fx { view.pageRectToScene(TestPdfs.MANY_PAGES, PageRect(0.0, 0.0, 1.0, 1.0)) })
    }

    @Test
    fun `scrollIntoView centres a rect that is off-screen and leaves one in view where it is`() {
        val view = open(TestPdfs.manyPages)
        awaitRendered(view, 0)
        var scrolls = 0
        fx { view.userScrolled += { scrolls++ } }
        val rect = PageRect(50.0, 400.0, 100.0, 20.0)
        fx { view.scrollIntoView(10, rect) }
        val shown = sceneRect(view, 10, rect)
        val pane = fx { view.pages.localToScene(view.pages.layoutBounds) }
        assertEquals(pane.centerY, shown.centerY, 2.0)
        val top = fx { view.pageColumn.viewTop }
        fx { view.scrollIntoView(10, rect.copy(y = 420.0)) }
        assertEquals(top, fx { view.pageColumn.viewTop })
        assertEquals(0, scrolls)
    }

    @Test
    fun `overlays highlight a word's boxes at two zoom levels`() {
        val view = open(TestPdfs.text)
        awaitRendered(view, 0)
        val text = textOf(view)
        val needle = boxOf(text, "needle")
        val omega = boxOf(text, "omega")
        assertTrue(colours(paint(), sceneRect(view, 0, needle)).any { it.isWhite() })
        fx { view.overlays.set(OverlayLayer.SELECTION, 0, listOf(needle)) }
        for (zoom in 0..1) {
            if (zoom == 1) {
                fx { view.pageColumn.zoomIn() }
                fx { view.scrollIntoView(0, needle) }
                awaitRendered(view, 0)
            }
            val painted = paint()
            assertTrue(colours(painted, sceneRect(view, 0, needle)).none { it.isWhite() }, "zoom $zoom")
            assertTrue(colours(painted, sceneRect(view, 0, omega)).any { it.isWhite() }, "zoom $zoom")
        }
    }

    @Test
    fun `the context menu of the pages shows on a page only while it has items`() {
        val view = open(TestPdfs.text)
        awaitRendered(view, 0)
        val page = sceneRect(view, 0, PageRect(100.0, 100.0, 10.0, 10.0))
        fx { Event.fireEvent(view.pages, contextMenuRequest(page.centerX, page.centerY)) }
        assertFalse(fx { view.pageContextMenu.isShowing })
        fx { view.pageContextMenu.items += MenuItem("Copy") }
        fx { Event.fireEvent(view.pages, contextMenuRequest(page.centerX, page.centerY)) }
        assertTrue(fx { view.pageContextMenu.isShowing })
        fx { view.pageContextMenu.hide() }
    }

    @Test
    fun `pageCursor is the cursor over the pages`() {
        val view = open(TestPdfs.text)
        awaitRendered(view, 0)
        fx { view.pageCursor = Cursor.TEXT }
        assertEquals(
            Cursor.TEXT,
            fx {
                view.pageColumn.pageSlots
                    .getValue(0)
                    .parent.cursor
            },
        )
    }

    @Test
    fun `the side panels show with their first width, hide, and come back at the width they had`() {
        val view = open(TestPdfs.text)
        fx {
            view.split.left.children += Label("Outline")
            view.split.right.children += Label("Search")
            view.split.showLeft(true)
        }
        await { view.split.left.width in 210.0..222.0 }
        assertTrue(fx { view.split.leftShown.get() })
        fx { view.split.showRight(true) }
        await { view.split.right.width in 270.0..282.0 }
        fx { view.split.showLeft(false) }
        assertFalse(
            fx {
                view.split.node.items
                    .contains(view.split.left)
            },
        )
        assertFalse(fx { view.split.leftShown.get() })
        fx { view.split.showLeft(true) }
        await { view.split.left.width in 210.0..222.0 }
        assertTrue(fx { view.split.rightShown.get() })
    }

    @Test
    fun `onDispose listeners are called once, when the view is disposed`() {
        val view = open(TestPdfs.text)
        var calls = 0
        fx { view.onDispose += { calls++ } }
        fx { view.dispose() }
        fx { view.dispose() }
        assertEquals(1, calls)
    }
}
