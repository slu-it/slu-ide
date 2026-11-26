package dev.sluide.pdf.view

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.pdf.HostilePdfs
import dev.sluide.pdf.TestPdfs
import dev.sluide.pdf.engine.PageRect
import dev.sluide.pdf.engine.PageTarget
import dev.sluide.pdf.engine.PageText
import dev.sluide.pdf.engine.PdfDocument
import javafx.event.Event
import javafx.scene.control.Tab
import javafx.scene.control.TabPane
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.scene.input.KeyCode
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The page column of the PDF view on real pages: what is painted, the fit it opens at, lazy rendering while scrolling,
 * scroll keys and focus, hostile pages, and dispose. Needs a scene (painting, focus, key routing); the layout and render
 * scheduling are unit-tested (`PageLayoutTest`, `RenderPlanTest`, `PageImagesTest`).
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
internal class PdfPagesUiTest : PdfViewUiFixture() {
    @Start
    fun start(stage: Stage) = startStage(stage)

    private fun textOf(
        view: PdfFileView,
        page: Int,
    ): PageText {
        var text: PageText? = null
        fx { view.texts.textOf(page) { text = it } }
        await { text != null }
        return checkNotNull(text)
    }

    /** The union of the boxes of [word] in [text]. */
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
    fun `a page shows its text painted on a white page`() {
        val view = open(TestPdfs.text)
        awaitRendered(view, 0)
        val word = sceneRect(view, 0, boxOf(textOf(view, 0), "needle"))
        val empty = sceneRect(view, 0, PageRect(72.0, 250.0, 400.0, 400.0))
        val painted = paint()
        assertTrue(colours(painted, word).any { it.isDark() })
        assertTrue(colours(painted, empty).all { it.isWhite() })
    }

    @Test
    fun `opens at Fit Page with the first page whole in view`() {
        val view = open(TestPdfs.manyPages)
        awaitRendered(view, 0)
        val page = sceneRect(view, 0, PageRect(0.0, 0.0, 595.0, 842.0))
        val pane = fx { view.pages.localToScene(view.pages.layoutBounds) }
        val viewport = fx { view.pageColumn.node.viewportBounds }
        assertTrue(pane.contains(page), "$page in $pane")
        assertEquals(viewport.height - 2 * PageLayout.MARGIN, page.height, 1.0)
        assertEquals(0.0, fx { view.pageColumn.viewTop })
    }

    /** Holds its tasks until [release], then runs them, and every later one, at once on the calling thread. */
    private class HeldExecutor : AbstractExecutorService() {
        private val held = mutableListOf<Runnable>()
        private var released = false

        override fun execute(command: Runnable) {
            if (released) command.run() else held += command
        }

        fun release() {
            released = true
            held.toList().also { held.clear() }.forEach(Runnable::run)
        }

        override fun shutdown() {}

        override fun shutdownNow(): List<Runnable> = emptyList()

        override fun isShutdown() = false

        override fun isTerminated() = false

        override fun awaitTermination(
            timeout: Long,
            unit: TimeUnit,
        ) = false
    }

    @Test
    fun `the focus asked for while loading goes to the pages once they show`() {
        // Another focusable node, which holds the focus until the pages take it.
        val field = TextField()
        lateinit var view: PdfFileView
        // One FX step, no pulse in it: the view joins the scene, the focus is asked for while it loads, and the
        // document opens and shows before the split pane has put the pages into the scene (it does so in a pulse).
        fx {
            val render = HeldExecutor()
            val text = HeldExecutor()
            view = PdfFileView(TestPdfs.text, render, text, runOnFx = Runnable::run).also { views += it }
            (stage.scene.root as StackPane).children.setAll(VBox(field, view.node))
            field.requestFocus()
            view.focus()
            text.release()
            render.release()
        }
        awaitRendered(view, 0)
        assertTrue(fx { !view.pageNotice.shown })
        paint()
        assertSame(view.pages, fx { stage.scene.focusOwner })
    }

    @Test
    fun `a focus that reached the view while loading goes to the pages once they show`() {
        val field = TextField()
        val render = HeldExecutor()
        val text = HeldExecutor()
        lateinit var view: PdfFileView
        fx {
            view = PdfFileView(TestPdfs.text, render, text, runOnFx = Runnable::run).also { views += it }
            (stage.scene.root as StackPane).children.setAll(VBox(field, view.node))
            field.requestFocus()
        }
        // The split is skinned: the request reaches the view while "Loading…" hides the pages.
        paint()
        fx { view.focus() }
        paint()
        assertSame(field, fx { stage.scene.focusOwner })
        // A click inside the view keeps the request.
        fx { Event.fireEvent(view.toolbar.openExternally, mousePress()) }
        fx {
            text.release()
            render.release()
        }
        awaitRendered(view, 0)
        paint()
        assertSame(view.pages, fx { stage.scene.focusOwner })
    }

    @Test
    fun `a PDF tab opened while another tab's editor has the focus takes it`() {
        val editor = TextArea()
        val tabs = TabPane(Tab("A", editor))
        fx { (stage.scene.root as StackPane).children.setAll(tabs) }
        // The tab pane puts its content into the scene once skinned, in a pulse.
        paint()
        fx { editor.requestFocus() }
        await { stage.scene.focusOwner === editor }
        lateinit var view: PdfFileView
        fx {
            view =
                PdfFileView(TestPdfs.text, NamedThreads.single("test-pdf-render"), NamedThreads.single("test-pdf-text"))
                    .also { views += it }
            val tab = Tab("B", view.node)
            tabs.tabs += tab
            tabs.selectionModel.select(tab)
            view.focus()
        }
        awaitRendered(view, 0)
        paint()
        assertSame(view.pages, fx { stage.scene.focusOwner })
    }

