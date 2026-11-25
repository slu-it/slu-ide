package dev.sluide.pdf.view

import dev.sluide.fileviewers.FileViewHost
import dev.sluide.fileviewers.zoom.ZoomSteps
import dev.sluide.pdf.TestPdfs
import dev.sluide.pdf.engine.PageTarget
import javafx.event.Event
import javafx.scene.input.KeyCode
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.net.URI
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The toolbar of the PDF view: the page field, Open in Default App and the zoom group with its keys, Shortcut+wheel and
 * pinch. Needs a scene (focus, key routing, layout); the page field's parsing, the zoom steps and fits are unit-tested
 * (`PageFieldTest`, `ZoomModelTest`, `PageLayoutTest`).
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
internal class PdfToolbarUiTest : PdfViewUiFixture() {
    @Start
    fun start(stage: Stage) = startStage(stage)

    private class FakeHost : FileViewHost {
        val webPages = mutableListOf<URI>()

        override fun openFile(file: Path) = false

        override fun openWebPage(uri: URI) {
            webPages += uri
        }
    }

    private fun press(
        view: PdfFileView,
        code: KeyCode,
        shortcut: Boolean = false,
    ) = fx { Event.fireEvent(view.toolbar.pageField, keyPress(code, shortcut)) }

    private fun zoomKey(
        view: PdfFileView,
        code: KeyCode,
    ) = fx { Event.fireEvent(view.pages, keyPress(code, shortcut = true)) }

    private fun scale(view: PdfFileView) = fx { view.pageColumn.zoom.scale }

    @Test
    fun `the toolbar holds the slots of other features, and none of its controls takes the focus by Tab`() {
        val view = open(TestPdfs.text)
        val toolbar = view.toolbar
        assertTrue(fx { toolbar.node.leading.containsAll(listOf(toolbar.sidePanelSlot, toolbar.searchSlot)) })
        assertEquals(
            emptyList(),
            fx {
                listOf(toolbar.pageField, toolbar.openExternally) +
                    (view.toolbar.node.trailing[1] as javafx.scene.Parent).childrenUnmodifiable
            }.filter { it.isFocusTraversable },
        )
    }

    @Test
    fun `the page field shows the current page and the page count`() {
        val view = open(TestPdfs.manyPages)
        awaitRendered(view, 0)
        assertEquals("1", fx { view.toolbar.pageField.text })
        assertEquals("/ ${TestPdfs.MANY_PAGES}", fx { view.toolbar.pageCount.text })
        fx { view.scrollTo(PageTarget(2)) }
        assertEquals("3", fx { view.toolbar.pageField.text })
    }

    @Test
    fun `Shortcut+G selects the page number, Enter jumps to the page typed, clamped, and gives the focus back`() {
        val view = open(TestPdfs.manyPages)
        awaitRendered(view, 0)
        fx { Event.fireEvent(view.pages, keyPress(KeyCode.G, shortcut = true)) }
        assertSame(view.toolbar.pageField, fx { stage.scene.focusOwner })
        assertEquals("1", fx { view.toolbar.pageField.selectedText })
        fx { view.toolbar.pageField.text = "42" }
        press(view, KeyCode.ENTER)
        assertEquals(41, fx { view.currentPage.get() })
        assertEquals("42", fx { view.toolbar.pageField.text })
        assertSame(view.pages, fx { stage.scene.focusOwner })
        fx {
            view.toolbar.focusPageField()
            view.toolbar.pageField.text = "9999"
        }
        press(view, KeyCode.ENTER)
        assertEquals(TestPdfs.MANY_PAGES - 1, fx { view.currentPage.get() })
    }

    @Test
    fun `Escape and a text that is no number restore the page number and give the focus back`() {
        val view = open(TestPdfs.manyPages)
        awaitRendered(view, 0)
        fx {
            view.toolbar.focusPageField()
            view.toolbar.pageField.text = "abc"
        }
        press(view, KeyCode.ENTER)
        assertEquals("1", fx { view.toolbar.pageField.text })
        assertSame(view.pages, fx { stage.scene.focusOwner })
        fx {
            view.toolbar.focusPageField()
            view.toolbar.pageField.text = "7"
        }
        press(view, KeyCode.ESCAPE)
        assertEquals("1", fx { view.toolbar.pageField.text })
        assertEquals(0, fx { view.currentPage.get() })
        assertSame(view.pages, fx { stage.scene.focusOwner })
    }

    @Test
    fun `scroll keys in the page field do not scroll the pages`() {
        val view = open(TestPdfs.manyPages)
        awaitRendered(view, 0)
        fx { view.toolbar.focusPageField() }
        press(view, KeyCode.SPACE)
        press(view, KeyCode.PAGE_DOWN)
        assertEquals(0.0, fx { view.pageColumn.viewTop })
    }

    @Test
    fun `Open in Default App opens the file through the host`() {
        val host = FakeHost()
        val view = open(TestPdfs.text) { it.host = host }
        fx { view.toolbar.openExternally.fire() }
        assertEquals(listOf(TestPdfs.text.toUri()), host.webPages)
    }

