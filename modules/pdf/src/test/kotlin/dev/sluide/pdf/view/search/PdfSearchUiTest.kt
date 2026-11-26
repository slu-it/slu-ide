package dev.sluide.pdf.view.search

import dev.sluide.pdf.TestPdfs
import dev.sluide.pdf.engine.PageTarget
import dev.sluide.pdf.engine.PageText
import dev.sluide.pdf.view.OverlayLayer
import dev.sluide.pdf.view.PdfFileView
import dev.sluide.pdf.view.PdfViewUiFixture
import dev.sluide.pdf.view.keyPress
import dev.sluide.pdf.view.textRects
import javafx.animation.PauseTransition
import javafx.event.Event
import javafx.event.EventType
import javafx.geometry.Insets
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.control.TextField
import javafx.scene.control.ToggleButton
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.Region
import javafx.scene.paint.Color
import javafx.scene.text.Text
import javafx.scene.text.TextFlow
import javafx.stage.Stage
import javafx.util.Duration
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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The search panel in a PDF view: one test per connection to the view (toggle and Shortcut+F, the page texts and the
 * document, the shown pages and highlights, the scroll, reload and dispose) and what needs a scene (focus, keys,
 * painted highlights). The search's own cases are in [SearchFlowTest].
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
internal class PdfSearchUiTest : PdfViewUiFixture() {
    @TempDir
    lateinit var dir: Path

    @Start
    fun start(stage: Stage) = startStage(stage)

    private fun toggle(view: PdfFileView) =
        assertIs<ToggleButton>(
            fx {
                view.toolbar.searchSlot.children
                    .singleOrNull()
            },
        )

    private fun field(view: PdfFileView) = assertIs<TextField>(fx { view.split.right.lookup(".pdf-search-field") })

    private fun status(view: PdfFileView) = assertIs<Label>(fx { view.split.right.lookup(".pdf-search-status") }).text

    @Suppress("UNCHECKED_CAST")
    private fun list(view: PdfFileView) =
        assertIs<ListView<*>>(fx { view.split.right.lookup(".pdf-search-list") }) as ListView<SearchRow>

    private fun rows(view: PdfFileView) = fx { list(view).items.toList() }

    private fun headings(view: PdfFileView) = rows(view).filterIsInstance<SearchRow.Heading>().map { it.title }

    private fun find(target: Node) = fx { Event.fireEvent(target, keyPress(KeyCode.F, shortcut = true)) }

    /** Opens the panel with Shortcut+F, searches [query] with Enter and waits for [expected] in the status. */
    private fun searched(
        view: PdfFileView,
        query: String = TestPdfs.SEARCH_WORD,
        expected: String = "4 matches",
    ) {
        find(view.node)
        val field = field(view)
        fx {
            field.text = query
            Event.fireEvent(field, keyPress(KeyCode.ENTER))
        }
        await { status(view) == expected }
    }

    private fun openSearch(): PdfFileView {
        val view = open(TestPdfs.search)
        awaitRendered(view, 0)
        return view
    }

    private fun textOf(
        view: PdfFileView,
        page: Int,
    ): PageText {
        var text: PageText? = null
        fx { view.texts.textOf(page) { text = it } }
        await { text != null }
        return checkNotNull(text)
    }

    /** The bounds of [hit]'s word on its page. */
    private fun pageRectOf(
        view: PdfFileView,
        hit: SearchHit,
    ) = checkNotNull(bounds(textRects(textOf(view, hit.page), hit.start, hit.end)))

    /** The scene bounds of [hit]'s word; not inside [await], which runs its condition on the FX thread. */
    private fun rectOf(
        view: PdfFileView,
        hit: SearchHit,
    ) = sceneRect(view, hit.page, pageRectOf(view, hit))

    private fun hits(view: PdfFileView) = rows(view).filterIsInstance<SearchRow.Hit>().map { it.hit }

    /** The brightest pixel painted over [hit]: the highlight over the white page between the glyphs. */
    private fun brightest(
        view: PdfFileView,
        hit: SearchHit,
    ): Color = colours(paint(), rectOf(view, hit)).maxBy { it.red + it.green + it.blue }

    /** Waits on the FX thread's animation timer until the 300 ms debounce of a query typed before has ended. */
    private fun pastDebounce() {
        val ended = CountDownLatch(1)
        fx { PauseTransition(Duration.millis(400.0)).apply { setOnFinished { ended.countDown() } }.play() }
        assertTrue(ended.await(30, TimeUnit.SECONDS))
    }

    private fun Color.isLight() = red > 0.9 && blue in 0.55..0.8

    private fun Color.isStrong() = red > 0.9 && blue < 0.55

