package dev.sluide.fileviewers

import dev.sluide.fileviewers.code.CodeView
import dev.sluide.fileviewers.languages.PythonFileViewer
import dev.sluide.fileviewers.library.LibraryCodeExtension
import dev.sluide.librarycode.LibraryCodeSettings
import dev.sluide.settings.SettingsStore
import javafx.beans.property.SimpleObjectProperty
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.IndexRange
import javafx.scene.control.Label
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.BorderPane
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.stage.Stage
import javafx.util.Subscription
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.net.URI
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [CodeEditorExtensions] reaching the code views of the viewers and of [CodeEditors.open] through their host, and what
 * they see of and add to the view (ADR 0108); when they attach is `CodeViewExtensionsTest`'s, the counting of locks and
 * activities `CodeViewAdditionsTest`'s. Needs a display: shown lines and gutter rows exist only in a laid-out area,
 * style spans and editability only in a real area, banners and the toolbar row only in a laid-out view.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class CodeEditorExtensionsUiTest {
    @TempDir
    lateinit var dir: Path

    private val container = StackPane()

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(container, 500.0, 300.0)
        stage.show()
    }

    /** A host of a window whose services hold [extensions]. */
    private fun host(extensions: CodeEditorExtensions) =
        object : FileViewHost {
            private val services = WindowServices().apply { register(CodeEditorExtensions::class.java, extensions) }

            override fun openFile(file: Path) = false

            override fun openWebPage(uri: URI) {}

            override fun <T : Any> windowService(type: Class<T>): T? = services.get(type)
        }

    /** Extensions that record the editors they extend and the handles closed. */
    private fun recording(
        extended: MutableList<ExtensibleCodeEditor>,
        closed: MutableList<Path>,
    ) = CodeEditorExtensions().apply {
        add { editor ->
            extended += editor
            AutoCloseable { closed.add(editor.file.value) }
        }
    }

    private fun file(
        name: String,
        text: String = "x = 1\n",
    ) = dir.resolve(name).apply { writeText(text) }

    @Test
    fun `the views of viewers and CodeEditors are extended through their host, follow moves and close on dispose`(
        robot: FxRobot,
    ) {
        val extended = mutableListOf<ExtensibleCodeEditor>()
        val closed = mutableListOf<Path>()
        val extensions = recording(extended, closed)
        val view = WaitForAsyncUtils.asyncFx<FileView> { PythonFileViewer().open(file("view.py")) }.get()
        val editor = WaitForAsyncUtils.asyncFx<CodeEditor> { CodeEditors.open(file("editor.py"), null) }.get()

        robot.interact {
            view.host = host(extensions)
            editor.host = host(extensions)
        }
        assertEquals(listOf(dir.resolve("view.py"), dir.resolve("editor.py")), extended.map { it.file.value })

        robot.interact {
            view.moved(dir.resolve("moved.py"))
            view.dispose()
            editor.dispose()
        }
        assertEquals(listOf(dir.resolve("moved.py"), dir.resolve("editor.py")), closed)
    }

    @Test
    fun `an extension sees the lines, adds a gutter column and follows the shown lines`(robot: FxRobot) {
        val extended = mutableListOf<ExtensibleCodeEditor>()
        val view =
            WaitForAsyncUtils
                .asyncFx<CodeView> {
                    PythonFileViewer().open(file("main.py", (1..40).joinToString("\n") { "x = $it" })) as CodeView
                }.get()
        robot.interact {
            container.children.setAll(view.node)
            view.host = host(recording(extended, mutableListOf()))
        }
        val editor = extended.single()

        robot.interact {
            editor.addGutterColumn(30.0) { line -> Label("c$line").apply { styleClass += "extension-column" } }
        }

        val labels = robot.lookup(".extension-column").queryAllAs(Label::class.java).map { it.text }
        assertTrue("c0" in labels, "$labels")
        assertEquals(40, editor.lineCount)
        assertEquals("x = 2", editor.lineText(1))
        assertFailsWith<IndexOutOfBoundsException> { editor.lineText(40) }
        val shown = editor.shownLines
        assertEquals(0, shown.first)
        assertTrue(shown.last in 1 until 39, "$shown")

        var changes = 0
        val subscription = WaitForAsyncUtils.asyncFx<Subscription> { editor.onShownLinesChanged { changes++ } }.get()
        robot.interact { view.area.showParagraphAtTop(20) }
        assertTrue(changes > 0, "scrolled")
        assertEquals(20, editor.shownLines.first)
        robot.interact { subscription.unsubscribe() }
    }

    /** A code view opened by [viewer], shown in the scene. */
    private fun shown(
        robot: FxRobot,
        viewer: FileViewer,
        file: Path,
    ): CodeView {
        val view = WaitForAsyncUtils.asyncFx<CodeView> { viewer.open(file) as CodeView }.get()
        robot.interact { container.children.setAll(view.node) }
        return view
    }

    /** Whether [styleClass] styles any character of [view]. */
    private fun styled(
        view: CodeView,
        styleClass: String,
    ) = view.area.getStyleSpans(0, view.area.length).any { styleClass in it.style }

    @Test
    fun `overlay layers show on plain and highlighted text and stay through background highlighting`(robot: FxRobot) {
        val plain = shown(robot, DefaultTextFileViewer(), file("notes.txt", "one two three\n"))
        robot.interact {
            plain.extensible.overlay("sentence", listOf(StyledRange(0, 7, "layer-sentence")))
            plain.extensible.overlay("word", listOf(StyledRange(4, 7, "layer-word"), StyledRange(10, 99, "layer-word")))
        }
        assertTrue(plain.extensible.plainText)
        assertEquals(listOf("layer-sentence"), plain.area.getStyleOfChar(1).toList())
        assertEquals(listOf("layer-sentence", "layer-word"), plain.area.getStyleOfChar(5).toList())
        assertEquals(listOf("layer-word"), plain.area.getStyleOfChar(12).toList(), "clamped to the text")
        robot.interact { plain.extensible.overlay("word", emptyList()) }
        assertEquals(listOf("layer-sentence"), plain.area.getStyleOfChar(5).toList(), "the other layer stays")

        val results = ConcurrentLinkedQueue<Runnable>()
        val file = file("main.py")
        val view =
            WaitForAsyncUtils
                .asyncFx<CodeView> {
                    CodeView(file, PythonFileViewer().highlighter, highlightPause = Duration.ofMillis(1)) {
                        results += it
                    }
                }.get()
        robot.interact {
            container.children.setAll(view.node)
            view.area.insertText(0, "def f(): pass\n")
            // Between the edit, which clears the layers, and its highlighting.
            view.extensible.overlay("sentence", listOf(StyledRange(0, 3, "layer-sentence")))
        }
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS) { results.isNotEmpty() }
        robot.interact { results.poll().run() }

        assertFalse(view.extensible.plainText)
        assertEquals(listOf("keyword", "layer-sentence"), view.area.getStyleOfChar(1).toList())
    }

    @Test
    fun `a reload clears the overlay layers and is a text change`(robot: FxRobot) {
        val file = file("notes.txt", "one two\n")
        val view = shown(robot, DefaultTextFileViewer(), file)
        var changes = 0
        val subscription = WaitForAsyncUtils.asyncFx<Subscription> { view.extensible.onTextChanged { changes++ } }.get()
        robot.interact { view.extensible.overlay("word", listOf(StyledRange(4, 7, "layer-word"))) }
        assertTrue(styled(view, "layer-word"))

        file.writeText("zero one two\n")
        robot.interact { view.reload() }

        assertEquals("zero one two\n", view.extensible.text)
        assertTrue(changes > 0, "the reload is a change of the text")
        assertFalse(styled(view, "layer-word"), "the ranges no longer fit the text")

        robot.interact { subscription.unsubscribe() }
        val before = changes
        robot.interact { view.area.insertText(0, "x") }
        assertEquals(before, changes, "unsubscribed")
    }

    @Test
    fun `locks keep the text read-only until all are closed`(robot: FxRobot) {
        val file = file("notes.txt", "x = 1\n")
        val view = shown(robot, DefaultTextFileViewer(), file)
        robot.interact { view.area.insertText(0, "y") }
        val first = WaitForAsyncUtils.asyncFx<AutoCloseable> { view.extensible.lock() }.get()
        val second = WaitForAsyncUtils.asyncFx<AutoCloseable> { view.extensible.lock() }.get()

        assertFalse(view.area.isEditable)
        assertFalse(view.canReplaceText)
        assertFalse(view.readOnly, "not shown read-only for its encoding")
        robot.interact {
            assertFalse(view.replaceText("other"))
            view.findBar.open(replacing = true)
            view.findBar.query.text = "x"
            view.findBar.replacement.text = "z"
            view.findBar.replace()
            view.findBar.replaceAll()
        }
        assertEquals("yx = 1\n", view.area.text, "nothing replaced")
        robot.interact { view.save() }
        assertEquals("yx = 1\n", file.readText(), "saving works while locked")
        file.writeText("new = 2\n")
        robot.interact { view.reload() }
        assertEquals("new = 2\n", view.area.text, "a change on disk still shows")
        assertFalse(view.area.isEditable, "still locked after the reload")

        robot.interact {
            first.close()
            first.close()
        }
        assertFalse(view.area.isEditable, "one lock is still held; closing twice is harmless")
        robot.interact { second.close() }
        assertTrue(view.area.isEditable)
        assertTrue(view.canReplaceText)

        val extensions = CodeEditorExtensions().apply { add { editor -> editor.lock() } }
        val editor = WaitForAsyncUtils.asyncFx<CodeEditor> { CodeEditors.open(file("other.py"), null) }.get()
        robot.interact { editor.host = host(extensions) }
        assertFalse(editor.canReplaceText)
        robot.interact { assertFalse(editor.replaceText("other")) }

        // 0x81 is undefined in Windows-1252: the file is shown read-only for its encoding.
        val invalid =
            dir.resolve("invalid.txt").apply {
                writeBytes("aä".toByteArray(Charsets.ISO_8859_1) + 0x81.toByte())
            }
        val readOnly = shown(robot, DefaultTextFileViewer(), invalid)
        robot.interact { readOnly.extensible.lock().close() }
        assertTrue(readOnly.readOnly)
        assertFalse(readOnly.area.isEditable, "closing the last lock keeps the read-only encoding")
    }

    @Test
    fun `an extension reads caret and selection and reveals a range without moving them`(robot: FxRobot) {
        val view = shown(robot, DefaultTextFileViewer(), file("long.txt", (1..200).joinToString("\n") { "line $it" }))
        robot.interact { view.area.selectRange(2, 6) }
        assertEquals(6, view.extensible.caretOffset)
        assertEquals(IndexRange(2, 6), view.extensible.selection)

        val start = view.area.getAbsolutePosition(150, 0)
        robot.interact { view.extensible.reveal(start, start + 8) }

        assertTrue(150 in view.extensible.shownLines, "${view.extensible.shownLines}")
        assertEquals(IndexRange(2, 6), view.extensible.selection, "the selection stays")
        assertEquals(6, view.extensible.caretOffset)
    }

    @Test
    fun `with soft wrap, reveal shows a word deep inside one wrapped paragraph and an overlay stays on it`(
        robot: FxRobot,
    ) {
        val view = shown(robot, DefaultTextFileViewer(), file("long.txt", (1..400).joinToString(" ") { "word$it" }))
        robot.interact { view.softWrap.wrapping.set(true) }
        assertTrue(view.area.isWrapText)
        val start = view.area.text.indexOf("word390")

        robot.interact {
            view.extensible.overlay("word", listOf(StyledRange(start, start + 7, "layer-word")))
            view.extensible.reveal(start, start + 7)
        }

        assertTrue(inViewport(view, start) && inViewport(view, start + 6), "the word is in the viewport")
        assertFalse(inViewport(view, 0), "the paragraph is taller than the viewport")
        assertEquals(listOf("layer-word"), view.area.getStyleOfChar(start + 3).toList())
        assertTrue(view.area.getStyleOfChar(start - 1).isEmpty())
        assertTrue(view.area.getStyleOfChar(start + 7).isEmpty())
        robot.interact { view.extensible.reveal(0, 5) }
        assertTrue(inViewport(view, 0), "and back to the first word")
    }

    /** Whether the character at [offset] of [view] is laid out within the viewport. */
    private fun inViewport(
        view: CodeView,
        offset: Int,
    ): Boolean =
        WaitForAsyncUtils
            .asyncFx<Boolean> {
                val bounds =
                    view.area
                        .getCharacterBoundsOnScreen(offset, offset + 1)
                        .orElse(null)
                        ?.let { view.area.screenToLocal(it) }
                bounds != null && bounds.minY >= -3 && bounds.maxY <= view.area.height + 3
            }.get()

    @Test
    fun `a view of its own always shows a row of Soft Wrap and then the toolbar items, an embedded one none`(
        robot: FxRobot,
    ) {
        val view = shown(robot, DefaultTextFileViewer(), file("notes.txt"))
        val row = robot.lookup(".code-editor-toolbar").queryAs(WrappingToolbar::class.java)
        assertTrue(row.isVisible && row.isManaged, "shown without items too")
        assertEquals(listOf(view.softWrapButton), row.leading.toList())

        val items = listOf(Label("a"), Label("b"))
        val handle = WaitForAsyncUtils.asyncFx<AutoCloseable> { view.extensible.addToolbarItems(items) }.get()
        assertEquals(listOf(view.softWrapButton) + items, row.leading.toList())
        assertEquals(items, view.toolbarItems.toList())
        assertSame(row, ((view.node as BorderPane).top as VBox).children.first(), "at the very top")

        robot.interact {
            handle.close()
            handle.close()
        }
        assertEquals(listOf(view.softWrapButton), row.leading.toList())
        assertTrue(view.toolbarItems.isEmpty())

        val python = shown(robot, PythonFileViewer(), file("main.py"))
        assertNotNull(python.node.lookup(".code-editor-toolbar"), "code viewers have the row too")

        val item = Label("c")
        val extensions = CodeEditorExtensions().apply { add { editor -> editor.addToolbarItems(listOf(item)) } }
        val editor = WaitForAsyncUtils.asyncFx<CodeEditor> { CodeEditors.open(file("embedded.py"), null) }.get()
        robot.interact {
            container.children.setAll(editor.node)
            editor.host = host(extensions)
        }
        assertEquals(listOf<Node>(item), editor.toolbarItems.toList())
        assertNull(editor.node.lookup(".code-editor-toolbar"), "the embedding view places the items")
    }

    @Test
    fun `an extension's banner shows below the find bar and the view's own banners until closed`(robot: FxRobot) {
        val file = file("notes.txt")
        val view = shown(robot, DefaultTextFileViewer(), file)
        val banner = Label("extension banner")
        val handle = WaitForAsyncUtils.asyncFx<AutoCloseable> { view.extensible.addBanner(banner) }.get()
        file.deleteExisting()
        robot.interact {
            view.findBar.open()
            // A banner of the view's own, shown after the extension's.
            view.reload()
        }
        robot.interact { container.layout() }

        val deleted = robot.lookup(".editor-banner").queryAs(Node::class.java)
        val findBottom =
            view.findBar.node
                .localToScene(view.findBar.node.layoutBounds)
                .maxY
        val deletedBounds = deleted.localToScene(deleted.layoutBounds)
        assertTrue(deletedBounds.minY >= findBottom, "own banner below the find bar")
        assertTrue(banner.localToScene(banner.layoutBounds).minY >= deletedBounds.maxY, "extension banner below it")

        robot.interact { handle.close() }
        assertNull(banner.scene)
    }

    @Test
    fun `an extension's status item shows below the text until closed`(robot: FxRobot) {
        val view = shown(robot, DefaultTextFileViewer(), file("notes.txt"))
        val item = Label("status item")
        val handle = WaitForAsyncUtils.asyncFx<AutoCloseable> { view.extensible.addStatusItem(item) }.get()
        robot.interact { container.layout() }

        val bar = robot.lookup(".code-status-bar").queryAs(Node::class.java)
        assertTrue(bar.isVisible)
        val areaBottom = view.area.localToScene(view.area.layoutBounds).maxY
        assertTrue(item.localToScene(item.layoutBounds).minY >= areaBottom, "item below the text area")

        robot.interact { handle.close() }
        robot.interact { container.layout() }
        assertFalse(bar.isVisible)
        assertNull(item.scene)
    }

    @Test
    fun `the view's activity combines the added ones`(robot: FxRobot) {
        val running = SimpleObjectProperty(ViewActivity.NONE)
        val subscriptions = mutableListOf<Subscription>()
        val extensions =
            CodeEditorExtensions().apply {
                add { editor ->
                    subscriptions += editor.addActivity(running)
                    null
                }
            }
        val editor = WaitForAsyncUtils.asyncFx<CodeEditor> { CodeEditors.open(file("main.py"), null) }.get()
        robot.interact { editor.host = host(extensions) }

        assertEquals(ViewActivity.NONE, editor.activity.value)
        robot.interact { running.set(ViewActivity.RUNNING) }
        assertEquals(ViewActivity.RUNNING, editor.activity.value, "through the CodeEditor's delegation")
        robot.interact { subscriptions.single().unsubscribe() }
        assertEquals(ViewActivity.NONE, editor.activity.value)
    }

    @Test
    fun `an event filter sees the text area's events until unsubscribed`(robot: FxRobot) {
        val view = shown(robot, DefaultTextFileViewer(), file("notes.txt"))
        val seen = mutableListOf<KeyCode>()
        val subscription =
            WaitForAsyncUtils
                .asyncFx<Subscription> { view.extensible.addEventFilter(KeyEvent.KEY_PRESSED) { seen += it.code } }
                .get()
        val press = { KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F7, false, false, false, false) }

        robot.interact { Event.fireEvent(view.area, press()) }
        robot.interact {
            subscription.unsubscribe()
            Event.fireEvent(view.area, press())
        }

        assertEquals(listOf(KeyCode.F7), seen)
    }

    @Test
    fun `a stylesheet is on the text area until its handle is closed`(robot: FxRobot) {
        val view = shown(robot, DefaultTextFileViewer(), file("notes.txt"))
        val url = file("extra.css", ".x {}").toUri().toString()
        val handle = WaitForAsyncUtils.asyncFx<AutoCloseable> { view.extensible.addStylesheet(url) }.get()

        assertTrue(url in view.area.stylesheets)

        robot.interact { handle.close() }
        assertFalse(url in view.area.stylesheets)
    }

    @Test
    fun `a read-only lock refuses Save and marks the view until closed`(robot: FxRobot) {
        val file = file("notes.txt", "x = 1\n")
        val view = shown(robot, DefaultTextFileViewer(), file)
        robot.interact { view.area.insertText(0, "y") }
        assertFalse(view.readOnlyMarked.value)
        val lock = WaitForAsyncUtils.asyncFx<AutoCloseable> { view.extensible.readOnlyLock() }.get()

        assertTrue(view.readOnlyMarked.value)
        assertFalse(view.canReplaceText, "it is a lock too")
        robot.interact { view.save() }
        assertEquals("x = 1\n", file.readText(), "Save does nothing")
        assertTrue(view.dirty.value)

        robot.interact { lock.close() }
        assertFalse(view.readOnlyMarked.value)
        robot.interact { view.save() }
        assertEquals("yx = 1\n", file.readText())
    }

    @Test
    fun `library code opens locked with a banner, Edit Anyway unlocks and the banner changes`(robot: FxRobot) {
        val venv = dir.resolve("venv").also { it.createDirectories() }
        venv.resolve("pyvenv.cfg").writeText("home = /usr/bin\n")
        val library = venv.resolve("mod.py").apply { writeText("x = 1\n") }
        val settings = LibraryCodeSettings(SettingsStore(dir.resolve("settings.properties")))
        val view = WaitForAsyncUtils.asyncFx<CodeView> { PythonFileViewer().open(library) as CodeView }.get()
        robot.interact {
            container.children.setAll(view.node)
            view.host = host(CodeEditorExtensions().apply { add(LibraryCodeExtension(settings)) })
        }

        val texts = { robot.lookup(".editor-banner .label").queryAllAs(Label::class.java).map { it.text } }
        assertEquals(
            listOf(
                "venv/mod.py is library code (virtual environment venv): read-only, as changes are lost on the next install.",
            ),
            texts(),
        )
        assertTrue(view.readOnlyMarked.value)
        assertFalse(view.area.isEditable)
        robot.interact {
            view.area.fireEvent(
                KeyEvent(KeyEvent.KEY_TYPED, "y", "", KeyCode.UNDEFINED, false, false, false, false),
            )
            assertFalse(view.replaceText("other"))
        }
        assertEquals("x = 1\n", view.area.text, "typing changes nothing")

        // A moved file is classified again.
        robot.interact { view.moved(dir.resolve("outside.py")) }
        assertTrue(view.area.isEditable, "moved out of the venv")
        assertFalse(view.readOnlyMarked.value)
        assertTrue(texts().isEmpty())
        robot.interact { view.moved(library) }
        assertFalse(view.area.isEditable, "moved into it again")
        assertEquals(1, texts().size)

        robot.interact { robot.lookup(".editor-banner .button").queryButton().fire() }
        assertTrue(view.area.isEditable)
        assertFalse(view.readOnlyMarked.value)
        assertEquals(
            listOf("Editing library code (virtual environment venv): changes are lost on the next install."),
            texts(),
        )
        assertSame(view.area, container.scene.focusOwner, "the focus is back in the text")

        // The setting releases a tab that is still locked and locks it again, but not one with unsaved changes.
        val second = venv.resolve("second.py").apply { writeText("x = 1\n") }
        val locked = WaitForAsyncUtils.asyncFx<CodeView> { PythonFileViewer().open(second) as CodeView }.get()
        robot.interact {
            locked.host = host(CodeEditorExtensions().apply { add(LibraryCodeExtension(settings)) })
        }
        assertFalse(locked.area.isEditable, "locked")
        robot.interact { settings.readOnly.set(false) }
        assertTrue(view.area.isEditable)
        assertTrue(texts().isEmpty(), "the setting off removes the banner of an unlocked tab too")

        assertTrue(locked.area.isEditable, "the setting released it")
        robot.interact { locked.area.insertText(0, "y") }
        robot.interact { settings.readOnly.set(true) }
        assertTrue(locked.area.isEditable, "unsaved changes stay unlocked")
        assertFalse(locked.readOnlyMarked.value)

        // The view unlocked with Edit Anyway stays unlocked through the setting, and warns again; Dismiss removes it.
        assertTrue(view.area.isEditable)
        assertEquals(1, texts().size, "the Edit Anyway view warns again")
        robot.interact { robot.lookup(".editor-banner .button").queryButton().fire() }
        assertTrue(texts().isEmpty())
        assertTrue(view.area.isEditable)
    }

    @Test
    fun `Convert to UTF-8 is disabled while a read-only lock is held`(robot: FxRobot) {
        // "Grüße" in Windows-1252.
        val latin1 = byteArrayOf(0x47, 0x72, 0xFC.toByte(), 0xDF.toByte(), 0x65)
        val file = dir.resolve("latin1.txt").apply { writeBytes(latin1) }
        val view = shown(robot, DefaultTextFileViewer(), file)
        val convert = { robot.lookup(".encoding-banner .button").queryButton() }
        assertFalse(convert().isDisable)

        val lock = WaitForAsyncUtils.asyncFx<AutoCloseable> { view.extensible.readOnlyLock() }.get()
        assertTrue(convert().isDisable, "converting would make the view dirty, which Save then refuses")
        robot.interact { lock.close() }
        assertFalse(convert().isDisable)
    }
}
