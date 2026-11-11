package dev.sluide.ebooks.view

import dev.sluide.ebooks.TestChapter
import dev.sluide.ebooks.TestEpub
import dev.sluide.fileviewers.ViewActivity
import dev.sluide.speech.ReadAloudSurface
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.value.ObservableValue
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.ListCell
import javafx.scene.control.ToggleButton
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import javafx.util.Duration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.time.Duration as JavaDuration

/** The reader's Search panel in a scene: focus, key handling, the click that jumps and marks, on a real WebView. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class EbookSearchUiTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var stage: Stage
    private var view: EpubFileView? = null
    private val executors = mutableListOf<java.util.concurrent.ExecutorService>()

    @Start
    fun start(stage: Stage) {
        this.stage = stage
        stage.scene = Scene(StackPane(), 900.0, 600.0)
        stage.show()
    }

    @AfterEach
    fun close() {
        fx { view?.dispose() }
        executors.forEach { it.shutdownNow() }
    }

    private fun <T> fx(block: () -> T): T = WaitForAsyncUtils.asyncFx<T> { block() }.get(20, TimeUnit.SECONDS)

    private fun await(condition: () -> Boolean) = WaitForAsyncUtils.waitFor(30, TimeUnit.SECONDS) { fx(condition) }

    private fun chapter(word: String) =
        "<h1>$word</h1>" + (1..40).joinToString("") { "<p>$word paragraph $it. A second sentence of $it.</p>" }

    private fun open(readAloud: ((ReadAloudSurface) -> ReaderControls?)? = null): Pair<EpubFileView, SearchPanel> {
        val chapters = listOf("Alpha", "Beta", "Gamma").map { TestChapter("$it.xhtml", chapter(it), title = it) }
        val file = TestEpub(chapters).write(dir, "book.epub")
        val loader = Executors.newSingleThreadExecutor { Thread(it).apply { isDaemon = true } }
        val writer = Executors.newSingleThreadExecutor { Thread(it).apply { isDaemon = true } }
        executors += listOf(loader, writer)
        val position = BookPosition(MemoryStore(), writer, JavaDuration.ofMillis(10))
        val config = EpubViewConfig(scrollDelay = Duration.millis(20.0))
        val panel =
            fx {
                val v = EpubFileView(BookLocation(file), position, loader, config)
                view = v
                stage.scene.root = v.node as javafx.scene.Parent
                readAloud?.let { ReaderReadAloud.attach(v, it) }
                ReaderSearch.attach(v, Duration.millis(50.0))
            }
        await { view?.page != null }
        return checkNotNull(view) to panel
    }

    private class MemoryStore : PositionStore {
        var saved: Int? = null

        override fun read() = saved

        override fun write(offset: Int) {
            saved = offset
        }
    }

    private fun keyPressed(
        node: Node,
        code: KeyCode,
        shortcut: Boolean = false,
    ) = Event.fireEvent(
        node,
        KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, shortcut && !isMac, false, shortcut && isMac),
    )

    private val isMac = System.getProperty("os.name").lowercase().contains("mac")

    private fun highlighted(v: EpubFileView): Any? = fx { highlightedNow(v) }

    private fun highlightedNow(v: EpubFileView) =
        v.webView.engine.executeScript(
            "(CSS.highlights.get('mark-search') ? Array.from(CSS.highlights.get('mark-search')).map(String).join('|') : null)",
        )

    private fun toggleOf(v: EpubFileView) = fx { v.searchSlot.children.single() as ToggleButton }

    private fun search(
        panel: SearchPanel,
        text: String,
        enter: Boolean = true,
    ) {
        fx {
            panel.field.text = text
            if (enter) keyPressed(panel.field, KeyCode.ENTER)
        }
    }

    private fun click(
        panel: SearchPanel,
        index: Int,
    ) {
        fx { panel.list.selectionModel.select(index) }
        await { panel.list.lookupAll(".list-cell").any { (it as? ListCell<*>)?.index == index } }
        fx {
            val cell =
                panel.list
                    .lookupAll(".list-cell")
                    .filterIsInstance<ListCell<*>>()
                    .first { it.index == index }
            Event.fireEvent(
                cell,
                MouseEvent(
                    MouseEvent.MOUSE_CLICKED,
                    5.0,
                    5.0,
                    5.0,
                    5.0,
                    MouseButton.PRIMARY,
                    1,
                    false,
                    false,
                    false,
                    false,
                    true,
                    false,
                    false,
                    true,
                    false,
                    false,
                    null,
                ),
            )
        }
    }

    private fun cellAt(
        panel: SearchPanel,
        index: Int,
    ) = panel.list
        .lookupAll(".list-cell")
        .filterIsInstance<ListCell<*>>()
        .firstOrNull { it.index == index }

    private fun openedWithHits(): Triple<EpubFileView, SearchPanel, ToggleButton> {
        val (v, panel) = open()
        val toggle = toggleOf(v)
        fx { toggle.isSelected = true }
        search(panel, "paragraph 30.")
        await { panel.status.text == "3 matches" }
        return Triple(v, panel, toggle)
    }

    @Test
    fun `Shortcut+F opens the panel and focuses the field, a search lists hits by chapter, a click jumps and marks`() {
        val (v, panel) = open()
        val toggle = toggleOf(v)
        assertTrue(fx { v.rightPane.children.isEmpty() && !toggle.isSelected })

        fx { keyPressed(v.webView, KeyCode.F, shortcut = true) }
        await { panel.field.isFocused }
        assertTrue(fx { toggle.isSelected && v.rightPane.children.contains(panel.node) })

        search(panel, "paragraph 30.")
        await { panel.status.text == "3 matches" }
        val rows = fx { panel.list.items.toList() }
        assertEquals(listOf("Alpha", "Beta", "Gamma"), rows.filterIsInstance<SearchRow.Heading>().map { it.title })
        assertEquals(6, rows.size)

        val gamma = rows.filterIsInstance<SearchRow.Hit>().last().match
        click(panel, 5)
        await { v.topOffsetWhole?.let { kotlin.math.abs(it - gamma.start) < 400 } == true }
        await { highlighted(v) == "paragraph 30." }

        fx { keyPressed(panel.field, KeyCode.ESCAPE) }
        assertEquals("", fx { panel.field.text })
        assertNull(highlighted(v))
        assertTrue(fx { toggle.isSelected })
        fx { keyPressed(panel.field, KeyCode.ESCAPE) }
        assertTrue(fx { !toggle.isSelected && v.rightPane.children.isEmpty() })
        assertNotNull(fx { v.page })
    }

    private class ReadingControls : ReaderControls {
        override val toolbar: Node = javafx.scene.control.Label("toolbar")
        override val notice: Node = javafx.scene.control.Label("notice")
        override val activity: ObservableValue<ViewActivity> = SimpleObjectProperty(ViewActivity.RUNNING)
        override var reading = true
        override var onFinished: () -> Unit = {}

        override fun play() {}

        override fun stop() {}

        override fun jumpTo(offset: Int) {}

        override fun handleKey(event: KeyEvent) = false

        override fun dispose() {}
    }

    @Test
    fun `a hit activated while Read Aloud follows stops the following, offers Back to reading and stays at the hit`() {
        var surface: ReadAloudSurface? = null
        val (v, panel) = open { s -> ReadingControls().also { surface = s } }
        await { v.readAloudSlot.children.isNotEmpty() }
        val reading = surface as BookReadingSurface
        fx {
            reading.lock(true)
            reading.highlight(0..5, null)
            toggleOf(v).isSelected = true
        }
        search(panel, "paragraph 30.")
        await { panel.status.text == "3 matches" }
        val gamma =
            fx {
                panel.list.items
                    .filterIsInstance<SearchRow.Hit>()
                    .last()
                    .match
            }
        click(panel, 5)
        await { v.topOffsetWhole?.let { kotlin.math.abs(it - gamma.start) < 400 } == true }
        assertFalse(fx { reading.following })
        assertTrue(fx { (v.node.lookup(".ebook-back-to-reading") as javafx.scene.control.Button).isVisible })
        // The next word of the reading, in chapter Alpha, must not take the page away from the hit.
        fx { reading.reveal(0, 5) }
        assertTrue(fx { v.topOffsetWhole!! } >= gamma.start - 400)
    }

    @Test
    fun `closing the panel with the toggle removes the highlight and the results, reopening searches again`() {
        val (v, panel, toggle) = openedWithHits()
        click(panel, 1)
        await { highlighted(v) == "paragraph 30." }

        fx { toggle.isSelected = false }
        assertNull(highlighted(v))
        assertTrue(fx { panel.list.items.isEmpty() && panel.status.text.isEmpty() })

        fx { toggle.isSelected = true }
        await { panel.status.text == "3 matches" }
    }

    @Test
    fun `the arrow keys skip the chapter headings, Enter on a hit marks it and the focus stays in the list`() {
        val (v, panel, _) = openedWithHits()
        fx { panel.list.requestFocus() }
        await { panel.list.isFocused }

        fx { panel.list.selectionModel.select(0) }
        assertEquals(1, fx { panel.list.selectionModel.selectedIndex })
        fx { panel.list.selectionModel.select(2) }
        assertEquals(3, fx { panel.list.selectionModel.selectedIndex })
        fx { panel.list.selectionModel.select(2) }
        assertEquals(1, fx { panel.list.selectionModel.selectedIndex })

        // Selected row 1 and unselected row 3: the snippet's Text takes the cell's text colour (readable in dark).
        for (index in listOf(1, 3)) await { cellAt(panel, index) != null }
        fx {
            for (index in listOf(1, 3)) {
                val cell = checkNotNull(cellAt(panel, index))
                cell.applyCss()
                val texts = cell.graphic.lookupAll(".ebook-search-text").filterIsInstance<javafx.scene.text.Text>()
                assertEquals(3, texts.size)
                texts.forEach { assertEquals(cell.textFill, it.fill, "row $index") }
            }
        }

        assertNull(highlighted(v))
        fx { keyPressed(panel.list, KeyCode.ENTER) }
        await { highlighted(v) == "paragraph 30." }
        assertTrue(fx { panel.list.isFocused })
    }

    @Test
    fun `typing searches after the debounce without Enter`() {
        val (_, panel) = open()
        val v = checkNotNull(view)
        fx { toggleOf(v).isSelected = true }
        search(panel, "paragraph 30.", enter = false)
        await { panel.status.text == "3 matches" }
    }

    @Test
    fun `a pending search delivers nothing after the view is disposed`() {
        val (v, panel) = open()
        fx { toggleOf(v).isSelected = true }
        fx {
            panel.field.text = "paragraph 30."
            v.dispose()
        }
        // Negative check without a signal: the debounce is 50 ms, so give a search time to run and deliver.
        Thread.sleep(500)
        assertTrue(fx { panel.list.items.isEmpty() && panel.status.text.isEmpty() })
    }
}