    @Test
    fun `a focus asked for while loading is dropped when the user clicks elsewhere meanwhile`() {
        val field = TextField()
        val other = TextField()
        lateinit var view: PdfFileView
        // As above, in one FX step; the user clicks another field before the document shows.
        fx {
            val render = HeldExecutor()
            val text = HeldExecutor()
            view = PdfFileView(TestPdfs.text, render, text, runOnFx = Runnable::run).also { views += it }
            (stage.scene.root as StackPane).children.setAll(VBox(field, other, view.node))
            field.requestFocus()
            view.focus()
            Event.fireEvent(other, mousePress())
            other.requestFocus()
            text.release()
            render.release()
        }
        awaitRendered(view, 0)
        // No signal tells that the focus did not move; the pulse that puts the pages into the scene came before.
        paint()
        assertSame(other, fx { stage.scene.focusOwner })
    }

    @Test
    fun `scrolling to a page makes it the current one and renders it`() {
        val view = open(TestPdfs.manyPages)
        awaitRendered(view, 0)
        fx { view.scrollTo(PageTarget(2)) }
        awaitRendered(view, 2)
        assertEquals(2, fx { view.currentPage.get() })
        assertFalse(fx { view.pageColumn.pageSlots.containsKey(10) })
    }

    @Test
    fun `scroll keys scroll the pages`() {
        val view = open(TestPdfs.manyPages)
        awaitRendered(view, 0)
        val column = view.pageColumn
        fx { Event.fireEvent(view.pages, keyPress(KeyCode.PAGE_DOWN)) }
        val pageDown = fx { column.viewTop }
        assertEquals(fx { column.node.viewportBounds.height } * PAGE_SCROLL_SHARE, pageDown, 1.0)
        fx { Event.fireEvent(view.pages, keyPress(KeyCode.END)) }
        assertEquals(TestPdfs.MANY_PAGES - 1, fx { view.currentPage.get() })
        fx { Event.fireEvent(view.pages, keyPress(KeyCode.HOME)) }
        assertEquals(0.0, fx { column.viewTop })
    }

    @Test
    fun `a rotated page shows landscape with its text painted inside its glyph boxes`() {
        val view = open(TestPdfs.rotated90)
        awaitRendered(view, 0)
        val slot = fx { view.pageColumn.pageSlots.getValue(0) }
        assertTrue(fx { slot.width > slot.height }, fx { "${slot.width} x ${slot.height}" })
        // The text runs down the turned page: its glyph boxes are where the glyphs are painted.
        val word = sceneRect(view, 0, boxOf(textOf(view, 0), "needle"))
        assertTrue(word.height > word.width, word.toString())
        val inside = colours(paint(), word)
        assertTrue(inside.count { it.isDark() } > inside.size / 10, "${inside.count { it.isDark() }} of ${inside.size}")
    }

    @Test
    fun `a huge page renders within the pixel cap`() {
        val view = open(TestPdfs.huge)
        awaitRendered(view, 0)
        fx { view.pageColumn.actualSize() }
        await {
            val image = view.pageColumn.pageSlots[0]?.image
            image != null && image.width * image.height > PdfDocument.MAX_PIXELS / 2
        }
        val image =
            checkNotNull(
                fx {
                    view.pageColumn.pageSlots
                        .getValue(0)
                        .image
                },
            )
        assertTrue(image.width * image.height <= PdfDocument.MAX_PIXELS)
    }

    @Test
    fun `a page that cannot be rendered says so while the others render`() {
        val view = open(HostilePdfs.brokenPages)
        (0..2).forEach { awaitRendered(view, it) }
        val slots = fx { view.pageColumn.pageSlots }
        assertTrue(fx { slots.getValue(2).failure.isVisible })
        assertEquals("Could not show page 3.", fx { slots.getValue(2).failure.text })
        assertNull(fx { slots.getValue(2).image })
        assertFalse(fx { slots.getValue(0).failure.isVisible })
        assertTrue(fx { slots.getValue(1).image } != null)
    }

    @Test
    fun `dispose stops both workers and releases the images`() {
        val view = open(TestPdfs.text)
        awaitRendered(view, 0)
        val slot = fx { view.pageColumn.pageSlots.getValue(0) }
        fx { view.dispose() }
        assertTrue(view.renderWorker.disposed)
        assertTrue(view.textWorker.disposed)
        assertNull(fx { slot.image })
        // A late scroll shows no pages again.
        fx { view.scrollTo(PageTarget(0)) }
        assertTrue(fx { view.pageColumn.pageSlots.isEmpty() })
        assertEquals(IntRange.EMPTY, fx { view.shownPages.value })
        var ran = false
        fx { view.renderWorker.submit(WorkPriority.VISIBLE, { ran = true }, {}) }
        fx { view.textWorker.submit(WorkPriority.TEXT, { ran = true }, {}) }
        // Nothing signals a job that never runs; the threads are idle, so a queued job would have run by then.
        Thread.sleep(200)
        assertFalse(ran)
    }
}
