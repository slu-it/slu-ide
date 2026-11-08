package dev.sluide.fileviewers

import dev.sluide.fileviewers.code.CodeView
import dev.sluide.fileviewers.code.Gutter
import dev.sluide.fileviewers.languages.PythonFileViewer
import dev.sluide.textediting.TextPosition
import javafx.event.Event
import javafx.event.EventTarget
import javafx.geometry.Bounds
import javafx.scene.Cursor
import javafx.scene.Scene
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.fxmisc.richtext.CodeArea
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
 * What a code view gives its extensions for language features (ADR 0141): text changes, edits, caret and positions,
 * key bindings, following overlays, saves and the cursor, and going to a line and column. The edit offsets' cases are
 * `EditOffsetsTest`'s, `TextPosition`'s `TextPositionTest`'s. Needs a display: changes, undo, styles and key handling
 * exist only in a real area, screen bounds and hits only in a laid-out one, the focus only in a shown window.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class ExtensibleCodeEditorApiUiTest {
    @TempDir
    lateinit var dir: Path

    private val container = StackPane()

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(container, 500.0, 300.0)
        stage.show()
    }

    private fun file(
        name: String,
        text: String,
    ) = dir.resolve(name).apply { writeText(text) }

    /** A host of a window whose code editors [extensions] extend. */
    private fun host(extensions: CodeEditorExtensions) =
        object : FileViewHost {
            private val services = WindowServices().apply { register(CodeEditorExtensions::class.java, extensions) }

            override fun openFile(file: Path) = false

            override fun openWebPage(uri: URI) {}

            override fun <T : Any> windowService(type: Class<T>): T? = services.get(type)
        }

    /** [view] shown in the scene and extended through a host; the editor its extension got. */
    private fun extended(
        robot: FxRobot,
        view: CodeView,
    ): ExtensibleCodeEditor {
        val editors = mutableListOf<ExtensibleCodeEditor>()
        val extensions =
            CodeEditorExtensions().apply {
                add { editor ->
                    editors += editor
                    null
                }
            }
        robot.interact {
            container.children.setAll(view.node)
            view.host = host(extensions)
        }
        return editors.single()
    }

    /** A plain-text code view of [text], shown and extended; the editor its extension got. */
    private fun plain(
        robot: FxRobot,
        text: String,
        name: String = "notes.txt",
    ): Pair<CodeView, ExtensibleCodeEditor> {
        val view =
            WaitForAsyncUtils
                .asyncFx<CodeView> {
                    DefaultTextFileViewer().open(
                        file(name, text),
                    ) as CodeView
                }.get()
        return view to extended(robot, view)
    }

    @Test
    fun `an extension gets the host and every change of the text as a delta that rebuilds the text`(robot: FxRobot) {
        val file = file("notes.txt", "one\n")
        val view = WaitForAsyncUtils.asyncFx<CodeView> { DefaultTextFileViewer().open(file) as CodeView }.get()
        var hostSeen: FileViewHost? = null
        val extensions =
            CodeEditorExtensions().apply {
                add { editor ->
                    hostSeen = editor.host
                    null
                }
            }
        val host = host(extensions)
        assertFalse(view.extensible.host.openFile(file), "before it is extended: a host that opens nothing")
        robot.interact {
            container.children.setAll(view.node)
            view.host = host
        }
        assertSame(host, hostSeen, "known while extending")
        val editor = view.extensible
        val changes = mutableListOf<TextChange>()
        val mismatches = mutableListOf<TextChange>()
        var copy = view.area.text
        val handle =
            WaitForAsyncUtils
                .asyncFx<AutoCloseable> {
                    editor.onTextChange { change ->
                        changes += change
                        if (!copy.startsWith(change.removed, change.start)) mismatches += change
                        copy = copy.replaceRange(change.start, change.start + change.removed.length, change.inserted)
                    }
                }.get()

        robot.interact {
            Event.fireEvent(
                view.area,
                KeyEvent(KeyEvent.KEY_TYPED, "x", "x", KeyCode.UNDEFINED, false, false, false, false),
            )
        }
        assertEquals(TextChange(0, "", "x"), changes.last(), "typing")
        robot.press(view.area, KeyCode.ENTER)
        assertEquals(TextChange(1, "", "\n"), changes.last(), "the editing keys")
        robot.interact {
            // Twice: Enter may have merged with the typing before it into one undo step.
            view.area.undo()
            view.area.undo()
        }
        assertEquals("one\n", view.area.text)
        file.writeText("zero one\n")
        robot.interact { view.reload() }
        assertEquals(TextChange(0, "", "zero "), changes.last(), "a reload: only what differs")
        robot.interact { view.replaceText("zero one two\n") }
        val before = changes.size
        robot.interact { assertTrue(editor.applyEdits(listOf(OffsetEdit(9, 12, "2"), OffsetEdit(0, 4, "0")))) }
        assertEquals(2, changes.size - before, "one change per edit")
        robot.interact { view.area.undo() }

        assertEquals("zero one two\n", view.area.text)
        assertEquals(view.area.text, copy, "the changes in order rebuild the text: $changes")
        assertEquals(emptyList(), mismatches)

        robot.interact { handle.close() }
        val closed = changes.size
        robot.interact { view.area.insertText(0, "y") }
        assertEquals(closed, changes.size, "closed")
    }

    @Test
    fun `edits are one undo step, move the caret with the text and are refused while the editor is locked`(
        robot: FxRobot,
    ) {
        val (view, editor) = plain(robot, "one two three\n")
        robot.interact { view.area.moveTo(8) }

        robot.interact { assertTrue(editor.applyEdits(listOf(OffsetEdit(4, 7, "2"), OffsetEdit(0, 3, "ONE!")))) }

        assertEquals("ONE! 2 three\n", view.area.text)
        assertEquals(7, view.area.caretPosition, "still before three")
        robot.interact { view.area.undo() }
        assertEquals("one two three\n", view.area.text, "one undo step")
        robot.interact { view.area.redo() }
        assertEquals("ONE! 2 three\n", view.area.text)

        val lock = WaitForAsyncUtils.asyncFx<AutoCloseable> { editor.lock() }.get()
        robot.interact { assertFalse(editor.applyEdits(listOf(OffsetEdit(0, 4, "")))) }
        assertEquals("ONE! 2 three\n", view.area.text, "refused while locked")
        robot.interact { lock.close() }
        robot.interact {
            assertFailsWith<IllegalArgumentException> {
                editor.applyEdits(
                    listOf(OffsetEdit(0, 3, ""), OffsetEdit(2, 5, "")),
                )
            }
        }
        assertEquals("ONE! 2 three\n", view.area.text, "overlapping edits change nothing")

        robot.interact {
            view.area.moveTo(13)
            assertTrue(editor.applyEdits(listOf(OffsetEdit(0, 0, "x\r\ny\rz"))))
        }
        assertEquals("x\ny\nzONE! 2 three\n", view.area.text, "a server's line breaks become \\n")
        assertEquals(18, view.area.caretPosition, "moved by the text as inserted, with \\n")
    }

    @Test
    fun `a key binding of an extension comes before the editor's keys while it handles them`(robot: FxRobot) {
        val (view, editor) = plain(robot, "a")
        var handles = true
        var calls = 0
        val enter =
            WaitForAsyncUtils
                .asyncFx<AutoCloseable> {
                    editor.addKeyBinding(KeyCodeCombination(KeyCode.ENTER)) {
                        calls++
                        handles
                    }
                }.get()
        robot.interact { view.area.moveTo(1) }

        robot.press(view.area, KeyCode.ENTER)
        assertEquals("a", view.area.text, "handled: no new line")
        handles = false
        robot.press(view.area, KeyCode.ENTER)
        assertEquals("a\n", view.area.text, "not handled: the editor's Enter")
        assertEquals(2, calls)

        robot.interact { enter.close() }
        robot.press(view.area, KeyCode.ENTER)
        assertEquals(2, calls, "removed")
        assertEquals("a\n\n", view.area.text)

        // A literal Ctrl, also where Shortcut is Cmd.
        var completions = 0
        robot.interact {
            editor.addKeyBinding(KeyCodeCombination(KeyCode.SPACE, KeyCombination.CONTROL_DOWN)) {
                completions++
                true
            }
            Event.fireEvent(view.area, KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.SPACE, false, true, false, false))
        }
        assertEquals(1, completions)
    }

    /** Fires a press of [code] at [view]'s area, then the key typed of [character] (with Alt if [alt]). */
    private fun FxRobot.pressAndType(
        view: CodeView,
        code: KeyCode,
        character: String?,
        alt: Boolean = false,
    ) = interact {
        Event.fireEvent(view.area, KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, alt, false))
        if (character != null) {
            Event.fireEvent(
                view.area,
                KeyEvent(KeyEvent.KEY_TYPED, character, "", KeyCode.UNDEFINED, false, false, alt, false),
            )
        }
    }

    @Test
    fun `a handled press takes its key typed too, a press consumed before or a failing action is not handled`(
        robot: FxRobot,
    ) {
        val (view, editor) = plain(robot, "")
        var handles = true
        robot.interact {
            editor.addKeyBinding(KeyCodeCombination(KeyCode.J, KeyCombination.ALT_DOWN)) { handles }
        }

        robot.pressAndType(view, KeyCode.J, "j", alt = true)
        assertEquals("", view.area.text, "Alt+J handled: RichTextFX types nothing")
        robot.pressAndType(view, KeyCode.J, null, alt = true)
        robot.pressAndType(view, KeyCode.K, "k")
        assertEquals("k", view.area.text, "a handled press without a key typed does not take the next one's")
        handles = false
        robot.pressAndType(view, KeyCode.J, "j", alt = true)
        assertEquals("kj", view.area.text, "not handled: typed as RichTextFX does")

        var calls = 0
        robot.interact {
            editor.addEventFilter(KeyEvent.KEY_PRESSED) { if (it.code == KeyCode.ENTER) it.consume() }
            editor.addKeyBinding(KeyCodeCombination(KeyCode.ENTER)) {
                calls++
                true
            }
            editor.addKeyBinding(KeyCodeCombination(KeyCode.D, KeyCombination.SHORTCUT_DOWN)) { error("broken") }
        }
        robot.press(view.area, KeyCode.ENTER)
        assertEquals(0, calls, "consumed by an earlier filter")
        robot.press(view.area, KeyCode.D, shortcut = true)
        assertEquals("kj\nkj", view.area.text, "a failing action counts as not handled: the editor duplicates the line")
    }

    /** A mouse event at [screenX], [screenY] over [area], aimed at [target]. */
    private fun mouseAt(
        area: CodeArea,
        screenX: Double,
        screenY: Double,
        target: EventTarget = area,
    ): MouseEvent {
        val local = area.screenToLocal(screenX, screenY)
        return MouseEvent(
            area,
            target,
            MouseEvent.MOUSE_MOVED,
            local.x,
            local.y,
            screenX,
            screenY,
            MouseButton.NONE,
            0,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            null,
        )
    }

    @Test
    fun `offsetAt finds the character under the mouse, screenBounds where a range is shown, popupOwner the area`(
        robot: FxRobot,
    ) {
        val (view, editor) = plain(robot, "hello world\nab\n" + (1..200).joinToString("\n") { "line $it" })
        val area = view.area

        val (world, outcomes) =
            WaitForAsyncUtils
                .asyncFx<Pair<Bounds?, List<Int?>>> {
                    val world = editor.screenBounds(6, 11)
                    val w = checkNotNull(editor.screenBounds(6, 7))
                    val b = checkNotNull(editor.screenBounds(13, 14))
                    val gutter = view.node.lookup(".${Gutter.GUTTER_STYLE}")
                    world to
                        listOf(
                            editor.offsetAt(mouseAt(area, w.centerX, w.centerY)),
                            editor.offsetAt(mouseAt(area, b.maxX + 60, b.centerY)),
                            editor.offsetAt(mouseAt(area, w.centerX, w.centerY, target = gutter)),
                        )
                }.get()

        assertEquals(listOf(6, null, null), outcomes, "the w, past the end of ab, the gutter")
        assertNotNull(world)
        assertTrue(world.width > 0)
        val caret = WaitForAsyncUtils.asyncFx<Bounds?> { editor.screenBounds(12) }.get()
        assertNotNull(caret, "a caret at the start of ab")
        val far = area.getAbsolutePosition(150, 0)
        assertNull(WaitForAsyncUtils.asyncFx<Bounds?> { editor.screenBounds(far, far + 4) }.get(), "scrolled away")
        assertSame(area, WaitForAsyncUtils.asyncFx<Any?> { editor.popupOwner }.get(), "popups belong to the area")
        robot.interact { container.children.clear() }
        assertNull(WaitForAsyncUtils.asyncFx<Bounds?> { editor.screenBounds(6, 7) }.get(), "not shown")
        assertNull(WaitForAsyncUtils.asyncFx<Any?> { editor.popupOwner }.get(), "no owner out of the scene")
    }

    @Test
    fun `a following overlay moves with edits elsewhere, through background highlighting, and goes where edited`(
        robot: FxRobot,
    ) {
        val results = ConcurrentLinkedQueue<Runnable>()
        val file = file("main.py", "x = 1\ny = 2\n")
        val view =
            WaitForAsyncUtils
                .asyncFx<CodeView> {
                    CodeView(file, PythonFileViewer().highlighter, highlightPause = Duration.ofMillis(1)) {
                        results += it
                    }
                }.get()
        val editor = extended(robot, view)
        robot.interact {
            editor.overlay("diagnostics", listOf(StyledRange(6, 7, "layer-diagnostic")), followEdits = true)
            editor.overlay("plain", listOf(StyledRange(0, 1, "layer-plain")))
        }

        robot.interact { view.area.insertText(0, "zz") }

        assertEquals(listOf("layer-diagnostic"), view.area.getStyleOfChar(8).filter { it.startsWith("layer-") })
        assertFalse(styled(view, "layer-plain"), "a layer that does not follow edits is cleared")
        assertEquals(listOf(8), classAt(view, "layer-diagnostic"), "moved with the text, not left at 6")
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS) { results.isNotEmpty() }
        robot.interact { results.poll().run() }
        assertEquals(listOf(8), classAt(view, "layer-diagnostic"), "applied again with the new highlighting")

        robot.interact { view.area.insertText(9, "q") }

        assertFalse(styled(view, "layer-diagnostic"), "an edit touching the range drops it")
    }

    /** Whether [styleClass] styles any character of [view]. */
    private fun styled(
        view: CodeView,
        styleClass: String,
    ) = classAt(view, styleClass).isNotEmpty()

    /** The offsets of the characters of [view] that [styleClass] styles. */
    private fun classAt(
        view: CodeView,
        styleClass: String,
    ) = WaitForAsyncUtils
        .asyncFx<List<Int>> { (0 until view.area.length).filter { styleClass in view.area.getStyleOfChar(it) } }
        .get()

    @Test
    fun `an extension sees the editor dirty and is told after each save`(robot: FxRobot) {
        val (view, editor) = plain(robot, "one\n")
        var saves = 0
        val handle = WaitForAsyncUtils.asyncFx<AutoCloseable> { editor.onSaved { saves++ } }.get()
        robot.interact { view.area.insertText(0, "x") }
        assertTrue(editor.dirty.get())

        robot.interact { view.save() }

        assertEquals(1, saves)
        assertFalse(editor.dirty.get())
        robot.interact {
            handle.close()
            view.save()
        }
        assertEquals(1, saves, "closed")
    }

    @Test
    fun `showPosition puts the caret at a line and column kept within the text and focuses it`(robot: FxRobot) {
        val (view, _) = plain(robot, (1..100).joinToString("\n") { "line $it" })

        robot.interact { view.showPosition(60, 3) }

        assertEquals(60 to 3, view.area.currentParagraph to view.area.caretColumn)
        assertTrue(view.area.isFocused)
        assertTrue(60 in view.extensible.shownLines, "scrolled to it")
        robot.interact { view.showPosition(10, 99) }
        assertEquals(10 to 7, view.area.currentParagraph to view.area.caretColumn, "the line's end")
        robot.interact { view.showPosition(500, 4) }
        assertEquals(99 to 4, view.area.currentParagraph to view.area.caretColumn, "the last line")

        val embedded =
            WaitForAsyncUtils
                .asyncFx<CodeEditor> {
                    CodeEditors.open(
                        file("other.py", "a\nbcd\n"),
                        null,
                    )
                }.get()
        robot.interact {
            container.children.setAll(embedded.node)
            embedded.showPosition(1, 2)
        }
        assertEquals(1, embedded.caretLine, "a CodeEditor goes there too")
    }

    @Test
    fun `an extension follows and moves the caret, converts positions and sets the cursor`(robot: FxRobot) {
        val (view, editor) = plain(robot, "ab\ncd😀e\n" + (1..200).joinToString("\n") { "line $it" })
        val offsets = mutableListOf<Int>()
        robot.interact { editor.caretOffsetProperty.addListener { _, _, offset -> offsets += offset.toInt() } }
        val focused = view.area.isFocused

        robot.interact { editor.moveCaret(4) }
        val far = view.area.getAbsolutePosition(150, 2)
        robot.interact { editor.moveCaret(far) }

        assertEquals(listOf(4, far), offsets)
        assertTrue(150 in editor.shownLines, "scrolled to it")
        assertEquals(focused, view.area.isFocused, "the focus stays")
        robot.interact { editor.moveCaret(-5) }
        assertEquals(0, editor.caretOffset)

        robot.interact {
            assertEquals(TextPosition(0, 2), editor.positionOf(2))
            assertEquals(TextPosition(1, 0), editor.positionOf(3), "after a line break: the next line")
            assertEquals(TextPosition(1, 5), editor.positionOf(8), "the emoji counts twice")
            assertEquals(TextPosition(0, 0), editor.positionOf(-1))
            assertEquals(4, editor.offsetOf(TextPosition(1, 1)))
            assertEquals(2, editor.offsetOf(TextPosition(0, 9)), "the line's end")
            assertEquals(editor.text.length, editor.offsetOf(TextPosition(999, 999)), "the text's end")
        }

        robot.interact {
            editor.setTextCursor(Cursor.HAND)
            view.area.applyCss()
        }
        assertEquals(Cursor.HAND, view.area.cursor, "not reset by the area's stylesheet")
        robot.interact { editor.setTextCursor(null) }
        assertEquals(Cursor.TEXT, view.area.cursor)
    }
}
