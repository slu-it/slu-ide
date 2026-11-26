package dev.sluide.pdf.view.side

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.pdf.PanelPdfs
import dev.sluide.pdf.TestPdfs
import dev.sluide.pdf.engine.OutlineItem
import dev.sluide.pdf.engine.PageTarget
import dev.sluide.pdf.view.PdfFileView
import dev.sluide.pdf.view.PdfViewUiFixture
import dev.sluide.pdf.view.WorkPriority
import dev.sluide.pdf.view.keyPress
import dev.sluide.pdf.view.wheel
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.control.TabPane
import javafx.scene.control.ToggleButton
import javafx.scene.control.TreeCell
import javafx.scene.control.TreeItem
import javafx.scene.control.TreeView
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.image.PixelFormat
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.scene.text.Text
import javafx.stage.Stage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The side panel of the PDF view: what needs a scene (the panel's controls, painting, the focus, the hooks of the view
 * it follows and jumps through). Which item follows a page is [OutlineFollowTest]'s, the thumbnails' book-keeping
 * [ThumbnailCacheTest]'s.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
internal class PdfSidePanelUiTest : PdfViewUiFixture() {
    @TempDir
    lateinit var dir: Path

    @Start
    fun start(stage: Stage) = startStage(stage)

    private fun toggle(view: PdfFileView) =
        fx {
            view.toolbar.sidePanelSlot.children
                .firstOrNull() as? ToggleButton
        }.let { assertNotNull(it, "no side panel toggle") }

    private fun tabs(view: PdfFileView) =
        fx {
            view.split.left.children
                .firstOrNull() as? TabPane
        }.let { assertNotNull(it, "no side panel") }

    private fun selectedTab(view: PdfFileView) = fx { tabs(view).selectionModel.selectedItem?.text }

    @Suppress("UNCHECKED_CAST")
    private fun tree(view: PdfFileView) =
        fx { tabs(view).tabs[0].content.lookup(".pdf-outline") as TreeView<OutlineItem> }

    @Suppress("UNCHECKED_CAST")
    private fun list(view: PdfFileView) = fx { tabs(view).tabs[1].content as ListView<Int> }

    private fun selectedTitle(view: PdfFileView) =
        fx {
            tree(view)
                .selectionModel.selectedItem
                ?.value
                ?.title
        }

    /** The laid-out cell of the item titled [title]. */
    @Suppress("UNCHECKED_CAST")
    private fun cell(
        view: PdfFileView,
        title: String,
    ): TreeCell<OutlineItem> =
        fx {
            val tree = tree(view)
            tree.applyCss()
            tree.layout()
            tree
                .lookupAll(".tree-cell")
                .map { it as TreeCell<OutlineItem> }
                .firstOrNull { !it.isEmpty && it.isVisible && it.item?.title == title }
        }.let { assertNotNull(it, "no cell for $title") }

    /** The laid-out cell of thumbnail [page], `null` while none shows it. */
    @Suppress("UNCHECKED_CAST")
    private fun thumbnail(
        view: PdfFileView,
        page: Int,
    ): ListCell<Int>? =
        fx {
            val list = list(view)
            list.applyCss()
            list.layout()
            list
                .lookupAll(".list-cell")
                .map { it as ListCell<Int> }
                .firstOrNull { !it.isEmpty && it.isVisible && it.index == page }
        }

    private fun imageOf(cell: ListCell<Int>): Image? = fx { imageOnFx(cell) }

    private fun imageOnFx(cell: ListCell<Int>): Image? = (cell.lookup(".pdf-thumbnail-image") as? ImageView)?.image

