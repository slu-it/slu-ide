package dev.sluide.ebooks.view

import dev.sluide.ebooks.TestChapter
import dev.sluide.ebooks.TestEpub
import dev.sluide.ebooks.TestToc
import dev.sluide.ebooks.TocItem
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.control.TreeView
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.stage.Stage
import javafx.util.Duration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.time.Duration as JavaDuration

/**
 * The reader of a large book (chapter mode) on a real WebView, with chapters that have no text (a cover, a plate):
 * what the user chooses is what is shown, and a chapter that cannot be built leaves the reader usable. A WebView and
 * the view's page loading are needed for both; the decisions are unit-tested in `ReadingLogicTest`.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
@Suppress("UNCHECKED_CAST")
class EbookChapterModeUiTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var stage: Stage
    private val views = mutableListOf<EpubFileView>()
    private val executors = mutableListOf<java.util.concurrent.ExecutorService>()

    private class MemoryStore(
        @Volatile var saved: SavedPosition? = null,
    ) : PositionStore {
        override fun read() = saved

        override fun write(position: SavedPosition) {
            saved = position
        }
    }

    @Start
    fun start(stage: Stage) {
        this.stage = stage
        stage.scene = Scene(javafx.scene.layout.StackPane(), 900.0, 600.0)
        stage.show()
    }

    @AfterEach
    fun close() {
        fx { views.forEach { it.dispose() } }
        executors.forEach { it.shutdownNow() }
    }

    private fun <T> fx(block: () -> T): T = WaitForAsyncUtils.asyncFx<T> { block() }.get(20, TimeUnit.SECONDS)

    private fun await(condition: () -> Boolean) = WaitForAsyncUtils.waitFor(30, TimeUnit.SECONDS) { fx(condition) }

    private fun paragraphs(word: String) =
        "<h1>$word</h1>" + (1..20).joinToString("") { "<p>$word paragraph $it with some more words.</p>" }

    private fun image(name: String) = "<p><img src=\"images/$name.png\" alt=\"\"/></p>"

    /**
     * Spine: 0 cover, 1 Alpha, 2 and 3 plates, 4 Beta. The cover and the plates are images only: no text, so no offset
     * of their own. The cover shares offset 0 with Alpha, the plates share one with each other.
     */
    private val epub =
        TestEpub(
            listOf(
                TestChapter("cover.xhtml", image("cover"), title = "Cover"),
                TestChapter(
                    "alpha.xhtml",
                    paragraphs("Alpha") + "<p><a href=\"plate-a.xhtml\">see the plate</a></p>",
                    title = "Alpha",
                ),
                TestChapter("plate-a.xhtml", image("a"), title = "Plate A"),
                TestChapter("plate-b.xhtml", image("b"), title = "Plate B"),
                TestChapter("beta.xhtml", paragraphs("Beta"), title = "Beta"),
            ),
            toc =
                listOf(
                    "Cover" to "cover",
                    "Alpha" to "alpha",
                    "Plate A" to "plate-a",
                    "Plate B" to "plate-b",
                    "Beta" to "beta",
                ).map { (title, file) -> TestToc(title, "$file.xhtml") },
            resources =
                mapOf(
                    "images/cover.png" to TestEpub.PNG,
                    "images/a.png" to TestEpub.PNG,
                    "images/b.png" to TestEpub.PNG,
                ),
        )

    private fun config(render: (Int?) -> Unit = {}) =
        EpubViewConfig(
            scrollDelay = Duration.millis(20.0),
            largeBookChars = 100,
            render = { loading, book, chapter, ownStyle, dark ->
                render(chapter)
                loading.render(book, chapter, ownStyle, dark)
            },
        )

    private fun open(
        store: MemoryStore = MemoryStore(),
        config: EpubViewConfig = config(),
    ): EpubFileView {
        val file = epub.write(dir, "book${views.size}.epub")
        val loader = Executors.newSingleThreadExecutor { Thread(it).apply { isDaemon = true } }
        val writer = Executors.newSingleThreadExecutor { Thread(it).apply { isDaemon = true } }
        executors += listOf(loader, writer)
        val position = BookPosition(store, writer, JavaDuration.ofMillis(10))
        return fx {
            EpubFileView(BookLocation(file), position, loader, config).also {
                views += it
                stage.scene.root = it.node as javafx.scene.Parent
            }
        }
    }

    private fun EpubFileView.loadedPage() = await { page != null }

    /** Whether the shown page is the one of the chapter with spine index [index] (its section is there); FX thread. */
    private fun EpubFileView.shows(index: Int) =
        try {
            webView.engine.executeScript("document.getElementById('ch$index') !== null") == true
        } catch (_: netscape.javascript.JSException) {
            false
        }

    /** What the Contents entries and the links of a plate point at; the same for both plates. */
    private fun EpubFileView.plateOffset() = assertNotNull(page).anchors.getValue("ch2")

    private fun enterOn(
        view: EpubFileView,
        title: String,
    ) = fx {
        val tree = view.contents.node as TreeView<TocItem>
        val item = view.contents.entries.first { it.title == title }

        fun find(from: javafx.scene.control.TreeItem<TocItem>): javafx.scene.control.TreeItem<TocItem>? =
            if (from.value === item) from else from.children.firstNotNullOfOrNull { find(it) }
        tree.selectionModel.select(find(tree.root))
        tree.fireEvent(KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false))
    }

    @Test
    fun `a contents entry of a chapter without text shows that chapter and saves it`() {
        val store = MemoryStore()
        val view = open(store)
        view.loadedPage()
        // Without a saved position the book opens on its cover, though Alpha starts at the same offset.
        await { view.shows(0) }
        enterOn(view, "Plate A")
        await { view.shows(2) }
        val offset = fx { view.plateOffset() }
        await { store.saved == SavedPosition(offset, 2) }
        enterOn(view, "Plate B")
        await { view.shows(3) }
        await { store.saved == SavedPosition(offset, 3) }
        enterOn(view, "Cover")
        await { view.shows(0) }
        await { store.saved == SavedPosition(0, 0) }
    }

    @Test
    fun `a link to a chapter without text shows that chapter`() {
        val view = open()
        view.loadedPage()
        enterOn(view, "Alpha")
        await { view.shows(1) }
        fx { view.webView.engine.executeScript("document.querySelector('a[href^=\"#\"]').click()") }
        await { view.shows(2) }
        fx { assertFalse(view.shows(3)) }
    }

    @Test
    fun `a saved position names the chapter without text, one saved without a chapter opens the one by offset`() {
        val probe = open().also { it.loadedPage() }
        val offset = fx { probe.plateOffset() }
        val withChapter = open(MemoryStore(SavedPosition(offset, 2)))
        withChapter.loadedPage()
        await { withChapter.shows(2) }
        // Data of an older version has no chapter: the last chapter that starts at the offset is shown.
        val without = open(MemoryStore(SavedPosition(offset)))
        without.loadedPage()
        await { without.shows(3) }
    }

    @Test
    fun `progress and the contents selection name the chapter without text that is shown`() {
        val view = open()
        view.loadedPage()
        await { view.shows(0) }
        await {
            view.bar.progress.text
                .startsWith("Cover · ")
        }
        fx { assertEquals("Cover", view.contents.selected?.title) }
        enterOn(view, "Plate A")
        await { view.shows(2) }
        // The plates share their offset: Plate B is the later entry there and must not take the label or the selection.
        await {
            view.bar.progress.text
                .startsWith("Plate A · ")
        }
        fx { assertEquals("Plate A", view.contents.selected?.title) }
        enterOn(view, "Plate B")
        await { view.shows(3) }
        await {
            view.bar.progress.text
                .startsWith("Plate B · ")
        }
        fx { assertEquals("Plate B", view.contents.selected?.title) }
    }

    @Test
    fun `a book read again after a change keeps a chapter without text shown`() {
        val view = open()
        view.loadedPage()
        val file = dir.resolve("book${views.size - 1}.epub")
        for ((title, index) in listOf("Plate A" to 2, "Cover" to 0)) {
            enterOn(view, title)
            await { view.shows(index) }
            val loads = AtomicInteger()
            fx { view.onPageLoaded += { loads.incrementAndGet() } }
            // A new modification time is what tells the view that the book changed.
            val time = FileTime.fromMillis(System.currentTimeMillis() + 10_000L * (index + 1))
            Files.setLastModifiedTime(file, time)
            fx { view.reload() }
            await { loads.get() == 1 }
            await { view.shows(index) }
        }
    }

    @Test
    fun `a first page that cannot be built leaves toolbar and contents usable and is not saved`() {
        // The saved position is in chapter 1 (Alpha), which cannot be built: the first page of the view fails.
        val saved = MemoryStore(SavedPosition(0, 1))
        val renders = CopyOnWriteArrayList<Int?>()
        val failing =
            open(
                saved,
                config { chapter ->
                    renders += chapter
                    if (chapter == 1) throw IllegalStateException("boom")
                },
            )
        await { failing.loadedBook != null }
        val notice = fx { failing.node.lookup(".ebook-page-notice") as Label }
        await { notice.isVisible }
        fx {
            assertEquals("Could not show this chapter: boom", notice.text)
            assertFalse(failing.overlayShown)
            assertTrue(failing.bar.node.isVisible)
            assertTrue(failing.contents.node.isVisible)
            assertFalse(failing.webView.isVisible)
            assertFalse(failing.bar.previous.isDisable)
        }
        // Book Style builds the failing chapter again (not chapter 0), which fails again and says so.
        fx { failing.bar.bookStyle.isSelected = true }
        await { renders.size == 2 }
        assertEquals(listOf<Int?>(1, 1), renders.toList())
        // A negative check without a signal: nothing is written for a failed page, so wait longer than the write delay.
        Thread.sleep(200)
        assertEquals(SavedPosition(0, 1), saved.saved)
        // Next opens the chapter after the failing one.
        fx { failing.bar.next.fire() }
        await { failing.shows(2) }
        fx {
            assertFalse(notice.isVisible)
            assertTrue(failing.webView.isVisible)
        }
        await { saved.saved?.chapter == 2 }
        enterOn(failing, "Beta")
        await { failing.shows(4) }
        await { saved.saved?.chapter == 4 }
    }
}
