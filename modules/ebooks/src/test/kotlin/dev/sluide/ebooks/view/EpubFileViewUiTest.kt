package dev.sluide.ebooks.view

import dev.sluide.ebooks.TestChapter
import dev.sluide.ebooks.TestEpub
import dev.sluide.ebooks.TestToc
import dev.sluide.ebooks.TocItem
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.projectdata.ProjectDataStore
import javafx.application.ColorScheme
import javafx.event.Event
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.TreeCell
import javafx.scene.control.TreeView
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.stage.Stage
import javafx.util.Duration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.time.Duration as JavaDuration

/**
 * The reader on a real WebView: loading, the Contents panel, the toolbar toggles, notices, and the page's links and
 * clicks reaching the view through the script bridge. Needs a scene and WebKit; the logic is in the unit tests.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
@Suppress("UNCHECKED_CAST")
class EpubFileViewUiTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var stage: Stage
    private val views = mutableListOf<EpubFileView>()
    private val executors = mutableListOf<java.util.concurrent.ExecutorService>()

    private class MemoryStore(
        var saved: Int? = null,
    ) : PositionStore {
        override fun read() = saved

        override fun write(offset: Int) {
            saved = offset
        }
    }

    private class FakeHost : FileViewHost {
        val webPages = CopyOnWriteArrayList<URI>()

        override fun openFile(file: Path) = false

        override fun openWebPage(uri: URI) {
            webPages += uri
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

    private fun paragraphs(
        word: String,
        extra: String = "",
    ) = "<h1>$word</h1>$extra" +
        (1..40).joinToString("") { "<p>$word paragraph $it. A second sentence of the paragraph $it.</p>" }

    private val chapters =
        listOf(
            TestChapter("c0.xhtml", paragraphs("Alpha"), title = "Alpha"),
            TestChapter(
                "c1.xhtml",
                paragraphs(
                    "Beta",
                    """<p><a href="https://example.com/out">outside</a> <a href="c2.xhtml#target">jump</a></p>""",
                ),
                title = "Beta",
            ),
            TestChapter(
                "c2.xhtml",
                "<h1>Gamma</h1><h2 id=\"target\">Target</h2>" + paragraphs("Gamma"),
                title = "Gamma",
            ),
        )

    private lateinit var lastFile: Path
    private lateinit var lastLoader: java.util.concurrent.ExecutorService

    private fun open(
        epub: TestEpub = TestEpub(chapters),
        store: MemoryStore = MemoryStore(),
        config: EpubViewConfig = EpubViewConfig(scrollDelay = Duration.millis(20.0)),
        host: FileViewHost = FakeHost(),
        saveDelay: JavaDuration = JavaDuration.ofMillis(10),
    ): EpubFileView = openFile(epub.write(dir, "book${views.size}.epub"), store, config, host, saveDelay)

    private fun openFile(
        file: Path,
        store: MemoryStore = MemoryStore(),
        config: EpubViewConfig = EpubViewConfig(scrollDelay = Duration.millis(20.0)),
        host: FileViewHost = FakeHost(),
        saveDelay: JavaDuration = JavaDuration.ofMillis(10),
    ): EpubFileView {
        val loader = Executors.newSingleThreadExecutor { Thread(it).apply { isDaemon = true } }
        val writer = Executors.newSingleThreadExecutor { Thread(it).apply { isDaemon = true } }
        executors += loader
        executors += writer
        lastFile = file
        lastLoader = loader
        val position = BookPosition(store, writer, saveDelay)
        return fx {
            EpubFileView(BookLocation(file), position, loader, config).also {
                it.host = host
                views += it
                stage.scene.root = it.node as javafx.scene.Parent
            }
        }
    }

    /**
     * Scrolls the page like a user would, again and again until [done]: the page's script tells a scroll that a resize
     * caused from the user's, and the layout of a fresh page may still be going on.
     */
    private fun scrollPage(
        view: EpubFileView,
        y: Int,
        done: () -> Boolean,
    ) {
        await {
            (view.webView.engine.executeScript("document.documentElement.scrollHeight") as Number).toInt() >
                y + 1000
        }
        // The script counts a scroll during or just after a resize (two frames) as not the user's: let some frames pass.
        settleFrames(view)
        // One scroll, then waiting: scrolling again restarts the view's debounce, so a poll loop that scrolls on every
        // round could keep it from ever firing.
        repeat(5) { attempt ->
            fx { view.webView.engine.executeScript("window.scrollTo(0, ${y + attempt * 10})") }
            try {
                WaitForAsyncUtils.waitFor(3, TimeUnit.SECONDS) { fx(done) }
                return
            } catch (_: java.util.concurrent.TimeoutException) {
                // The scroll was taken for a resize's echo or lost: scroll again.
            }
        }
        assertTrue(fx(done), "the scroll was not noticed")
    }

    /** Waits for a few frames, so that the scroll events of what the test did have been delivered and handled. */
    private fun settleFrames(view: EpubFileView) {
        val engine = view.webView.engine
        fx {
            engine.executeScript(
                "window.__frames = 0; (function f() { if (++window.__frames < 8) requestAnimationFrame(f) })()",
            )
        }
        await { engine.executeScript("window.__frames") == 8 }
        fx {}
    }

    private fun EpubFileView.loadedPage() = await { page != null }

    @Test
    fun `opens a book, shows its contents and the progress`() {
        val view = open()
        view.loadedPage()
        fx {
            assertEquals(listOf("Alpha", "Beta", "Gamma"), view.contents.entries.map { it.title })
            assertTrue(
                view.bar.progress.text
                    .startsWith("Alpha · "),
                view.bar.progress.text,
            )
            assertFalse(view.overlayShown)
        }
    }

    private fun entryCell(
        tree: TreeView<TocItem>,
        title: String,
    ): TreeCell<TocItem> = checkNotNull(entryCellOrNull(tree, title)) { "no cell for $title" }

    private fun entryCellOrNull(
        tree: TreeView<TocItem>,
        title: String,
    ): TreeCell<TocItem>? {
        tree.applyCss()
        tree.layout()
        return tree
            .lookupAll(
                ".tree-cell",
            ).filterIsInstance<TreeCell<TocItem>>()
            .firstOrNull { it.item?.title == title }
    }

    /** Waits until the tree has laid out a cell for [title]: under load the first layout pass can come later. */
    private fun awaitEntry(
        tree: () -> TreeView<TocItem>,
        title: String,
    ) = await { entryCellOrNull(tree(), title) != null }

    @Suppress("UNCHECKED_CAST")
    private fun contentsTree(view: EpubFileView) = view.contents.node as TreeView<TocItem>

    private fun click(count: Int = 1) =
        MouseEvent(
            MouseEvent.MOUSE_CLICKED,
            5.0,
            5.0,
            5.0,
            5.0,
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

    @Test
    fun `clicking a contents entry jumps there and its selection stays when a child shares the offset`() {
        val toc =
            listOf(
                TestToc("Alpha", "c0.xhtml"),
                TestToc("Parent", "c1.xhtml", listOf(TestToc("Child", "c1.xhtml"))),
                TestToc("Gamma", "c2.xhtml"),
            )
        val view = open(TestEpub(chapters, toc = toc))
        view.loadedPage()
        awaitEntry({ contentsTree(view) }, "Parent")
        fx {
            val tree = view.contents.node as TreeView<TocItem>
            val cell = entryCell(tree, "Parent")
            tree.selectionModel.select(cell.treeItem)
            Event.fireEvent(cell, click())
        }
        val beta = fx { assertNotNull(view.page).chapters[1].start }
        await { view.currentPosition == beta }
        await { view.topOffsetWhole?.let { it in (beta - 150)..(beta + 150) } == true }
        // The scroll that follows the jump must not select the child, which is "deeper" at the same offset.
        settleFrames(view)
        fx { assertEquals("Parent", view.contents.selected?.title) }
    }

    @Test
    fun `the contents panel jumps once on a click or Enter, never on the arrow keys or empty space`() {
        val jumps = mutableListOf<Int>()
        val panel = ContentsPanel { jumps += it }
        val items = listOf(TocItem("One", 0, emptyList()), TocItem("Two", 50, emptyList()))
        val tree = panel.node as TreeView<TocItem>
        fx {
            panel.show(items)
            stage.scene.root = tree
        }
        awaitEntry({ tree }, "Two")
        fx {
            // What the arrow keys do: they move the selection.
            tree.selectionModel.select(1)
            assertEquals(emptyList(), jumps)
            Event.fireEvent(entryCell(tree, "Two"), click())
            assertEquals(listOf(50), jumps)
            tree.selectionModel.select(0)
            tree.fireEvent(KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false))
            assertEquals(listOf(50, 0), jumps)
            Event.fireEvent(tree, click())
            assertEquals(listOf(50, 0), jumps)
        }
    }

    @Test
    fun `the contents toggle hides and shows the panel and keeps the divider`() {
        val view = open()
        view.loadedPage()
        fx {
            assertTrue(view.bar.contents.isSelected)
            assertTrue(view.splitPane.items.contains(view.contents.node))
            view.splitPane.setDividerPosition(0, 0.4)
        }
        fx { view.bar.contents.isSelected = false }
        fx { assertFalse(view.splitPane.items.contains(view.contents.node)) }
        fx { view.bar.contents.isSelected = true }
        await { view.splitPane.items.contains(view.contents.node) && view.splitPane.dividerPositions[0] in 0.39..0.41 }
    }

    @Test
    fun `a drm book and an invalid book show a notice instead of the page`() {
        val drm = open(TestEpub(chapters, rights = true))
        await { drm.overlayShown && drm.overlayLabel.text.startsWith("This book is DRM-protected") }
        fx { assertEquals("This book is DRM-protected and cannot be opened.", drm.overlayLabel.text) }
        val bad = dir.resolve("bad.epub")
        Files.writeString(bad, "not a zip")
        val view = openFile(bad)
        await { view.overlayLabel.text.startsWith("Not a valid EPUB: ") }
        fx { assertNull(view.page) }
    }

    @Test
    fun `a fixed-layout book shows a dismissible notice bar`() {
        val view = open(TestEpub(chapters, fixedLayout = true))
        view.loadedPage()
        fx {
            assertTrue(view.fixedLayoutBar.isVisible)
            ((view.fixedLayoutBar as javafx.scene.layout.HBox).children.last() as Button).fire()
            assertFalse(view.fixedLayoutBar.isVisible)
        }
    }

    @Test
    fun `the saved position is restored and marked as the start`() {
        val text = open().also { it.loadedPage() }.let { fx { assertNotNull(it.page).text } }
        val saved = text.indexOf("Beta paragraph 30.")
        val view = open(store = MemoryStore(saved))
        view.loadedPage()
        fx {
            assertEquals(saved, view.currentPosition)
            assertTrue(view.startOffset() in (saved - 40)..saved, "start ${view.startOffset()} for $saved")
        }
        await { view.topOffsetWhole?.let { it in (saved - 150)..(saved + 150) } == true }
    }

    @Test
    fun `toggling book style reloads the page and keeps the top of the view`() {
        val view = open()
        view.loadedPage()
        val loads = AtomicInteger()
        fx { view.onPageLoaded += { loads.incrementAndGet() } }
        val gamma = fx { assertNotNull(view.page).chapters[2].start }
        fx { view.jumpTo(gamma + 300) }
        await { view.topOffsetWhole?.let { it in (gamma + 150)..(gamma + 450) } == true }
        fx { view.bar.bookStyle.isSelected = true }
        await { loads.get() == 1 && view.page != null }
        await { view.topOffsetWhole?.let { it in (gamma + 150)..(gamma + 450) } == true }
    }

    /** A render that fails with [failure] while [failing] is set, else builds the page as usual. */
    private class FailingRender(
        val failure: Throwable,
    ) {
        @Volatile var failing = false
        val calls = AtomicInteger()

        val config =
            EpubViewConfig(
                scrollDelay = Duration.millis(20.0),
                largeBookChars = 100,
                render = { loading, book, chapter, ownStyle, dark ->
                    calls.incrementAndGet()
                    if (failing) throw failure
                    loading.render(book, chapter, ownStyle, dark)
                },
            )
    }

    private fun noticeText(view: EpubFileView) =
        (view.fixedLayoutBar as javafx.scene.layout.HBox).children.first() as Label

    @Test
    fun `a chapter that cannot be built keeps the page and the position and says why`() {
        val render = FailingRender(IllegalStateException("boom"))
        val view = open(config = render.config)
        view.loadedPage()
        val before = fx { view.bar.progress.text }
        render.failing = true
        fx { view.bar.next.fire() }
        await { view.fixedLayoutBar.isVisible }
        fx {
            assertEquals("Could not show this chapter: boom", noticeText(view).text)
            assertEquals(before, view.bar.progress.text)
            assertNotNull(view.page)
        }
        render.failing = false
        fx { view.bar.next.fire() }
        await { fx { view.bar.progress.text }.startsWith("Beta · ") }
    }

    @Test
    fun `a Book Style change that runs out of memory keeps the page and puts the toggle back`() {
        val render = FailingRender(OutOfMemoryError())
        val view = open(config = render.config)
        view.loadedPage()
        val calls = render.calls.get()
        render.failing = true
        fx { view.bar.bookStyle.isSelected = true }
        await { view.fixedLayoutBar.isVisible }
        // A retry would be queued on the single loader thread: wait until everything queued before this has run.
        lastLoader.submit {}.get(20, TimeUnit.SECONDS)
        fx { }
        fx {
            assertEquals("This chapter is too large to show.", noticeText(view).text)
            assertFalse(view.bar.bookStyle.isSelected)
            assertNotNull(view.page)
        }
        // Putting the toggle back is not a new request: one failed build, no retry.
        assertEquals(calls + 1, render.calls.get())
    }

    @Test
    fun `a changed file is read again and the position kept, a broken one leaves the book as it is`() {
        val view = open()
        view.loadedPage()
        val loads = AtomicInteger()
        fx { view.onPageLoaded += { loads.incrementAndGet() } }
        val gamma = fx { assertNotNull(view.page).chapters[2].start }
        fx { view.jumpTo(gamma + 300) }
        await { view.topOffsetWhole?.let { it in (gamma + 150)..(gamma + 450) } == true }
        val longer =
            chapters.dropLast(1) + chapters.last().copy(body = chapters.last().body + "<p>Added at the end.</p>")
        TestEpub(longer).write(dir, lastFile.fileName.toString())
        fx { view.reload() }
        await { loads.get() == 1 }
        await { view.topOffsetWhole?.let { it in (gamma + 150)..(gamma + 450) } == true }
        fx { assertTrue(assertNotNull(view.page).text.endsWith("Added at the end.")) }
        Files.writeString(lastFile, "not a zip, and longer than before to change the size of the file")
        fx { view.reload() }
        lastLoader.submit {}.get(10, TimeUnit.SECONDS) // the result of the broken read is queued behind this
        fx {}
        fx {
            assertFalse(view.overlayShown)
            assertEquals(1, loads.get())
            assertNotNull(view.page)
        }
    }

    @Test
    fun `the position is saved after a scroll, after a contents jump and when the tab closes`() {
        val store = MemoryStore()
        val view = open(store = store, saveDelay = JavaDuration.ofMillis(10))
        view.loadedPage()
        scrollPage(view, 3000) { store.saved?.let { it > 0 } == true }
        val gamma = fx { assertNotNull(view.page).chapters[2].start }
        awaitEntry({ contentsTree(view) }, "Gamma")
        fx {
            val tree = view.contents.node as TreeView<TocItem>
            val cell = entryCell(tree, "Gamma")
            tree.selectionModel.select(cell.treeItem)
            Event.fireEvent(cell, click())
        }
        await { store.saved?.let { it in gamma..(gamma + 60) } == true }

        fx { view.dispose() }
        val closing = MemoryStore()
        val second = open(store = closing, saveDelay = JavaDuration.ofSeconds(60))
        second.loadedPage()
        scrollPage(second, 3000) { second.currentPosition > 0 }
        assertNull(closing.saved)
        val shownAt = fx { second.currentPosition.also { second.dispose() } }
        assertEquals(shownAt, closing.saved)
    }

    @Test
    fun `the page follows the dark and light theme at runtime`() {
        val view = open()
        view.loadedPage()

        fun dark() = fx { view.webView.engine.executeScript("document.body.classList.contains('dark')") }
        assertEquals(false, dark())
        fx { stage.scene.preferences.colorScheme = ColorScheme.DARK }
        await { dark() == true }
        fx { stage.scene.preferences.colorScheme = ColorScheme.LIGHT }
        await { dark() == false }
    }

    @Test
    fun `a node in the right pane becomes a split item and leaves with it`() {
        val view = open()
        view.loadedPage()
        val side = Label("side")
        fx { assertFalse(view.splitPane.items.contains(view.rightPane)) }
        fx { view.rightPane.children += side }
        fx { assertEquals(view.rightPane, view.splitPane.items.last()) }
        // It opens at its own width, not at half of the split (which squeezed the page).
        await { view.rightPane.width in (ReaderSplit.RIGHT_WIDTH - 20)..(ReaderSplit.RIGHT_WIDTH + 20) }
        fx { view.rightPane.children -= side }
        fx { assertFalse(view.splitPane.items.contains(view.rightPane)) }
    }

    @Test
    fun `clicking a link to the web opens it through the host and the web view does not navigate`() {
        val host = FakeHost()
        val view = open(host = host)
        view.loadedPage()
        val loads = AtomicInteger()
        val before = fx { view.webView.engine.location }
        fx {
            view.onPageLoaded += { loads.incrementAndGet() }
            view.webView.engine.executeScript("document.querySelector('a[href^=\"https\"]').click()")
        }
        await { host.webPages.isNotEmpty() }
        assertEquals("https://example.com/out", host.webPages.single().toString())
        Thread.sleep(300) // negative check: a navigation would show up as a changed location or a reload
        fx {
            assertEquals(before, view.webView.engine.location)
            assertEquals(0, loads.get())
        }
    }

    @Test
    fun `a link within the book moves the progress and the contents`() {
        val view = open()
        view.loadedPage()
        fx { view.webView.engine.executeScript("document.querySelector('a[href^=\"#\"]').click()") }
        await {
            view.bar.progress.text
                .startsWith("Gamma · ")
        }
        await { view.contents.selected?.title == "Gamma" }
    }

    @Test
    fun `a navigation the page does by itself is cancelled and the book shown again`() {
        val view = open()
        view.loadedPage()
        val loads = AtomicInteger()
        fx { view.onPageLoaded += { loads.incrementAndGet() } }
        val other = Files.writeString(dir.resolve("other.html"), "<html><body>other</body></html>")
        // The engine is told to load it: a script of the page cannot navigate to a file, but any navigation counts.
        fx { view.webView.engine.load(other.toUri().toString()) }
        await { loads.get() >= 1 && view.page != null }
        fx {
            assertFalse(
                view.webView.engine.location
                    .startsWith("file:"),
            )
        }
    }

    @Test
    fun `a click on text marks the sentence as the start`() {
        val view = open()
        view.loadedPage()
        fx {
            view.webView.engine.executeScript(
                """var s = document.querySelector('p span[data-o]');
                   var r = s.getBoundingClientRect();
                   s.dispatchEvent(new MouseEvent('click', {bubbles: true, clientX: r.left + 3, clientY: r.top + 3,
                       button: 0, detail: 1}))""",
            )
        }
        await { view.startOffset() > 0 }
        fx {
            val text = assertNotNull(view.page).text
            val start = view.startOffset()
            assertTrue(text.substring(start).startsWith("Alpha paragraph 1."), text.substring(start, start + 30))
        }
    }

    @Test
    fun `a large book shows one chapter at a time with whole-book offsets`() {
        val view = open(config = EpubViewConfig(scrollDelay = Duration.millis(20.0), largeBookChars = 100))
        view.loadedPage()
        val loads = AtomicInteger()
        fx {
            assertTrue(view.bar.next.isVisible)
            assertTrue(view.bar.previous.isDisable)
            assertTrue(
                view.bar.progress.text
                    .startsWith("Alpha · "),
            )
            view.onPageLoaded += { loads.incrementAndGet() }
            view.bar.next.fire()
        }
        await {
            loads.get() == 1 &&
                view.bar.progress.text
                    .startsWith("Beta · ")
        }
        val gamma = fx { assertNotNull(view.page).chapters[2].start }
        fx { view.jumpTo(gamma + 10) }
        await { loads.get() == 2 }
        fx {
            assertEquals(gamma + 10, view.currentPosition)
            assertTrue(view.bar.next.isDisable)
        }
        await { view.topOffsetWhole?.let { it >= gamma } == true }
    }

    @Test
    fun `in a large book a link to another chapter loads that chapter`() {
        val view = open(config = EpubViewConfig(scrollDelay = Duration.millis(20.0), largeBookChars = 100))
        view.loadedPage()
        val loads = AtomicInteger()
        fx {
            view.onPageLoaded += { loads.incrementAndGet() }
            view.bar.next.fire()
        }
        await {
            loads.get() == 1 &&
                view.bar.progress.text
                    .startsWith("Beta · ")
        }
        val target = fx { assertNotNull(view.page).anchors.getValue("ch2-target") }
        fx { view.webView.engine.executeScript("document.querySelector('a[href^=\"#\"]').click()") }
        await {
            loads.get() == 2 &&
                view.bar.progress.text
                    .startsWith("Gamma · ")
        }
        fx { assertEquals(target, view.currentPosition) }
        await { view.topOffsetWhole?.let { it in (target - 150)..(target + 150) } == true }
    }

    @Test
    fun `user scroll listeners hear a wheel but not a jump`() {
        val view = open()
        view.loadedPage()
        val heard = AtomicInteger()
        fx { view.onUserScroll += { heard.incrementAndGet() } }
        val gamma = fx { assertNotNull(view.page).chapters[2].start }
        fx { view.jumpTo(gamma + 300) }
        await { view.topOffsetWhole?.let { it in (gamma + 150)..(gamma + 450) } == true }
        settleFrames(view)
        assertEquals(0, heard.get())
        fx { view.webView.engine.executeScript("window.dispatchEvent(new WheelEvent('wheel', { deltaY: 100 }))") }
        await { heard.get() == 1 }
    }

    @Test
    fun `a pending position is written under the new name after the book was moved`() {
        val project = Files.createDirectory(dir.resolve("project"))
        val old = TestEpub(chapters).write(project, "old.epub")
        val data = ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())
        val location = BookLocation(old).apply { projectFolder = project }
        val writer = Executors.newSingleThreadExecutor { Thread(it).apply { isDaemon = true } }
        val loader = Executors.newSingleThreadExecutor { Thread(it).apply { isDaemon = true } }
        executors += writer
        executors += loader
        val position = BookPosition(ProjectDataPositionStore(data, location), writer, JavaDuration.ofMillis(400))
        val view =
            fx {
                EpubFileView(location, position, loader, EpubViewConfig(scrollDelay = Duration.millis(20.0))).also {
                    views += it
                    stage.scene.root = it.node as javafx.scene.Parent
                }
            }
        view.loadedPage()
        val gamma = fx { assertNotNull(view.page).chapters[2].start }
        fx { view.jumpTo(gamma) }
        // What MainWindow does on a move in the explorer: the data is re-keyed first, then the tab is told.
        val moved = project.resolve("new.epub")
        Files.move(old, moved)
        data.moved(old, moved, project)
        assertTrue(fx { view.moved(moved) })
        await {
            data
                .fileData(moved, project)
                ?.get("ebook")
                ?.get("offset")
                ?.asInt() == gamma
        }
        assertNull(data.fileData(old, project))
    }
}
