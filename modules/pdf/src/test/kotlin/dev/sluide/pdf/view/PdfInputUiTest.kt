package dev.sluide.pdf.view

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.pdf.TestPdfs
import dev.sluide.pdf.engine.PageRect
import dev.sluide.pdf.engine.PageTarget
import javafx.event.Event
import javafx.event.EventType
import javafx.geometry.Insets
import javafx.scene.control.Label
import javafx.scene.input.ContextMenuEvent
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.ZoomEvent
import javafx.scene.layout.StackPane
import javafx.scene.text.Font
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Input on the PDF view: which events it takes for itself (and so keeps from the rest of the window), sideways
 * scrolling and zooming on pages wider than the view, the context menu's place, and the hooks before the document
 * opened. Needs a scene (event routing, layout).
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
internal class PdfInputUiTest : PdfViewUiFixture() {
    @Start
    fun start(stage: Stage) = startStage(stage)

    /** Counts the events of [type] that reach the scene, that is, that the view did not consume. */
    private fun <T : Event> reachingScene(type: EventType<T>): () -> Int {
        var count = 0
        fx { stage.scene.addEventHandler(type) { count++ } }
        return { fx { count } }
    }

    /** Opens [TestPdfs.manyPages] at 200 %, where a page is wider than the view. */
    private fun openWide(): PdfFileView {
        val view = open(TestPdfs.manyPages)
        awaitRendered(view, 0)
        fx {
            view.pageColumn.actualSize()
            view.pageColumn.zoomIn()
            view.pageColumn.zoomIn()
            view.pageColumn.zoomIn()
        }
        assertEquals(2.0, fx { view.pageColumn.zoom.scale })
        paint()
        return view
    }

    private fun pane(view: PdfFileView) = fx { view.pages.localToScene(view.pages.layoutBounds) }

    @Test
    fun `the keys the view acts on go no further, a zoom key without Shortcut does nothing`() {
        val view = open(TestPdfs.manyPages)
        awaitRendered(view, 0)
        // The pages hold the focus as a tab's do: nothing else in this scene would give it to them.
        fx { view.focus() }
        await { stage.scene.focusOwner === view.pages }
        val keys = reachingScene(KeyEvent.KEY_PRESSED)
        val fitted = fx { view.pageColumn.zoom.scale }
        fx { Event.fireEvent(view.pages, keyPress(KeyCode.PLUS)) }
        assertEquals(fitted, fx { view.pageColumn.zoom.scale })
        assertEquals(1, keys())
        fx { Event.fireEvent(view.pages, keyPress(KeyCode.G)) }
        assertSame(view.pages, fx { stage.scene.focusOwner })
        assertEquals(2, keys())
        fx { Event.fireEvent(view.pages, keyPress(KeyCode.PLUS, shortcut = true)) }
        fx { Event.fireEvent(view.pages, keyPress(KeyCode.G, shortcut = true)) }
        fx { view.toolbar.pageField.text = "3" }
        fx { Event.fireEvent(view.toolbar.pageField, keyPress(KeyCode.ENTER)) }
        assertEquals(2, keys())
    }

    @Test
    fun `a key the page field does not act on stays in it`() {
        val view = open(TestPdfs.manyPages)
        awaitRendered(view, 0)
        fx {
            view.toolbar.focusPageField()
            view.toolbar.pageField.text = "12"
        }
        fx { Event.fireEvent(view.toolbar.pageField, keyPress(KeyCode.DIGIT3, text = "3")) }
        assertSame(view.toolbar.pageField, fx { stage.scene.focusOwner })
        assertEquals("12", fx { view.toolbar.pageField.text })
        // Shortcut+G again selects the whole number, whatever was selected before.
        fx {
            view.toolbar.pageField.deselect()
            Event.fireEvent(view.pages, keyPress(KeyCode.G, shortcut = true))
        }
        assertEquals("12", fx { view.toolbar.pageField.selectedText })
        // Leaving the field puts the current page back.
        fx { view.pages.requestFocus() }
        assertEquals("1", fx { view.toolbar.pageField.text })
    }

    @Test
    fun `a page number beyond the last shows the last page's number after the jump`() {
        val view = open(TestPdfs.manyPages)
        awaitRendered(view, 0)
        fx {
            view.toolbar.focusPageField()
            view.toolbar.pageField.text = "9999"
        }
        fx { Event.fireEvent(view.toolbar.pageField, keyPress(KeyCode.ENTER)) }
        assertEquals("${TestPdfs.MANY_PAGES}", fx { view.toolbar.pageField.text })
    }