    /**
     * A mouse event of [button] at the centre of [target], not synthesized: a list cell selects on such a press, as on a
     * real one, when it lies within the cell.
     */
    private fun mouse(
        type: EventType<MouseEvent>,
        target: Node,
        button: MouseButton = MouseButton.PRIMARY,
    ) = fx {
        val centre = target.localToScene(target.layoutBounds)
        Event.fireEvent(
            target,
            MouseEvent(
                type,
                centre.centerX,
                centre.centerY,
                centre.centerX,
                centre.centerY,
                button,
                1,
                false,
                false,
                false,
                false,
                button == MouseButton.PRIMARY,
                false,
                button == MouseButton.SECONDARY,
                false,
                false,
                false,
                null,
            ),
        )
    }

    /** Counts the key presses that reach the scene from now on: the panel consumes those it acts on. */
    private fun keysReachingScene(): () -> Int {
        var reached = 0
        fx { stage.scene.addEventHandler(KeyEvent.KEY_PRESSED) { reached++ } }
        return { fx { reached } }
    }

    /**
     * Waits until the text of [page] with boxes has come: a choice made before asks for it first, so its highlight
     * and scroll are done by then.
     */
    private fun awaitTextOf(
        view: PdfFileView,
        page: Int,
    ) {
        textOf(view, page)
    }

    /** What each list cell shows that does not fit its row; none when every cell shows its own. */
    private fun cellsNotShowingTheirRows(view: PdfFileView): List<String> =
        fx {
            list(view).lookupAll(".list-cell").map { it as ListCell<*> }.mapNotNull { cell ->
                val heading = "pdf-search-heading" in cell.styleClass
                val fits =
                    when (val item = cell.item) {
                        is SearchRow.Heading -> cell.text == item.title && cell.graphic == null && heading
                        is SearchRow.Hit -> cell.text == null && cell.graphic is TextFlow && !heading
                        else -> cell.text == null && cell.graphic == null && !heading
                    }
                val shows = "'${cell.text}', ${cell.graphic}, heading $heading"
                if (fits) null else "${cell.index}: ${cell.item} shows $shows"
            }
        }

    private fun cellAt(
        view: PdfFileView,
        index: Int,
    ): ListCell<*> {
        await { list(view).lookupAll(".list-cell").any { (it as ListCell<*>).index == index } }
        return fx { list(view).lookupAll(".list-cell").map { it as ListCell<*> }.first { it.index == index } }
    }

    @Test
    fun `the toggle and Shortcut+F open the panel with the field focused, also from the page field`() {
        val view = openSearch()
        val toggle = toggle(view)
        assertFalse(fx { toggle.isFocusTraversable })
        assertEquals("Search (Shortcut+F)", fx { toggle.tooltip.text })
        assertTrue(fx { "pdf-button" in toggle.styleClass })
        assertFalse(fx { view.split.rightShown.get() })
        val keys = keysReachingScene()
        find(view.pages)
        assertEquals(0, keys())
        assertTrue(fx { toggle.isSelected && view.split.rightShown.get() })
        val field = field(view)
        await { field.isFocused }
        assertEquals("Search in PDF", fx { field.promptText })
        // Shortcut+F with the panel open focuses the field again, its text selected.
        fx {
            field.text = "lan"
            view.pages.requestFocus()
        }
        find(view.pages)
        await { field.isFocused }
        assertEquals("lan", fx { field.selectedText })
        // Also while the field has the focus, its caret placed without a selection.
        fx { field.positionCaret(1) }
        assertEquals("", fx { field.selectedText })
        find(field)
        assertEquals("lan", fx { field.selectedText })
        fx { toggle.isSelected = false }
        assertFalse(fx { view.split.rightShown.get() })
        fx {
            field.text = "lan"
            view.toolbar.focusPageField()
        }
        find(view.toolbar.pageField)
        await { field.isFocused }
        assertEquals("lan", fx { field.selectedText })
        fx {
            view.pages.requestFocus()
            toggle.isSelected = false
            toggle.fire()
        }
        assertTrue(fx { view.split.rightShown.get() })
        await { field.isFocused }
    }