    @Test
    fun `the zoom keys zoom and the fit toggles mirror the fit mode`() {
        val view = open(TestPdfs.text)
        awaitRendered(view, 0)
        val zoom = view.toolbar.node.trailing[1]
        val fitted = scale(view)
        assertTrue(fx { view.pageColumn.zoom.fit == FitMode.PAGE })
        assertEquals(ZoomSteps.label(fitted), fx { (zoom.lookup(".zoom-level") as javafx.scene.control.Label).text })
        zoomKey(view, KeyCode.PLUS)
        assertEquals(ZoomSteps.zoomIn(fitted), scale(view))
        assertNull(fx { view.pageColumn.zoom.fit })
        assertEquals(
            ZoomSteps.label(ZoomSteps.zoomIn(fitted)),
            fx {
                (zoom.lookup(".zoom-level") as javafx.scene.control.Label).text
            },
        )
        zoomKey(view, KeyCode.DIGIT0)
        assertEquals(1.0, scale(view))
        zoomKey(view, KeyCode.MINUS)
        assertEquals(0.75, scale(view))
        zoomKey(view, KeyCode.DIGIT9)
        assertEquals(fitted, scale(view), 1e-9)
        assertEquals(FitMode.PAGE, fx { view.pageColumn.zoom.fit })
    }

    @Test
    fun `the zoom buttons zoom and Fit Width fits the page's width`() {
        val view = open(TestPdfs.text)
        awaitRendered(view, 0)
        val buttons = fx { (view.toolbar.node.trailing[1] as javafx.scene.Parent).childrenUnmodifiable }
        val (zoomOut, _, zoomIn, actualSize) = buttons
        val fitWidth = buttons[4] as javafx.scene.control.ToggleButton
        val fitPage = buttons[5] as javafx.scene.control.ToggleButton
        assertTrue(fx { fitPage.isSelected })
        fx { (actualSize as javafx.scene.control.Button).fire() }
        assertEquals(1.0, scale(view))
        assertFalse(fx { fitPage.isSelected })
        fx { (zoomIn as javafx.scene.control.Button).fire() }
        assertEquals(1.25, scale(view))
        fx { (zoomOut as javafx.scene.control.Button).fire() }
        assertEquals(1.0, scale(view))
        fx { fitWidth.fire() }
        assertTrue(fx { fitWidth.isSelected && !fitPage.isSelected })
        // The scroll bar shows after the fit, which then fits again to the narrower viewport.
        await {
            val page =
                view.pageColumn.pageSlots
                    .getValue(0)
                    .width
            abs(view.pageColumn.node.viewportBounds.width - 2 * PageLayout.MARGIN - page) < 1
        }
        fx { fitWidth.fire() }
        assertTrue(fx { fitWidth.isSelected })
        fx { fitPage.fire() }
        assertTrue(fx { fitPage.isSelected && !fitWidth.isSelected })
    }

    @Test
    fun `a resize fits again while fitted, keeps any other zoom and the page, and is no user scroll`() {
        val view = open(TestPdfs.manyPages)
        awaitRendered(view, 0)
        fx { view.scrollTo(PageTarget(3)) }
        var scrolls = 0
        fx { view.userScrolled += { scrolls++ } }
        val fitted = scale(view)
        fx { stage.height = stage.height - 100 }
        await { view.pageColumn.zoom.scale < fitted }
        paint()
        assertEquals(3, fx { view.currentPage.get() })
        fx { view.pageColumn.actualSize() }
        fx { stage.height = stage.height + 100 }
        // No signal tells that a resize changed nothing; a snapshot runs the layout pass of the resize.
        paint()
        assertEquals(1.0, scale(view))
        assertEquals(3, fx { view.currentPage.get() })
        assertEquals(0, scrolls)
    }

    @Test
    fun `Shortcut+wheel and pinching zoom around the pointer without scrolling the pages`() {
        val view = open(TestPdfs.manyPages)
        awaitRendered(view, 0)
        var scrolls = 0
        fx { view.userScrolled += { scrolls++ } }
        // The toolbar's height is known a pulse after the first fit, which then fits again; a snapshot runs that pulse.
        paint()
        val slot = fx { view.pageColumn.pageSlots.getValue(0) }
        val bounds = fx { slot.localToScene(slot.layoutBounds) }
        // The page is narrower than the viewport, so it stays centred: only a point on its middle keeps its x.
        val x = bounds.centerX
        val y = bounds.minY + bounds.height * 0.6
        val before = checkNotNull(fx { view.pagePointAt(x, y) })
        val fitted = scale(view)
        fx { Event.fireEvent(slot, wheel(x, y, 40.0, shortcut = true)) }
        assertEquals(fitted * 1.1, scale(view), 1e-9)
        assertNull(fx { view.pageColumn.zoom.fit })
        // The scroll pane moves the column to the new scroll position in its next layout pass, which a snapshot runs.
        paint()
        val after = checkNotNull(fx { view.pagePointAt(x, y) })
        assertEquals(before.page, after.page)
        assertEquals(before.x, after.x, 1.0)
        assertEquals(before.y, after.y, 1.0)
        fx { Event.fireEvent(slot, pinch(x, y, 1.5)) }
        assertEquals(fitted * 1.1 * 1.5, scale(view), 1e-9)
        assertEquals(0, scrolls)
    }
}