    @Test
    fun `arrows scroll a page wider than the view sideways, as a user scroll`() {
        val view = openWide()
        var scrolls = 0
        fx { view.userScrolled += { scrolls++ } }
        val before = sceneRect(view, 0, PageRect(0.0, 0.0, 10.0, 10.0)).minX
        fx { Event.fireEvent(view.pages, keyPress(KeyCode.RIGHT)) }
        paint()
        assertEquals(before - LINE_SCROLL, sceneRect(view, 0, PageRect(0.0, 0.0, 10.0, 10.0)).minX, 1.0)
        assertTrue(scrolls > 0)
        fx { Event.fireEvent(view.pages, keyPress(KeyCode.LEFT)) }
        paint()
        assertEquals(before, sceneRect(view, 0, PageRect(0.0, 0.0, 10.0, 10.0)).minX, 1.0)
    }

    @Test
    fun `scrollIntoView scrolls sideways to a rect beyond the right edge, not to one in view`() {
        val view = openWide()
        val page = sceneRect(view, 0, PageRect(0.0, 0.0, 10.0, 10.0)).minX
        val inView = checkNotNull(fx { view.pagePointAt(pane(view).centerX, pane(view).centerY) })
        fx { view.scrollIntoView(0, PageRect(inView.x, inView.y, 5.0, 5.0)) }
        paint()
        assertEquals(page, sceneRect(view, 0, PageRect(0.0, 0.0, 10.0, 10.0)).minX, 0.5)
        val rect = PageRect(560.0, 100.0, 20.0, 10.0)
        fx { view.scrollIntoView(0, rect) }
        paint()
        val shown = sceneRect(view, 0, rect)
        val pane = pane(view)
        assertTrue(shown.minX >= pane.minX && shown.maxX <= pane.maxX, "$shown in $pane")
    }

    @Test
    fun `Shortcut+wheel and pinch on a page wider than the view keep the point under the pointer`() {
        val view = openWide()
        val pane = pane(view)
        val x = pane.minX + pane.width * 0.8
        val y = pane.minY + pane.height * 0.4
        val before = checkNotNull(fx { view.pagePointAt(x, y) })
        fx { Event.fireEvent(view.pages, wheel(x, y, 40.0, shortcut = true)) }
        paint()
        val wheeled = checkNotNull(fx { view.pagePointAt(x, y) })
        assertEquals(before.x, wheeled.x, 1.0)
        assertEquals(before.y, wheeled.y, 1.0)
        val zooms = reachingScene(ZoomEvent.ZOOM)
        fx { Event.fireEvent(view.pages, pinch(x, y, 1.5)) }
        paint()
        val pinched = checkNotNull(fx { view.pagePointAt(x, y) })
        assertEquals(before.x, pinched.x, 1.0)
        assertEquals(before.y, pinched.y, 1.0)
        assertEquals(0, zooms())
    }

    @Test
    fun `Shortcut+wheel without a vertical step keeps the zoom and the fit`() {
        val view = open(TestPdfs.manyPages)
        awaitRendered(view, 0)
        val pane = pane(view)
        fx { Event.fireEvent(view.pages, wheel(pane.centerX, pane.centerY, 0.0, shortcut = true)) }
        assertEquals(FitMode.PAGE, fx { view.pageColumn.zoom.fit })
    }

    @Test
    fun `the context menu opens at the pointer, only on a page, and takes the request`() {
        val view = open(TestPdfs.manyPages)
        awaitRendered(view, 0)
        // The places of the pages, measured once the view is laid out (the toolbar's height comes a pulse later).
        paint()
        // The menu has items because the view's selection adds Copy and Select All; without items it never shows.
        val requests = reachingScene(ContextMenuEvent.CONTEXT_MENU_REQUESTED)
        val page = sceneRect(view, 0, PageRect(0.0, 0.0, 595.0, 842.0))
        // The column's left edge, far from the centred page; the rect and the event in one step, so no layout lies between.
        fx {
            val edge = view.pages.localToScene(view.pages.layoutBounds)
            Event.fireEvent(view.pages, contextMenuRequest(edge.minX + 2, page.centerY))
        }
        assertFalse(fx { view.pageContextMenu.isShowing })
        assertEquals(1, requests())
        fx { Event.fireEvent(view.pages, contextMenuRequest(page.centerX, page.centerY)) }
        assertTrue(fx { view.pageContextMenu.isShowing })
        assertEquals(page.centerX, fx { view.pageContextMenu.anchorX }, 1.0)
        assertEquals(page.centerY, fx { view.pageContextMenu.anchorY }, 1.0)
        assertEquals(1, requests())
        fx { view.pageContextMenu.hide() }
    }