    @Test
    fun `typing a word lists its hits by page, and the shown pages show them lightly`() {
        val view = openSearch()
        find(view.pages)
        assertTrue(fx { view.split.rightShown.get() })
        fx { field(view).text = TestPdfs.SEARCH_WORD }
        await { status(view) == "4 matches" }
        assertEquals(listOf("Page 1", "Page 3", "Page 5"), headings(view))
        assertEquals(7, rows(view).size)
        await { view.overlays.on(0).isNotEmpty() }
        assertTrue(fx { view.overlays.on(0).all { it.first == OverlayLayer.SEARCH_HIT } })
        assertTrue(fx { view.overlays.on(2).isEmpty() && view.overlays.on(4).isEmpty() })
        val painted = brightest(view, hits(view).first())
        assertTrue(painted.isLight(), painted.toString())
        fx { view.scrollTo(PageTarget(2)) }
        await { view.overlays.on(2).size == 2 }
        awaitRendered(view, 2)
        val scrolledTo = brightest(view, hits(view)[2])
        assertTrue(scrolledTo.isLight(), scrolledTo.toString())

        // Enter searches at once: the texts are kept, so the matching starts before the runnable ends.
        val field = field(view)
        val keys = keysReachingScene()
        val status =
            fx {
                field.text = "page"
                Event.fireEvent(field, keyPress(KeyCode.ENTER))
                status(view)
            }
        assertEquals(SEARCHING, status)
        assertEquals(0, keys())
        // Enter stops the debounce of the text typed: no second search replaces the results.
        await { status(view) == "2 matches" }
        val listed = fx { list(view).items }
        pastDebounce()
        assertSame(listed, fx { list(view).items })
    }

    @Test
    fun `the panel fills the side, can be made narrow, and its rows show headings and snippets, also when reused`() {
        val view = openSearch()
        searched(view)
        paint()
        val panel =
            fx {
                view.split.right.children
                    .single() as Region
            }
        assertEquals(Insets(6.0), fx { panel.padding })
        val list = list(view)
        assertEquals(fx { panel.height - panel.padding.bottom }, fx { list.boundsInParent.maxY }, 1.0)

        val heading = cellAt(view, 0)
        assertEquals("Page 1", fx { heading.text })
        val snippet = assertIs<TextFlow>(fx { cellAt(view, 1).graphic })
        val texts = fx { snippet.children.map { it as Text } }
        assertEquals(listOf("A ", TestPdfs.SEARCH_WORD, " on page one"), fx { texts.map { it.text } })
        assertTrue(
            fx { "pdf-search-snippet" in snippet.styleClass && texts.all { "pdf-search-text" in it.styleClass } },
        )
        assertTrue(fx { "Bold" in texts[1].font.style && "Bold" !in texts[0].font.style }, fx { texts[1].font.style })
        assertEquals(fx { cellAt(view, 1).width - 24 }, fx { snippet.prefWidth }, 0.5)
        assertEquals(emptyList(), cellsNotShowingTheirRows(view))

        // "on": Page 1, hit, hit, Page 2, ...: cells that showed a heading show a hit now, and the other way round.
        searched(view, "on", "4 matches")
        assertEquals(emptyList(), cellsNotShowingTheirRows(view))
        // "lit": two rows, the other cells empty.
        searched(view, "lit", "1 match")
        assertEquals(emptyList(), cellsNotShowingTheirRows(view))

        fx { view.split.node.setDividerPosition(view.split.node.dividers.lastIndex, 0.99) }
        await { view.split.right.width < 10 }
    }

    @Test
    fun `only a primary click or Enter on a selected hit chooses it, and the selection skips the headings`() {
        val view = openSearch()
        searched(view)
        val list = list(view)
        fx { list.selectionModel.select(1) }
        fx { list.selectionModel.select(2) }
        assertEquals(3, fx { list.selectionModel.selectedIndex })
        fx { list.selectionModel.select(2) }
        assertEquals(1, fx { list.selectionModel.selectedIndex })
        fx { list.selectionModel.select(0) }
        assertEquals(-1, fx { list.selectionModel.selectedIndex })

        fx { Event.fireEvent(list, keyPress(KeyCode.ENTER)) }
        fx { list.selectionModel.select(6) }
        mouse(MouseEvent.MOUSE_CLICKED, cellAt(view, 6), MouseButton.SECONDARY)
        mouse(MouseEvent.MOUSE_CLICKED, cellAt(view, 5))
        fx { Event.fireEvent(list, keyPress(KeyCode.A)) }
        // Outside the cells.
        mouse(MouseEvent.MOUSE_PRESSED, list)
        mouse(MouseEvent.MOUSE_CLICKED, list)
        awaitTextOf(view, 4)
        assertTrue(fx { view.overlays.on(4).none { it.first == OverlayLayer.SEARCH_CURRENT } })
        assertEquals(0, fx { view.currentPage.get() })

        val keys = keysReachingScene()
        fx {
            list.selectionModel.select(6)
            Event.fireEvent(list, keyPress(KeyCode.ENTER))
        }
        // Enter on the list goes no further.
        assertEquals(0, keys())
        await { view.currentPage.get() == 4 }
    }