    private fun pixels(image: Image): IntArray =
        fx {
            val width = image.width.toInt()
            val height = image.height.toInt()
            IntArray(width * height).also {
                image.pixelReader.getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), it, 0, width)
            }
        }

    private fun titles(items: List<TreeItem<OutlineItem>>): List<String> = items.map { it.value.title }

    private fun click(
        button: MouseButton = MouseButton.PRIMARY,
        count: Int = 1,
    ) = MouseEvent(
        MouseEvent.MOUSE_CLICKED,
        5.0,
        5.0,
        5.0,
        5.0,
        button,
        count,
        false,
        false,
        false,
        false,
        button == MouseButton.PRIMARY,
        false,
        button == MouseButton.SECONDARY,
        false,
        false,
        true,
        null,
    )

    /** What the FX thread threw where JavaFX catches it (listeners): each test ends with none. */
    private val fxErrors = mutableListOf<Throwable>()
    private var fxHandler: Thread.UncaughtExceptionHandler? = null

    @BeforeEach
    fun catchFxErrors() =
        fx {
            val thread = Thread.currentThread()
            fxHandler = thread.uncaughtExceptionHandler
            thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error -> fxErrors += error }
        }

    @AfterEach
    fun noFxErrors() {
        fx { Thread.currentThread().uncaughtExceptionHandler = fxHandler }
        assertEquals(emptyList(), fxErrors.map { it.toString() })
    }

    /** The visible cells of [view]'s outline without an item. */
    private fun emptyCell(view: PdfFileView): Node =
        fx {
            val tree = tree(view)
            tree.applyCss()
            tree.layout()
            tree.lookupAll(".tree-cell").first { (it as TreeCell<*>).isEmpty && it.isVisible }
        }

    private fun placeholder(view: PdfFileView) =
        fx { tabs(view).tabs[0].content.lookup(".pdf-outline-placeholder") as Label }

    private fun focusOwner(): Node? = fx { stage.scene.focusOwner }

    /** Wheel-scrolls the pages of [view] down until [page] is the current page. */
    private fun wheelTo(
        view: PdfFileView,
        page: Int,
    ) {
        repeat(200) {
            if (fx { view.currentPage.get() } >= page) return
            fx {
                val slot = view.pageColumn.pageSlots.getValue(view.currentPage.get())
                val centre = slot.localToScene(slot.layoutBounds)
                Event.fireEvent(slot, wheel(centre.centerX, centre.centerY, -40.0))
            }
        }
    }

    /** The thumbnail renders queued on the render worker of [view]. */
    private fun queuedThumbnails(view: PdfFileView): Int = fx { queuedThumbnailsOnFx(view) }

    /** The pages of the thumbnail cells laid out and visible. */
    private fun thumbnailsShown(view: PdfFileView): Set<Int> =
        fx {
            list(view)
                .lookupAll(".list-cell")
                .map { it as ListCell<*> }
                .filter { !it.isEmpty && it.isVisible }
                .map { it.index }
                .toSet()
        }

    /** The renders other than thumbnails queued on the render worker of [view]. */
    private fun queuedPageRenders(view: PdfFileView): Int =
        fx {
            var queued = 0
            view.renderWorker.cancelQueued { job ->
                if (job.priority != WorkPriority.THUMBNAIL) queued++
                false
            }
            queued
        }

    private fun queuedThumbnailsOnFx(view: PdfFileView): Int {
        var queued = 0
        view.renderWorker.cancelQueued { job ->
            if (job.priority == WorkPriority.THUMBNAIL) queued++
            false
        }
        return queued
    }

    @Test
    fun `a PDF with an outline opens the panel on Outline, its items expanded as the PDF marks them`() {
        val view = open(PanelPdfs.sideOutline)
        awaitRendered(view, 0)
        assertTrue(fx { view.split.leftShown.get() })
        val toggle = toggle(view)
        assertTrue(fx { toggle.isSelected })
        assertEquals("Side Panel", fx { toggle.tooltip?.text })
        assertFalse(fx { toggle.isFocusTraversable })
        assertEquals("Outline", selectedTab(view))
        assertEquals(listOf("Outline", "Thumbnails"), fx { tabs(view).tabs.map { it.text } })
        assertEquals(TabPane.TabClosingPolicy.UNAVAILABLE, fx { tabs(view).tabClosingPolicy })
        val roots = fx { tree(view).root.children.toList() }
        assertEquals(listOf("1 Intro", "2 Body", "3 No destination", "4 Late", "5 End"), titles(roots))
        assertEquals(listOf(true, false, false, true, false), fx { roots.map { it.isExpanded } })
        assertEquals(listOf("1.1 Start", "1.2 More"), titles(fx { roots[0].children.toList() }))
        assertFalse(fx { tree(view).isShowRoot })
        // Page 1 is the current page: its last item in document order, a child on its parent's page.
        assertEquals("1.1 Start", selectedTitle(view))
        assertFalse(
            fx {
                tabs(view)
                    .tabs[0]
                    .content
                    .lookup(".pdf-outline-placeholder")
                    .isVisible
            },
        )
    }

    @Test
    fun `a click on an item scrolls to its page and gives the focus to the pages`() {
        val view = open(PanelPdfs.sideOutline)
        awaitRendered(view, 0)
        fx { tree(view).requestFocus() }
        fx { Event.fireEvent(cell(view, "1.2 More"), click()) }
        assertEquals(1, fx { view.currentPage.get() })
        assertEquals("1.2 More", selectedTitle(view))
        assertSame(view.pages, focusOwner())
    }

    @Test
    fun `Enter on the selected item jumps, the arrow keys only move the selection`() {
        val view = open(PanelPdfs.sideOutline)
        awaitRendered(view, 0)
        assertEquals(5, fx { tree(view).root.children.size })
        fx {
            val tree = tree(view)
            tree.requestFocus()
            tree.selectionModel.select(tree.root.children[3])
            Event.fireEvent(tree, keyPress(KeyCode.DOWN))
        }
        assertEquals("4.1 Deep", selectedTitle(view))
        assertEquals(0, fx { view.currentPage.get() })
        assertSame(tree(view), focusOwner())
        fx { Event.fireEvent(tree(view), keyPress(KeyCode.ENTER)) }
        assertEquals(5, fx { view.currentPage.get() })
        assertSame(view.pages, focusOwner())
    }

    @Test
    fun `scrolling the pages moves the selection to the item of the current page, never into a collapsed one`() {
        val view = open(PanelPdfs.sideOutline)
        awaitRendered(view, 0)
        wheelTo(view, 1)
        assertEquals("1.2 More", selectedTitle(view))
        // Page 4 (0-based 3) is "2.1 Hidden"'s, under the collapsed "2 Body".
        wheelTo(view, 3)
        assertEquals(3, fx { view.currentPage.get() })
        assertEquals("2 Body", selectedTitle(view))
        assertFalse(fx { tree(view).root.children[1].isExpanded })
    }

    @Test
    fun `a jump keeps the picked item selected until the user scrolls or the pages move away from its page`() {
        val view = open(PanelPdfs.sideOutline)
        awaitRendered(view, 0)
        fx { view.scrollTo(PageTarget(4)) }
        assertEquals("4 Late", selectedTitle(view))
        // Following would pick "1.1 Start", the last item of page 1.
        fx { Event.fireEvent(cell(view, "1 Intro"), click()) }
        assertEquals(0, fx { view.currentPage.get() })
        assertEquals("1 Intro", selectedTitle(view))
        fx {
            val slot = view.pageColumn.pageSlots.getValue(0)
            val centre = slot.localToScene(slot.layoutBounds)
            Event.fireEvent(slot, wheel(centre.centerX, centre.centerY, -10.0))
        }
        assertEquals(0, fx { view.currentPage.get() })
        assertEquals("1.1 Start", selectedTitle(view))
        // Picked again, then the pages move by another way than the user's scroll (the page field, a link, a hit).
        fx { Event.fireEvent(cell(view, "1 Intro"), click()) }
        assertEquals("1 Intro", selectedTitle(view))
        fx { view.scrollTo(PageTarget(6)) }
        assertEquals("5 End", selectedTitle(view))
    }

    @Test
    fun `an item without a destination is dimmed and does nothing on a click or Enter`() {
        val view = open(PanelPdfs.sideOutline)
        awaitRendered(view, 0)
        fx { view.scrollTo(PageTarget(2)) }
        val dimmed = cell(view, "3 No destination")
        assertTrue(fx { "pdf-outline-dimmed" in dimmed.styleClass })
        assertEquals(0.5, fx { dimmed.opacity }, 0.01)
        assertEquals(1.0, fx { cell(view, "2 Body").opacity })
        fx { tree(view).requestFocus() }
        fx { Event.fireEvent(dimmed, click()) }
        assertEquals("3 No destination", selectedTitle(view))
        fx { Event.fireEvent(tree(view), keyPress(KeyCode.ENTER)) }
        assertEquals(2, fx { view.currentPage.get() })
        assertSame(tree(view), focusOwner())
        // Collapsed, "1 Intro" moves the items below it up into cells that showed others.
        fx { tree(view).root.children[0].isExpanded = false }
        cell(view, "4.1 Deep")
        val dimming =
            fx {
                tree(view)
                    .lookupAll(".tree-cell")
                    .map { it as TreeCell<*> }
                    .filter { !it.isEmpty && it.isVisible }
                    .associate { (it.item as OutlineItem).title to ("pdf-outline-dimmed" in it.styleClass) }
            }
        assertEquals(mapOf("3 No destination" to true), dimming.filterValues { it })
        assertTrue("4.1 Deep" in dimming, dimming.toString())
    }

    @Test
    fun `only a single primary click on an item jumps, not another button, a double click, an arrow or empty space`() {
        val view = open(PanelPdfs.sideOutline)
        awaitRendered(view, 0)
        val more = cell(view, "1.2 More")
        fx { Event.fireEvent(more, click(MouseButton.SECONDARY)) }
        fx { Event.fireEvent(more, click(count = 2)) }
        val late = cell(view, "4 Late")
        fx { Event.fireEvent(late.lookup(".tree-disclosure-node"), click()) }
        val empty = emptyCell(view)
        fx { Event.fireEvent(empty, click()) }
        fx { Event.fireEvent(tree(view), click()) }
        assertEquals(0, fx { view.currentPage.get() })
        // A click on the title inside an item's cell is one on the item.
        fx { Event.fireEvent(more.lookup(".text"), click()) }
        assertEquals(1, fx { view.currentPage.get() })
    }

    @Test
    fun `Enter in the lists is consumed, other keys do not jump, and Enter without a selection does nothing`() {
        val view = open(PanelPdfs.sideOutline)
        awaitRendered(view, 0)
        var enters = 0
        fx { view.node.addEventHandler(KeyEvent.KEY_PRESSED) { if (it.code == KeyCode.ENTER) enters++ } }
        fx {
            val tree = tree(view)
            tree.selectionModel.select(tree.root.children[3])
            Event.fireEvent(tree, keyPress(KeyCode.X, text = "x"))
        }
        assertEquals(0, fx { view.currentPage.get() })
        fx { Event.fireEvent(tree(view), keyPress(KeyCode.ENTER)) }
        assertEquals(4, fx { view.currentPage.get() })
        fx {
            tabs(view).selectionModel.select(1)
            list(view).selectionModel.clearSelection()
            Event.fireEvent(list(view), keyPress(KeyCode.ENTER))
        }
        assertEquals(4, fx { view.currentPage.get() })
        fx {
            list(view).selectionModel.select(2)
            Event.fireEvent(list(view), keyPress(KeyCode.X, text = "x"))
        }
        assertEquals(4, fx { view.currentPage.get() })
        fx {
            list(view).selectionModel.select(0)
            Event.fireEvent(list(view), keyPress(KeyCode.ENTER))
        }
        assertEquals(0, fx { view.currentPage.get() })
        assertEquals(0, enters)
    }

    @Test
    fun `the placeholder does not show while no document is open`() {
        val view = open(TestPdfs.notPdf)
        await {
            view.pageNotice.text.text
                .startsWith("Not a valid PDF")
        }
        assertFalse(fx { placeholder(view).isVisible })
    }

    @Test
    fun `a PDF without an outline opens with the panel closed, Thumbnails selected and a placeholder on Outline`() {
        val view = open(PanelPdfs.sideNoOutline)
        awaitRendered(view, 0)
        assertFalse(fx { view.split.leftShown.get() })
        assertFalse(fx { toggle(view).isSelected })
        assertEquals("Thumbnails", selectedTab(view))
        val placeholder = placeholder(view)
        assertEquals("This PDF has no outline.", fx { placeholder.text })
        assertTrue(fx { placeholder.isVisible })
        assertFalse(fx { tree(view).isVisible })
    }

    @Test
    fun `thumbnails paint their pages, follow the current page, and a click or Enter jumps`() {
        val view = open(PanelPdfs.sideNoOutline)
        awaitRendered(view, 0)
        fx { toggle(view).isSelected = true }
        assertTrue(fx { view.split.leftShown.get() })
        await { thumbnail(view, 0)?.let(::imageOf) != null && thumbnail(view, 1)?.let(::imageOf) != null }
        val painted = paint()
        for (page in 0..1) {
            val paper = fx { thumbnail(view, page)!!.lookup(".pdf-thumbnail-page") }
            assertTrue(colours(painted, fx { paper.localToScene(paper.layoutBounds) }).any { !it.isWhite() })
        }
        assertEquals("2", fx { (thumbnail(view, 1)!!.lookup(".pdf-thumbnail-number") as Label).text })
        // "Page 1" and "Page 2": each thumbnail is the image of its own page.
        val first = checkNotNull(imageOf(checkNotNull(thumbnail(view, 0))))
        val second = checkNotNull(imageOf(checkNotNull(thumbnail(view, 1))))
        assertFalse(pixels(first).contentEquals(pixels(second)))
        // A cell reused for page 1 again shows page 1's image, kept in the cache.
        fx { list(view).scrollTo(PanelPdfs.SIDE_PAGES - 1) }
        await { thumbnail(view, PanelPdfs.SIDE_PAGES - 1)?.let(::imageOf) != null }
        fx { list(view).scrollTo(0) }
        assertSame(first, imageOf(checkNotNull(thumbnail(view, 0))))
        val cell = checkNotNull(thumbnail(view, 0))
        fx {
            val content = cell.lookup(".pdf-thumbnail") as VBox
            val paper = cell.lookup(".pdf-thumbnail-page") as Region
            val image = cell.lookup(".pdf-thumbnail-image") as ImageView
            // A letter page, 120 wide, on paper of its size, centred above its number.
            assertEquals(120.0, paper.width, 0.5)
            assertEquals(120.0 * 792 / 612, paper.height, 1.0)
            assertEquals(120.0, image.fitWidth, 0.5)
            assertEquals(120.0 * 792 / 612, image.fitHeight, 1.0)
            assertEquals(4.0, content.spacing)
            assertEquals(content.width / 2, paper.boundsInParent.centerX, 1.0)
            // Rendered at the thumbnail's width times the screen's output scale.
            assertEquals(120.0 * stage.outputScaleX, checkNotNull(image.image).width, 1.0)
        }
        assertEquals(0, fx { list(view).selectionModel.selectedIndex })
        fx { view.scrollTo(PageTarget(15)) }
        assertEquals(15, fx { list(view).selectionModel.selectedIndex })
        assertNotNull(thumbnail(view, 15), "the list did not scroll to the current page")
        assertEquals(15, fx { view.currentPage.get() })
        fx {
            list(view).selectionModel.select(14)
            Event.fireEvent(list(view), keyPress(KeyCode.UP))
        }
        assertEquals(13, fx { list(view).selectionModel.selectedIndex })
        assertEquals(15, fx { view.currentPage.get() })
        fx { Event.fireEvent(list(view), keyPress(KeyCode.ENTER)) }
        assertEquals(13, fx { view.currentPage.get() })
        assertSame(view.pages, focusOwner())
        fx { list(view).requestFocus() }
        fx { Event.fireEvent(checkNotNull(thumbnail(view, 14)), click()) }
        assertEquals(14, fx { view.currentPage.get() })
        assertEquals(14, fx { list(view).selectionModel.selectedIndex })
        assertSame(view.pages, focusOwner())
    }

    @Test
    fun `a picked thumbnail stays selected until the user scrolls or the pages move away, also below the top`() {
        val view = open(PanelPdfs.sideNoOutline)
        awaitRendered(view, 0)
        // Several pages in the viewport: the last ones cannot scroll to its top, so the current page is an earlier one.
        fx { repeat(4) { view.pageColumn.zoomOut() } }
        fx { toggle(view).isSelected = true }
        paint()
        val last = PanelPdfs.SIDE_PAGES - 1
        fx { list(view).scrollTo(last) }
        fx { Event.fireEvent(checkNotNull(thumbnail(view, last)), click()) }
        val current = fx { view.currentPage.get() }
        assertTrue(current < last, "current page $current")
        assertEquals(last, fx { list(view).selectionModel.selectedIndex })
        fx {
            val slot = view.pageColumn.pageSlots.getValue(current)
            val centre = slot.localToScene(slot.layoutBounds)
            Event.fireEvent(slot, wheel(centre.centerX, centre.centerY, 10.0))
        }
        assertEquals(fx { view.currentPage.get() }, fx { list(view).selectionModel.selectedIndex })
        // Picked again, then the pages move by another way than the user's scroll (the page field, a link, a hit).
        fx { list(view).scrollTo(last) }
        fx { Event.fireEvent(checkNotNull(thumbnail(view, last)), click()) }
        assertEquals(last, fx { list(view).selectionModel.selectedIndex })
        fx { view.scrollTo(PageTarget(2)) }
        val moved = fx { view.currentPage.get() }
        assertTrue(moved < current, "current page $moved")
        assertEquals(moved, fx { list(view).selectionModel.selectedIndex })
    }

    @Test
    fun `thumbnails opened after the pages moved show the current page`() {
        val view = open(PanelPdfs.sideNoOutline)
        awaitRendered(view, 0)
        fx { view.scrollTo(PageTarget(15)) }
        fx { toggle(view).isSelected = true }
        await { thumbnail(view, 15) != null }
        assertEquals(15, fx { list(view).selectionModel.selectedIndex })
        fx { view.scrollTo(PageTarget(0)) }
        assertNotNull(thumbnail(view, 0), "the list did not scroll to the first page")
        // Without a selection (a Shortcut+click took it), the list stays where it is when shown again.
        fx { view.scrollTo(PageTarget(15)) }
        assertNotNull(thumbnail(view, 15))
        fx { list(view).selectionModel.clearSelection() }
        fx { toggle(view).isSelected = false }
        fx { toggle(view).isSelected = true }
        paint()
        assertNotNull(thumbnail(view, 15), "the list scrolled away")
    }

    @Test
    fun `only a single primary click on a page jumps, not another button, a double click or empty space`() {
        val view = open(TestPdfs.text)
        awaitRendered(view, 0)
        fx {
            view.pageColumn.actualSize()
            view.scrollTo(PageTarget(0, 300.0))
        }
        val top = fx { view.pageColumn.viewTop }
        assertTrue(top > 0)
        fx { toggle(view).isSelected = true }
        paint()
        val page = checkNotNull(thumbnail(view, 0))
        fx { Event.fireEvent(page, click(MouseButton.SECONDARY)) }
        fx { Event.fireEvent(page, click(count = 2)) }
        val empty =
            fx {
                list(view).lookupAll(".list-cell").first { (it as ListCell<*>).isEmpty && it.isVisible }
            }
        fx { Event.fireEvent(empty, click()) }
        fx { Event.fireEvent(list(view), click()) }
        assertEquals(top, fx { view.pageColumn.viewTop })
        // A click on the number inside a page's cell is one on the page.
        fx { Event.fireEvent(page.lookup(".pdf-thumbnail-number"), click()) }
        assertTrue(fx { view.pageColumn.viewTop } < top)
    }

    @Test
    fun `a thumbnail has the shape of its own page`() {
        val view = open(PanelPdfs.sidePortraitLandscape)
        awaitRendered(view, 0)
        fx { toggle(view).isSelected = true }
        paint()
        val paper = fx { checkNotNull(thumbnail(view, 1)).lookup(".pdf-thumbnail-page") as Region }
        assertTrue(fx { paper.width > paper.height }, fx { "${paper.width} by ${paper.height}" })
    }

    @Test
    fun `a shorter document after a reload leaves the cells below its pages empty`() {
        val file = dir.resolve("shrinking.pdf")
        Files.copy(PanelPdfs.sideNoOutline, file)
        var reloads = 0
        val view = open(file) { opening -> opening.onReload += { reloads++ } }
        awaitRendered(view, 0)
        fx { toggle(view).isSelected = true }
        await { thumbnail(view, 1)?.let(::imageOf) != null }
        replace(file, TestPdfs.text)
        fx { view.reload() }
        await { reloads == 1 }
        paint()
        val cells = fx { list(view).lookupAll(".list-cell").map { it as ListCell<*> }.filter { it.isVisible } }
        assertTrue(cells.count { it.isEmpty } > 0)
        assertEquals(listOf(null), fx { cells.filter { it.isEmpty }.map { it.graphic }.distinct() })
    }

    @Test
    fun `the outline scrolls to show the item it follows, also one followed while the panel was hidden`() {
        val view = open(PanelPdfs.sideLongOutline)
        awaitRendered(view, 0)
        // Page 1 comes before the first item's.
        assertNull(selectedTitle(view))
        fx { view.scrollTo(PageTarget(50)) }
        assertEquals("Page 51", selectedTitle(view))
        cell(view, "Page 51")
        fx { toggle(view).isSelected = false }
        fx { view.scrollTo(PageTarget(10)) }
        fx { toggle(view).isSelected = true }
        assertEquals("Page 11", selectedTitle(view))
        paint()
        cell(view, "Page 11")
        fx { view.scrollTo(PageTarget(0)) }
        assertNull(selectedTitle(view))
    }

    @Test
    fun `a long title shows on one line, cut off within the panel, with the whole title as tooltip`() {
        val view = open(PanelPdfs.sideLongOutline)
        awaitRendered(view, 0)
        fx { view.scrollTo(PageTarget(PanelPdfs.SIDE_LONG_PAGES - 1)) }
        paint()
        val long = cell(view, PanelPdfs.SIDE_LONG_TITLE)
        assertEquals(PanelPdfs.SIDE_LONG_TITLE.replace("\n", " "), fx { long.text })
        assertEquals(PanelPdfs.SIDE_LONG_TITLE, fx { long.tooltip?.text })
        assertTrue(fx { long.width <= tree(view).width }, fx { "${long.width} in ${tree(view).width}" })
        assertTrue(
            fx { (long.lookup(".text") as Text).text.endsWith("...") },
            fx { (long.lookup(".text") as Text).text },
        )
    }

    @Test
    fun `the toggle hides the panel and shows it again at the width it had, and follows the split`() {
        val view = open(PanelPdfs.sideOutline)
        awaitRendered(view, 0)
        assertTrue(fx { view.split.leftShown.get() })
        await { view.split.left.width in 210.0..222.0 }
        fx { view.split.node.setDividerPosition(0, 0.4) }
        await { view.split.left.width > 300 }
        val dragged = fx { view.split.left.width }
        fx { toggle(view).isSelected = false }
        assertFalse(fx { view.split.leftShown.get() })
        assertFalse(
            fx {
                view.split.node.items
                    .contains(view.split.left)
            },
        )
        fx { toggle(view).isSelected = true }
        // The split sizes its panels at once and once more a pulse later, which a snapshot waits for.
        paint()
        assertEquals(dragged, fx { view.split.left.width }, 1.0)
        fx { view.split.showLeft(false) }
        assertFalse(fx { toggle(view).isSelected })
        fx { view.split.showLeft(true) }
        assertTrue(fx { toggle(view).isSelected })
    }

    @Test
    fun `a reload rebuilds the outline from the new file`() {
        val file = dir.resolve("outline.pdf")
        Files.copy(PanelPdfs.sideOutline, file)
        var reloads = 0
        val view = open(file) { opening -> opening.onReload += { reloads++ } }
        awaitRendered(view, 0)
        fx {
            view.scrollTo(PageTarget(4))
            tabs(view).selectionModel.select(1)
            toggle(view).isSelected = false
        }
        replace(file, PanelPdfs.sideOutlineRenamed)
        fx { view.reload() }
        await { reloads == 1 }
        val roots = fx { tree(view).root.children.toList() }
        assertEquals(
            listOf("New 1 Intro", "New 2 Body", "New 3 No destination", "New 4 Late", "New 5 End"),
            titles(roots),
        )
        assertEquals("New 4 Late", selectedTitle(view))
        assertEquals(4, fx { list(view).selectionModel.selectedIndex })
        // The user's choices stay: the start rule (open on Outline) is for the first document only.
        assertFalse(fx { view.split.leftShown.get() })
        assertEquals("Thumbnails", selectedTab(view))
    }

    @Test
    fun `a reload drops the thumbnails and renders the shown ones again, keeping the tab and the open panel`() {
        val file = dir.resolve("pages.pdf")
        Files.copy(PanelPdfs.sideNoOutline, file)
        var reloads = 0
        var cell: ListCell<Int>? = null
        // Right after the panel's own reload callback (it registered first), before any layout: the image it showed
        // and the renders it queued.
        var atReload: Pair<Image?, Int>? = null
        val view =
            open(file) { opening ->
                opening.onReload += {
                    reloads++
                    atReload = imageOnFx(checkNotNull(cell)) to queuedThumbnailsOnFx(opening)
                }
            }
        awaitRendered(view, 0)
        fx { toggle(view).isSelected = true }
        assertTrue(fx { view.split.leftShown.get() })
        await { thumbnail(view, 0)?.let(::imageOf) != null }
        cell = checkNotNull(thumbnail(view, 0))
        val before = imageOf(checkNotNull(cell))
        assertNotNull(before)
        // The same document again: its info is equal, only the reload tells the panel.
        replace(file, PanelPdfs.sideNoOutline)
        fx { view.reload() }
        await { reloads == 1 }
        val (image, queued) = checkNotNull(atReload)
        assertNull(image)
        assertTrue(queued > 0, "no thumbnail render queued at the reload")
        await { thumbnail(view, 0)?.let(::imageOf).let { it != null && it !== before } }
        assertTrue(fx { view.split.leftShown.get() })
        assertEquals("Thumbnails", selectedTab(view))
    }

    @Test
    fun `thumbnails render only while their tab shows in the panel, and dispose drops the queued ones`() {
        val executor = NamedThreads.single("test-side-render")
        val view = open(PanelPdfs.sideOutline, executor)
        awaitRendered(view, 0)
        val release = CountDownLatch(1)
        try {
            executor.execute { release.await() }
            // Page renders wait behind the thumbnails' in the same queue.
            fx { view.scrollTo(PageTarget(5)) }
            paint()
            assertEquals(0, queuedThumbnails(view))
            fx { tabs(view).selectionModel.select(1) }
            paint()
            assertTrue(queuedThumbnails(view) > 0)
            // Scrolled away, the list drops its stale renders, and only those: hiding the panel would resize the pages,
            // which queue their renders anew anyway.
            val pageRenders = queuedPageRenders(view)
            assertTrue(pageRenders > 0)
            val shown = fx { thumbnailsShown(view) }
            fx { list(view).scrollTo(0) }
            paint()
            assertTrue(fx { thumbnailsShown(view) } != shown, "the list did not scroll")
            assertEquals(pageRenders, queuedPageRenders(view))
            fx { toggle(view).isSelected = false }
            assertEquals(0, queuedThumbnails(view))
            fx { toggle(view).isSelected = true }
            paint()
            assertTrue(queuedThumbnails(view) > 0)
            fx { view.dispose() }
            assertEquals(0, queuedThumbnails(view))
        } finally {
            release.countDown()
        }
    }

    private fun replace(
        file: Path,
        with: Path,
    ) {
        Files.copy(with, file, StandardCopyOption.REPLACE_EXISTING)
        Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 10_000))
    }
}
