package dev.sluide.ebooks.view

import dev.sluide.ebooks.TestChapter
import dev.sluide.ebooks.TestEpub
import dev.sluide.fileviewers.ViewActivity
import dev.sluide.speech.ReadAloudSurface
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.value.ObservableValue
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
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
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Read Aloud in the reader on a real WebView, with fake controls: the wiring of toolbar, highlight, scroll-away
 * button, keys and dispose. The decisions are in [BookReadingSurfaceTest]; this needs a scene and WebKit.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class EbookReadAloudUiTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var stage: Stage
    private val views = mutableListOf<EpubFileView>()
    private val executors = mutableListOf<java.util.concurrent.ExecutorService>()

    private class FakeControls : ReaderControls {
        override val toolbar: Node = Label("fake toolbar")
        override val notice: Node = Label("fake notice")
        override val activity: ObservableValue<ViewActivity> = SimpleObjectProperty(ViewActivity.RUNNING)
        override var reading = false
        override var onFinished: () -> Unit = {}
        val keys = mutableListOf<KeyEvent>()
        val jumps = mutableListOf<Int>()
        var handled = false
        var disposed = false

        override fun play() {}

        override fun stop() {}

        override fun jumpTo(offset: Int) {
            jumps += offset
        }

        override fun handleKey(event: KeyEvent): Boolean {
            keys += event
            return handled
        }

        override fun dispose() {
            disposed = true
        }
    }

    @Start
    fun start(stage: Stage) {
        this.stage = stage
        stage.scene = Scene(StackPane(), 900.0, 600.0)
        stage.show()
    }

    @AfterEach
    fun close() {
        fx { views.forEach { it.dispose() } }
        executors.forEach { it.shutdownNow() }
    }

    private fun <T> fx(block: () -> T): T = WaitForAsyncUtils.asyncFx<T> { block() }.get(20, TimeUnit.SECONDS)

    private fun await(condition: () -> Boolean) = WaitForAsyncUtils.waitFor(30, TimeUnit.SECONDS) { fx(condition) }

    private class Opened(
        val view: EpubFileView,
        val controls: FakeControls,
        val surface: BookReadingSurface,
    )

    private fun open(): Opened {
        val chapters =
            listOf("Alpha", "Beta").mapIndexed { index, word ->
                TestChapter(
                    "c$index.xhtml",
                    "<h1>$word</h1>" + (1..40).joinToString("") { "<p>$word paragraph $it. Another sentence $it.</p>" },
                    title = word,
                )
            }
        val file = TestEpub(chapters).write(dir)
        val loader = Executors.newSingleThreadExecutor { Thread(it).apply { isDaemon = true } }
        val writer = Executors.newSingleThreadExecutor { Thread(it).apply { isDaemon = true } }
        executors += loader
        executors += writer
        val controls = FakeControls()
        var surface: ReadAloudSurface? = null
        val view =
            fx {
                EpubFileView(
                    BookLocation(file),
                    BookPosition(MemoryPositions(), writer, JavaDuration.ofMillis(10)),
                    loader,
                    EpubViewConfig(scrollDelay = Duration.millis(20.0)),
                ).also {
                    surface = ReaderReadAloud.attach(it) { s -> controls.also { surface = s } }
                    views += it
                    stage.scene.root = it.node as Parent
                }
            }
        await { view.page != null && view.readAloudSlot.children.isNotEmpty() }
        return Opened(view, controls, surface as BookReadingSurface)
    }

    private class MemoryPositions : PositionStore {
        override fun read(): Int? = null

        override fun write(offset: Int) {}
    }

    private fun highlighted(
        view: EpubFileView,
        name: String,
    ) = view.webView.engine.executeScript(
        "(CSS.highlights.get('mark-$name') ? Array.from(CSS.highlights.get('mark-$name')).map(String).join('|') : null)",
    )

    private fun backButton(view: EpubFileView) = view.node.lookup(".ebook-back-to-reading") as Button

    @Test
    fun `the controls sit in the toolbar and the page shows what they highlight`() {
        val (view, controls, surface) = open().let { Triple(it.view, it.controls, it.surface) }
        fx {
            assertSame(controls.toolbar, view.readAloudSlot.children.single())
            assertSame(controls.notice, view.bannerSlot.children.single())
            assertEquals(ViewActivity.RUNNING, view.activity.value)
        }
        val text = checkNotNull(view.page).text
        val start = text.indexOf("Alpha paragraph 3.")
        fx { surface.highlight(start until start + "Alpha paragraph 3.".length, start until start + 5) }
        await { highlighted(view, "sentence") == "Alpha paragraph 3." }
        assertEquals("Alpha", fx { highlighted(view, "word") })
    }

    @Test
    fun `a wheel on the page while reading shows Back to reading and the button hides it`() {
        val opened = open()
        val view = opened.view
        fx {
            opened.controls.reading = true
            opened.surface.lock(true)
            assertFalse(backButton(view).isVisible)
            assertFalse(view.startClicks)
        }
        // The script tells a scroll right after a resize from the user's: wheel again until it is noticed.
        await {
            view.webView.engine.executeScript("window.dispatchEvent(new WheelEvent('wheel', { deltaY: 100 }))")
            backButton(view).isVisible
        }
        assertEquals("Back to reading", fx { backButton(view).text })
        fx { backButton(view).fire() }
        assertFalse(fx { backButton(view).isVisible })
        assertTrue(fx { opened.surface.following })
    }

    private fun mouse(
        type: javafx.event.EventType<MouseEvent>,
        x: Double,
        y: Double,
        count: Int,
    ) = MouseEvent(
        type,
        x,
        y,
        x,
        y,
        MouseButton.PRIMARY,
        count,
        false,
        false,
        false,
        false,
        true,
        false,
        false,
        false,
        false,
        true,
        null,
    )

    private fun clickFirstParagraph(view: EpubFileView) =
        view.webView.engine.executeScript(
            """var s = document.querySelector('p span[data-o]');
               var r = s.getBoundingClientRect();
               s.dispatchEvent(new MouseEvent('click', {bubbles: true, clientX: r.left + 3, clientY: r.top + 3,
                   button: 0, detail: 1}))""",
        )

    @Test
    fun `a navigation during a reading keeps the clicked place and offers Back to reading`() {
        val opened = open()
        val view = opened.view
        val alpha = checkNotNull(view.page).text.indexOf("Alpha paragraph 2.")
        val beta = checkNotNull(view.page).chapters[1].start
        fx {
            opened.controls.reading = true
            opened.surface.lock(true)
            opened.surface.highlight(alpha until alpha + 5, null)
            val tree = view.contents.node as javafx.scene.control.TreeView<dev.sluide.ebooks.TocItem>
            tree.applyCss()
            tree.layout()
            val cell =
                tree
                    .lookupAll(
                        ".tree-cell",
                    ).filterIsInstance<javafx.scene.control.TreeCell<dev.sluide.ebooks.TocItem>>()
                    .first { it.item?.title == "Beta" }
            tree.selectionModel.select(cell.treeItem)
            javafx.event.Event.fireEvent(cell, mouse(MouseEvent.MOUSE_CLICKED, 5.0, 5.0, 1))
        }
        await { view.currentPosition == beta && view.topOffsetWhole?.let { it in (beta - 150)..(beta + 150) } == true }
        assertTrue(fx { backButton(view).isVisible })
        // The next word of the reading must not take the page back to the sentence it is reading.
        fx { opened.surface.reveal(alpha, alpha + 5) }
        assertTrue(fx { view.topOffsetWhole!! } >= beta - 150)
    }

    @Test
    fun `a double-click on the page while reading jumps to the offset under it`() {
        val opened = open()
        val view = opened.view
        val text = checkNotNull(view.page).text
        val first = text.indexOf("Alpha paragraph 1.")
        fx {
            opened.controls.reading = true
            opened.surface.readingText()
        }
        val rect =
            fx {
                view.webView.engine.executeScript(
                    """var r = document.querySelector('p span[data-o]').getBoundingClientRect();
                       (r.left + 3) + ',' + (r.top + 3)""",
                ) as String
            }.split(",").map { it.toDouble() }
        // Event coordinates are scene coordinates for a fired event; the view converts them to its own.
        val origin = fx { view.webView.localToScene(0.0, 0.0) }
        val event = mouse(MouseEvent.MOUSE_CLICKED, origin.x + rect[0], origin.y + rect[1], 2)
        // A consumed event never reaches the handlers behind the filter.
        var reached = 0
        fx { view.webView.addEventHandler(MouseEvent.MOUSE_CLICKED) { reached++ } }
        fx { view.webView.fireEvent(event) }
        assertEquals(1, opened.controls.jumps.size)
        assertTrue(opened.controls.jumps.single() in first..(first + 3), "${opened.controls.jumps}")
        assertEquals(0, reached)
    }

    @Test
    fun `a click on the page moves the start mark only while not locked`() {
        val opened = open()
        val view = opened.view
        val before = fx { view.startOffset() }
        fx {
            opened.surface.lock(true)
            clickFirstParagraph(view)
        }
        assertEquals(before, fx { view.startOffset() })
        fx {
            opened.surface.lock(false)
            clickFirstParagraph(view)
        }
        await { view.startOffset() != before }
        assertTrue(fx { checkNotNull(view.page).text.substring(view.startOffset()).startsWith("Alpha paragraph 1.") })
    }

    @Test
    fun `keys typed into a text field are not taken while reading`() {
        val opened = open()
        val view = opened.view
        val field = javafx.scene.control.TextField()
        fx { view.rightPane.children += field }
        opened.controls.handled = true
        val space = KeyEvent(KeyEvent.KEY_PRESSED, " ", " ", KeyCode.SPACE, false, false, false, false)
        fx { field.fireEvent(space) }
        assertTrue(opened.controls.keys.isEmpty())
        assertFalse(space.isConsumed)
    }

    @Test
    fun `a key press reaches the controls and is consumed when they handle it, dispose disposes them`() {
        val opened = open()
        val view = opened.view
        val space = { KeyEvent(KeyEvent.KEY_PRESSED, " ", " ", KeyCode.SPACE, false, false, false, false) }
        // A consumed event never reaches the handlers below the filter.
        var reached = 0
        fx { view.node.addEventHandler(KeyEvent.KEY_PRESSED) { reached++ } }
        fx { view.node.fireEvent(space()) }
        assertEquals(1, opened.controls.keys.size)
        assertEquals(1, reached)
        opened.controls.handled = true
        fx { view.node.fireEvent(space()) }
        assertEquals(2, opened.controls.keys.size)
        assertEquals(1, reached)
        // The typed and released events of a handled press are swallowed too, then keys pass again.
        var typed = 0
        fx { view.node.addEventHandler(KeyEvent.KEY_TYPED) { typed++ } }
        fx {
            view.node.fireEvent(
                KeyEvent(KeyEvent.KEY_TYPED, " ", " ", KeyCode.UNDEFINED, false, false, false, false),
            )
        }
        assertEquals(0, typed)
        fx { view.node.fireEvent(KeyEvent(KeyEvent.KEY_RELEASED, " ", " ", KeyCode.SPACE, false, false, false, false)) }
        fx {
            view.node.fireEvent(
                KeyEvent(KeyEvent.KEY_TYPED, " ", " ", KeyCode.UNDEFINED, false, false, false, false),
            )
        }
        assertEquals(1, typed)
        fx { view.dispose() }
        assertTrue(opened.controls.disposed)
    }
}

private typealias JavaDuration = java.time.Duration