    @Test
    fun `Enter on a hit scrolls it into view with the strong highlight, the focus stays in the list, a click too`() {
        val view = openSearch()
        searched(view)
        val list = list(view)
        val hit = hits(view)[1]
        assertEquals(2, hit.page)
        fx {
            list.requestFocus()
            list.selectionModel.select(3)
            Event.fireEvent(list, keyPress(KeyCode.ENTER))
        }
        val viewport = fx { view.pages.localToScene(view.pages.layoutBounds) }
        val pageRect = pageRectOf(view, hit)
        await {
            checkNotNull(view.pageRectToScene(2, pageRect)).let {
                it.minY >= viewport.minY &&
                    it.maxY <= viewport.maxY
            }
        }
        awaitRendered(view, 2)
        await { view.overlays.on(2).any { it.first == OverlayLayer.SEARCH_CURRENT } }
        val current = brightest(view, hit)
        assertTrue(current.isStrong(), current.toString())
        val other = brightest(view, hits(view)[2])
        assertTrue(other.isLight(), other.toString())
        assertTrue(fx { list.isFocused })

        // A press on a heading leaves the selection, a click on a hit chooses it.
        val headingCell = cellAt(view, 5)
        mouse(MouseEvent.MOUSE_PRESSED, fx { checkNotNull(headingCell.lookup(".text")) })
        assertEquals(3, fx { list.selectionModel.selectedIndex })
        assertFalse(fx { headingCell.isSelected })
        fx { list.selectionModel.select(6) }
        mouse(MouseEvent.MOUSE_CLICKED, cellAt(view, 6))
        await { view.currentPage.get() == 4 }
    }

    @Test
    fun `Escape clears the field, a second Escape closes the panel, removes the highlights and focuses the pages`() {
        val view = openSearch()
        searched(view)
        await { view.overlays.on(0).isNotEmpty() }
        val field = field(view)
        // A focusable node in the left panel, which the focus could go to when the field leaves the scene.
        fx {
            view.split.left.children += Button("Outline")
            view.split.showLeft(true)
        }
        val keys = keysReachingScene()
        fx { Event.fireEvent(field, keyPress(KeyCode.ESCAPE)) }
        assertEquals("", fx { field.text })
        assertEquals(emptyList(), rows(view))
        assertTrue(fx { view.overlays.on(0).isEmpty() && view.split.rightShown.get() })
        fx { Event.fireEvent(field, keyPress(KeyCode.ESCAPE)) }
        assertFalse(fx { view.split.rightShown.get() || toggle(view).isSelected })
        assertEquals(0, keys())
        await { view.pages.isFocused }
    }

    @Test
    fun `closing removes the highlights and results and keeps the query, which opening searches again`() {
        val view = openSearch()
        searched(view)
        await { view.overlays.on(0).isNotEmpty() }
        val toggle = toggle(view)
        fx { toggle.isSelected = false }
        assertTrue(fx { view.overlays.on(0).isEmpty() })
        assertEquals("", status(view))
        assertEquals(emptyList(), rows(view))
        assertEquals(TestPdfs.SEARCH_WORD, fx { field(view).text })
        val white = brightest(view, SearchHit(0, 2, 2 + TestPdfs.SEARCH_WORD.length, "", "", ""))
        assertTrue(white.isWhite(), white.toString())
        fx { toggle.isSelected = true }
        await { status(view) == "4 matches" }

        // Closed before the debounce ends: the query typed is not searched with the panel closed.
        val field = field(view)
        fx {
            field.text = ""
            field.text = TestPdfs.SEARCH_WORD
            toggle.isSelected = false
        }
        pastDebounce()
        assertEquals("", status(view))
        assertTrue(fx { view.overlays.on(0).isEmpty() })
    }

    @Test
    fun `a query typed while the document loads is searched once it is open`() {
        val gate = CountDownLatch(1)
        val render =
            object : ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, LinkedBlockingQueue()) {
                override fun beforeExecute(
                    thread: Thread,
                    task: Runnable,
                ) = gate.await()
            }
        try {
            val view = open(TestPdfs.search, render)
            find(view.node)
            fx {
                field(view).text = TestPdfs.SEARCH_WORD
                Event.fireEvent(field(view), keyPress(KeyCode.ENTER))
            }
            assertEquals("", status(view))
            gate.countDown()
            await { status(view) == "4 matches" }
        } finally {
            gate.countDown()
        }
    }

    @Test
    fun `a reload with an open query searches the new text`() {
        val file = dir.resolve("search.pdf")
        Files.copy(TestPdfs.search, file)
        val view = open(file)
        awaitRendered(view, 0)
        searched(view)
        Files.copy(TestPdfs.searchMoved, file, StandardCopyOption.REPLACE_EXISTING)
        Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 10_000))
        fx { view.reload() }
        await { status(view) == "2 matches" }
        assertEquals(listOf("Page 2", "Page 4"), headings(view))
    }

    @Test
    fun `disposing the view ends the search's thread`() {
        val view = openSearch()
        searched(view)
        fx { view.dispose() }
        await { Thread.getAllStackTraces().keys.none { it.name == "sluide-pdf-search" && it.isAlive } }
    }
}
