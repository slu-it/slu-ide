package dev.sluide.findinfiles

import dev.sluide.fileviewers.DefaultTextFileViewer
import dev.sluide.fileviewers.FileViewers
import dev.sluide.findinfiles.search.FileHits
import dev.sluide.findinfiles.search.LineHit
import dev.sluide.findinfiles.ui.FindAnchor
import dev.sluide.textediting.SearchOptions
import javafx.beans.property.SimpleStringProperty
import javafx.geometry.BoundingBox
import javafx.stage.Stage
import javafx.stage.Window
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executor
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FindInFilesTest {
    @TempDir
    lateinit var dir: Path

    private val view = FakeFindView()
    private val timers = mutableListOf<Runnable>()
    private var scheduled = 0
    private val background = mutableListOf<Runnable>()
    private val fx = mutableListOf<Runnable>()
    private var roots = listOf<Path>()
    private var unsaved = emptyMap<Path, String>()
    private var openerCalls = 0
    private val walked = mutableListOf<Path>()

    private val find =
        FindInFiles(
            DefaultTextFileViewer().let { FileViewers(listOf(it), it) },
            { view },
            SimpleStringProperty(""),
            { _, task ->
                timers += task
                scheduled++
                AutoCloseable { timers -= task }
            },
            Executor { background += it },
            Executor { background += it },
            { fx += it },
        ).apply {
            roots = { this@FindInFilesTest.roots }
            entries = { folder ->
                walked.add(folder) // Path is an Iterable, so += would be ambiguous
                Files.list(folder).use { it.toList() }
            }
            unsavedTexts = { unsaved }
            opener = {
                openerCalls++
                val call = openerCalls
                MatchOpener { file, line, column -> view.calls += "opened $call:${file.fileName}:$line:$column" }
            }
        }

    private val anchor = FindAnchor(windowWithoutToolkit(), BoundingBox(0.0, 0.0, 100.0, 100.0), null)

    private fun drain() {
        while (background.isNotEmpty() || fx.isNotEmpty()) {
            background.toList().also { background.clear() }.forEach { it.run() }
            fx.toList().also { fx.clear() }.forEach { it.run() }
        }
    }

    private fun project(): Path {
        val root = dir.resolve("proj").also { it.createDirectories() }
        root.resolve("sub").createDirectories()
        root.resolve("a.txt").writeText("needle here")
        root.resolve("sub/b.txt").writeText("another needle")
        roots = listOf(root)
        return root
    }

    private fun userTypes(text: String) {
        view.query = text
        view.onChanged()
    }

    @Test
    fun `without a folder it does not open and says why`() {
        assertEquals("no folder open", find.blocker())
        assertFalse(find.openWith(anchor, null, null))
        assertEquals(0, view.anchors)

        project()
        assertNull(find.blocker())
    }

    @Test
    fun `query, options and mask are remembered across openings, the scope is not`() {
        val root = project()
        find.openWith(anchor, root.resolve("sub"), null)
        userTypes("needle")
        view.mask = "*.txt"
        view.options = SearchOptions(matchCase = true)
        view.onChanged()
        view.hide()

        find.openWith(anchor, null, null)

        assertEquals("needle", view.query)
        assertEquals("*.txt", view.mask)
        assertEquals(SearchOptions(matchCase = true), view.options)
        assertNull(view.scopeLabel)
    }

    @Test
    fun `a given query replaces the remembered one`() {
        project()
        find.openWith(anchor, null, "needle")
        assertEquals("needle", view.query)
        view.hide()
        find.openWith(anchor, null, "other")
        assertEquals("other", view.query)
        view.hide()
        find.openWith(anchor, null, null)
        assertEquals("other", view.query)
    }

    @Test
    fun `a folder scopes the search and the chip shows its path in the root`() {
        val root = project()
        find.openWith(anchor, root.resolve("sub"), "needle")
        drain()

        assertEquals("sub", view.scopeLabel)
        assertEquals(listOf(root.resolve("sub").resolve("b.txt")), view.files)
        assertEquals(listOf(root.resolve("sub")), walked)
    }

    @Test
    fun `a nested folder is labelled by its path in the root`() {
        val root = project()
        root.resolve("sub/deep").createDirectories()
        find.openWith(anchor, root.resolve("sub/deep"), "needle")
        assertEquals(Path.of("sub", "deep").toString(), view.scopeLabel)
    }

    @Test
    fun `a root folder as scope is labelled by its name`() {
        val root = project()
        find.openWith(anchor, root, "needle")
        assertEquals("proj", view.scopeLabel)
    }

    @Test
    fun `a folder outside the roots is labelled by its name`() {
        project()
        val other = dir.resolve("elsewhere").also { it.createDirectories() }
        find.openWith(anchor, other, "needle")
        assertEquals("elsewhere", view.scopeLabel)
    }

    @Test
    fun `widening searches the whole window at once`() {
        val root = project()
        find.openWith(anchor, root.resolve("sub"), "needle")
        drain()

        view.onWiden()
        drain()

        assertNull(view.scopeLabel)
        assertEquals(setOf(root.resolve("a.txt"), root.resolve("sub/b.txt")), view.files.toSet())
        assertTrue(timers.isEmpty())
    }

    @Test
    fun `opening while it shows does nothing more`() {
        project()
        find.openWith(anchor, null, "needle")
        val calls = view.calls.toList()
        val openerBefore = openerCalls

        assertTrue(find.openWith(anchor, null, "changed"))

        assertEquals(1, view.anchors)
        assertEquals(calls, view.calls)
        assertEquals(openerBefore, openerCalls)
        assertEquals("needle", view.query)
    }

    @Test
    fun `opening a match hides first and uses the opener of the opening`() {
        project()
        find.openWith(anchor, null, "needle")
        val hits = FileHits(Path.of("/p/a.txt"), Path.of("/p"), emptyList(), 1, false)
        view.onOpen(hits, LineHit(3, 5, "x", emptyList()))

        assertEquals(1, openerCalls)
        assertEquals(listOf("hideForOpen", "opened 1:a.txt:3:5"), view.calls.takeLast(2))
        find.openWith(anchor, null, null)
        assertEquals(2, openerCalls)
    }

    @Test
    fun `the view is told whether several roots are searched, before results arrive`() {
        val root = project()
        val second = dir.resolve("second").also { it.createDirectories() }
        roots = listOf(root, second)
        find.openWith(anchor, null, "needle")
        assertTrue(view.severalRoots)
        view.hide()

        find.openWith(anchor, root.resolve("sub"), "needle")
        assertFalse(view.severalRoots)
        view.onWiden()
        assertTrue(view.severalRoots)
    }

    @Test
    fun `the editor menu item has four states and a limit`() {
        val editor = FakeEditor()
        find.extend(editor)

        assertTrue(editor.menu("").isEmpty())

        val multi = editor.menu("a\nb").single()
        assertEquals("Find Selection in Files (more than one line)", multi.text)
        assertTrue(multi.isDisable)

        val noFolder = editor.menu("needle").single()
        assertEquals("Find Selection in Files (no folder open)", noFolder.text)
        assertTrue(noFolder.isDisable)

        project()
        val enabled = editor.menu("needle").single()
        assertEquals("Find Selection in Files", enabled.text)
        assertFalse(enabled.isDisable)

        val long = editor.menu("a".repeat(MAX_SELECTION_QUERY + 1)).single()
        assertEquals("Find Selection in Files (longer than 200 characters)", long.text)
        assertTrue(long.isDisable)
    }

    @Test
    fun `hiding cancels the walk`() {
        project()
        find.openWith(anchor, null, "needle")
        view.hide()
        drain()

        assertTrue(view.files.isEmpty())
    }

    @Test
    fun `typing waits for the debounce, the status follows the search`() {
        project()
        find.openWith(anchor, null, null)
        assertEquals("", view.status)

        userTypes("needle")
        assertEquals(1, timers.size)
        timers.single().run()
        assertEquals("Searching…", view.status)
        drain()
        assertEquals("2 matches in 2 files", view.status)

        userTypes("n")
        assertEquals("", view.status)
        assertTrue(view.files.isEmpty())
    }

    @Test
    fun `the popup being filled is not an edit`() {
        project()
        view.echo = true
        find.openWith(anchor, null, "needle")

        assertEquals(0, scheduled)
        assertEquals(1, background.size)
    }

    @Test
    fun `unsaved texts are read with normalised keys`() {
        val root = project()
        unsaved = mapOf(root.resolve("sub/../a.txt") to "only in the buffer")
        find.openWith(anchor, null, "buffer")
        drain()

        assertEquals(listOf(root.resolve("a.txt")), view.files)
    }

    @Test
    fun `closing hides the popup and stops the search`() {
        project()
        find.openWith(anchor, null, "needle")
        find.close()
        drain()

        assertFalse(view.isShowing)
        assertTrue(view.files.isEmpty())
    }
}

/**
 * A window the view only passes on: a real one needs the toolkit, so its constructor is skipped. Fakes must never
 * touch `anchor.owner`: any call on it fails.
 */
private fun windowWithoutToolkit(): Window {
    val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }
    val unsafe = field.get(null) as sun.misc.Unsafe
    return unsafe.allocateInstance(Stage::class.java) as Window
}