    @Test
    fun `before the document opened the hooks find no page, and a scroll before the pages have a size waits for it`() {
        val opening = CountDownLatch(1)
        val render = NamedThreads.single("test-pdf-render").also { it.execute { opening.await() } }
        val view = open(TestPdfs.manyPages, render)
        assertNull(fx { view.pagePointAt(400.0, 300.0) })
        // Nothing to scroll yet: dropped.
        fx { view.scrollTo(PageTarget(7)) }
        // Out of the scene, the pages get no size when they open.
        fx { (stage.scene.root as StackPane).children.clear() }
        opening.countDown()
        await { view.info.value != null }
        fx { view.scrollTo(PageTarget(3)) }
        fx { (stage.scene.root as StackPane).children.setAll(view.node) }
        awaitRendered(view, 3)
        assertEquals(3, fx { view.currentPage.get() })
    }

    @Test
    fun `pages that do not exist have no rect and are not scrolled to`() {
        val view = open(TestPdfs.manyPages)
        awaitRendered(view, 0)
        assertNull(fx { view.pageRectToScene(-1, PageRect(0.0, 0.0, 1.0, 1.0)) })
        fx { view.scrollIntoView(TestPdfs.MANY_PAGES, PageRect(0.0, 400.0, 10.0, 10.0)) }
        assertEquals(0.0, fx { view.pageColumn.viewTop })
        // Highlights of a page without a slot wait for it.
        fx { view.overlays.set(OverlayLayer.SEARCH_HIT, 400, listOf(PageRect(0.0, 0.0, 1.0, 1.0))) }
    }

    @Test
    fun `slots of pages scrolled away leave the column`() {
        val view = open(TestPdfs.manyPages)
        awaitRendered(view, 0)
        val first = fx { view.pageColumn.pageSlots.getValue(0) }
        fx { view.scrollTo(PageTarget(20)) }
        assertNull(fx { first.parent })
    }

    @Test
    fun `Fit Width keeps the place in the middle of the view, unlike Fit Page`() {
        val view = open(TestPdfs.manyPages)
        awaitRendered(view, 0)
        fx { view.scrollTo(PageTarget(5, 300.0)) }
        val pane = pane(view)
        val middle = checkNotNull(fx { view.pagePointAt(pane.centerX, pane.centerY) })
        fx { view.pageColumn.fit(FitMode.WIDTH) }
        paint()
        val after = checkNotNull(fx { view.pagePointAt(pane.centerX, pane.centerY) })
        assertEquals(middle.page, after.page)
        assertEquals(middle.y, after.y, 2.0)
    }

    @Test
    fun `Fit Page shows the current page whole`() {
        val view = open(TestPdfs.manyPages)
        awaitRendered(view, 0)
        fx {
            view.pageColumn.actualSize()
            view.scrollTo(PageTarget(5, 300.0))
        }
        assertEquals(5, fx { view.currentPage.get() })
        fx { view.pageColumn.fit(FitMode.PAGE) }
        paint()
        val page = sceneRect(view, 5, PageRect(0.0, 0.0, 595.0, 842.0))
        val pane = pane(view)
        assertTrue(pane.contains(page), "$page in $pane")
        assertEquals(pane.minY + PageLayout.MARGIN, page.minY, 1.0)
    }

    @Test
    fun `a resize at a zoom of its own keeps the point at the top of the view`() {
        val view = open(TestPdfs.manyPages)
        awaitRendered(view, 0)
        fx {
            view.pageColumn.actualSize()
            view.scrollTo(PageTarget(3, 100.0))
        }
        val before = checkNotNull(fx { view.pageColumn.topTarget() })
        fx { stage.height = stage.height - 100 }
        paint()
        val after = checkNotNull(fx { view.pageColumn.topTarget() })
        fx { stage.height = stage.height + 100 }
        assertEquals(before.page, after.page)
        assertEquals(checkNotNull(before.y), checkNotNull(after.y), 1.0)
    }

    @Test
    fun `the zoom level is as wide as the widest level in its font, with its padding`() {
        val view = open(TestPdfs.text)
        val level =
            fx {
                view.toolbar.node.trailing[1]
                    .lookup(".zoom-level") as Label
            }
        // In one FX step: a CSS pass would set the stylesheet's padding and font again.
        val (small, large, padded) =
            fx {
                level.padding = Insets.EMPTY
                val small = level.minWidth
                level.font = Font(level.font.family, 40.0)
                val large = level.minWidth
                level.padding = Insets(0.0, 0.0, 0.0, 50.0)
                Triple(small, large, level.minWidth)
            }
        assertTrue(large > small * 2, "$small, $large")
        assertEquals(large + 50, padded, 1.0)
    }
}
