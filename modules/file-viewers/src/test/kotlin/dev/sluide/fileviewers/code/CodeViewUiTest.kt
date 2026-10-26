package dev.sluide.fileviewers.code

import dev.sluide.fileviewers.DefaultTextFileViewer
import dev.sluide.fileviewers.FileView
import dev.sluide.fileviewers.highlight.TomlHighlighter
import dev.sluide.fileviewers.isTextFile
import dev.sluide.fileviewers.languages.PythonFileViewer
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.layout.BorderPane
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.text.Font
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.io.IOException
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectory
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.moveTo
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Edits files in a real [CodeView]. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class CodeViewUiTest {
    @TempDir
    lateinit var dir: Path

    private val container = StackPane()

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(container, 500.0, 300.0)
        stage.show()
    }

    private companion object {
        val SHORT_PAUSE: Duration = Duration.ofMillis(1)
    }

    private fun open(
        file: Path,
        robot: FxRobot,
    ): CodeView {
        val view = WaitForAsyncUtils.asyncFx<CodeView> { CodeView(file, PythonFileViewer().highlighter) }.get()
        robot.interact { container.children.setAll(view.node) }
        return view
    }

    @Test
    fun `an edit makes the view dirty and undoing it clean again`(robot: FxRobot) {
        val file = dir.resolve("main.py").apply { writeText("x = 1\n") }
        val view = open(file, robot)
        assertTrue(view.area.isEditable)
        assertFalse(view.dirty.get(), "clean after opening")

        robot.interact { view.area.insertText(0, "y = 2\n") }
        assertTrue(view.dirty.get())

        robot.interact { view.area.undo() }
        assertEquals("x = 1\n", view.area.text)
        assertFalse(view.dirty.get())
    }

    @Test
    fun `typing after a save is a change of its own`(robot: FxRobot) {
        val file = dir.resolve("main.py").apply { writeText("") }
        val view = open(file, robot)

        robot.interact {
            view.area.insertText(0, "a")
            view.save()
            // Right after the first edit, which the undo manager would otherwise merge with this one.
            view.area.insertText(1, "b")
        }
        assertTrue(view.dirty.get())

        robot.interact { view.area.undo() }
        assertEquals("a", view.area.text)
        assertFalse(view.dirty.get(), "back at the saved text")
    }

    @Test
    fun `saving keeps the line endings of the file`(robot: FxRobot) {
        val file = dir.resolve("main.py").apply { writeText("a = 1\r\nb = 2\r\n") }
        val view = open(file, robot)
        assertEquals("a = 1\nb = 2\n", view.area.text)

        robot.interact {
            view.area.insertText(view.area.length, "c = 3\n")
            view.save()
        }

        assertContentEquals("a = 1\r\nb = 2\r\nc = 3\r\n".toByteArray(), file.readBytes())
        assertFalse(view.dirty.get())
    }

    @Test
    fun `reloading an unchanged file with CRLF line endings keeps the caret`(robot: FxRobot) {
        val file = dir.resolve("main.py").apply { writeText("a = 1\r\nb = 2\r\n") }
        val view = open(file, robot)
        robot.interact { view.area.moveTo(3) }

        robot.interact { view.reload() }

        assertEquals(3, view.area.caretPosition)
        assertFalse(view.dirty.get())
    }

    private fun bannerButton(
        robot: FxRobot,
        text: String,
    ): Button = robot.lookup(".editor-banner .button").queryAllAs(Button::class.java).single { it.text == text }

    private fun encodingMessage(robot: FxRobot): String? =
        robot
            .lookup(".encoding-banner .label")
            .queryAllAs(Label::class.java)
            .singleOrNull()
            ?.text

    /** "Grüße" in Windows-1252, with CRLF line endings. */
    private val windows1252 = byteArrayOf(0x47, 0x72, 0xFC.toByte(), 0xDF.toByte(), 0x65, 0x0D, 0x0A)

    @Test
    fun `a Windows-1252 file is edited and saved as Windows-1252, but no character it cannot store`(robot: FxRobot) {
        val file = dir.resolve("latin1.txt").apply { writeBytes(windows1252) }
        assertTrue(isTextFile(file))
        val view = open(file, robot)
        assertTrue(view.area.isEditable)
        assertEquals("Grüße\n", view.area.text)
        assertEquals("latin1.txt is not valid UTF-8: it is opened and saved as Windows-1252.", encodingMessage(robot))

        robot.interact {
            view.area.insertText(view.area.length, "ä")
            view.save()
        }
        assertContentEquals(windows1252 + 0xE4.toByte(), file.readBytes())
        // Its own save coming back.
        robot.interact { view.reload() }
        assertFalse(view.dirty.get())
        assertFalse(view.conflictShown)
        assertEquals("latin1.txt is not valid UTF-8: it is opened and saved as Windows-1252.", encodingMessage(robot))

        robot.interact { view.area.insertText(view.area.length, "😀") }
        val failure = WaitForAsyncUtils.asyncFx<IOException> { assertFailsWith<IOException> { view.save() } }.get()
        assertTrue(failure.message.orEmpty().startsWith("Line 2 contains '😀'"))
        assertContentEquals(windows1252 + 0xE4.toByte(), file.readBytes())
        assertTrue(view.dirty.get())

        // Converted elsewhere, the same text: the view follows and saves UTF-8.
        file.writeBytes("Grüße\r\nä".toByteArray())
        robot.interact { view.reload() }
        assertEquals(null, encodingMessage(robot))
        robot.interact { view.save() }
        assertContentEquals("Grüße\r\nä😀".toByteArray(), file.readBytes())
    }

    @Test
    fun `converting a Windows-1252 file makes it dirty and saves it as UTF-8`(robot: FxRobot) {
        val file = dir.resolve("latin1.txt").apply { writeBytes(windows1252) }
        val view = open(file, robot)

        robot.interact { bannerButton(robot, "Convert to UTF-8").fire() }
        assertTrue(view.dirty.get())
        assertEquals(null, encodingMessage(robot))
        assertEquals("Grüße\n", view.area.text)
        // The file on disk, still Windows-1252, reported again: the conversion stays pending.
        robot.interact { view.reload() }
        assertTrue(view.dirty.get())
        assertEquals(null, encodingMessage(robot))

        robot.interact { view.save() }
        assertContentEquals("Grüße\r\n".toByteArray(), file.readBytes())
        assertFalse(view.dirty.get())
    }

    @Test
    fun `a file that is neither UTF-8 nor Windows-1252 is shown read-only and never saved`(robot: FxRobot) {
        // 0x81 is undefined in Windows-1252.
        val bytes = "aä".toByteArray(Charsets.ISO_8859_1) + 0x81.toByte()
        val file = dir.resolve("latin1.txt").apply { writeBytes(bytes) }
        assertTrue(isTextFile(file))
        val view = open(file, robot)

        assertTrue(view.readOnly)
        assertFalse(view.area.isEditable)
        assertEquals(
            "latin1.txt is not valid UTF-8; it is shown read-only.",
            robot.lookup(".editor-banner .label").queryAs(Label::class.java).text,
        )
        assertEquals("a${Char(0xFFFD)}", view.area.text, "ä and 0x81 are one malformed UTF-8 sequence")

        robot.interact { view.save() }
        assertContentEquals(bytes, file.readBytes())
        assertFalse(view.dirty.get())

        file.writeBytes("fixé\n".toByteArray(Charsets.ISO_8859_1))
        robot.interact { view.reload() }
        assertEquals("fixé\n", view.area.text)
        assertTrue(view.area.isEditable)
        assertFalse(view.readOnly)
        assertEquals("latin1.txt is not valid UTF-8: it is opened and saved as Windows-1252.", encodingMessage(robot))
    }

    @Test
    fun `after keeping mine, undoing back to the last save still differs from the file`(robot: FxRobot) {
        val file = dir.resolve("main.py").apply { writeText("a = 1\n") }
        val view = open(file, robot)
        robot.interact { view.area.insertText(0, "mine = 1\n") }
        file.writeText("theirs = 1\n")
        robot.interact { view.reload() }
        robot.interact { bannerButton(robot, "Keep mine").fire() }

        robot.interact { view.area.undo() }
        assertEquals("a = 1\n", view.area.text)
        assertTrue(view.dirty.get())

        robot.interact { view.save() }
        assertEquals("a = 1\n", file.readText())
        assertFalse(view.dirty.get())
    }

    @Test
    fun `a change on disk to the text the view holds makes it clean`(robot: FxRobot) {
        val file = dir.resolve("main.py").apply { writeText("a = 1\n") }
        val view = open(file, robot)
        robot.interact { view.area.appendText("b = 2\n") }

        file.writeText("a = 1\r\nb = 2\r\n")
        robot.interact { view.reload() }

        assertFalse(view.conflictShown)
        assertFalse(view.dirty.get())
        assertEquals("a = 1\nb = 2\n", view.area.text)
    }

    @Test
    fun `a file that switched to CRLF on disk is saved with CRLF`(robot: FxRobot) {
        val file = dir.resolve("main.py").apply { writeText("a = 1\n") }
        val view = open(file, robot)

        file.writeBytes("a = 1\r\n".toByteArray())
        robot.interact { view.reload() }
        robot.interact {
            view.area.appendText("b = 2\n")
            view.save()
        }

        assertContentEquals("a = 1\r\nb = 2\r\n".toByteArray(), file.readBytes())
    }

    @Test
    fun `a reload to a text without a line break keeps the line ending`(robot: FxRobot) {
        val file = dir.resolve("main.py").apply { writeBytes("a = 1\r\nb = 2\r\n".toByteArray()) }
        val view = open(file, robot)

        file.writeText("x")
        robot.interact { view.reload() }
        assertEquals("x", view.area.text)
        robot.interact {
            view.area.appendText("\ny")
            view.save()
        }

        assertContentEquals("x\r\ny".toByteArray(), file.readBytes())
    }

    @Test
    fun `a change on disk replaces only what changed and keeps caret and undo`(robot: FxRobot) {
        val file = dir.resolve("main.py").apply { writeText("a = 1\nb = 2\nc = 3\n") }
        val view = open(file, robot)
        robot.interact { view.area.moveTo(view.area.text.indexOf("c")) }

        file.writeText("a = 1\nb = 22\nc = 3\n")
        robot.interact { view.reload() }

        assertEquals("a = 1\nb = 22\nc = 3\n", view.area.text)
        assertEquals(view.area.text.indexOf("c"), view.area.caretPosition)
        assertFalse(view.dirty.get())
        robot.interact { view.area.undo() }
        assertEquals("a = 1\nb = 2\nc = 3\n", view.area.text, "the reload can be undone")
    }

    @Test
    fun `its own save coming back from disk changes nothing`(robot: FxRobot) {
        val file = dir.resolve("main.py").apply { writeText("a = 1\r\n") }
        val view = open(file, robot)
        robot.interact {
            view.area.insertText(0, "b = 2\n")
            view.save()
            // More typing before the file watcher reports the save.
            view.area.insertText(0, "c = 3\n")
            view.reload()
        }

        assertEquals("c = 3\nb = 2\na = 1\n", view.area.text)
        assertTrue(view.dirty.get())
        assertFalse(view.conflictShown)
    }

    @Test
    fun `a change on disk keeps unsaved changes and offers to reload`(robot: FxRobot) {
        val file = dir.resolve("main.py").apply { writeText("a = 1\n") }
        val view = open(file, robot)
        robot.interact { view.area.insertText(0, "mine = 1\n") }

        file.writeText("theirs = 1\n")
        robot.interact { view.reload() }

        assertEquals("mine = 1\na = 1\n", view.area.text)
        assertTrue(view.conflictShown)
        assertEquals(
            "main.py changed on disk.",
            robot.lookup(".editor-banner .label").queryAs(Label::class.java).text,
        )

        robot.interact { bannerButton(robot, "Reload").fire() }

        assertEquals("theirs = 1\n", view.area.text)
        assertFalse(view.dirty.get())
        assertFalse(view.conflictShown)
    }

    @Test
    fun `keeping the unsaved changes writes them over the file on the next save, in the view's encoding`(
        robot: FxRobot,
    ) {
        val file = dir.resolve("main.py").apply { writeBytes("ä = 1\n".toByteArray(Charsets.ISO_8859_1)) }
        val view = open(file, robot)
        robot.interact { view.area.insertText(0, "mine = 1\n") }
        // ASCII, so UTF-8.
        file.writeText("theirs = 1\n")
        robot.interact { view.reload() }

        robot.interact { bannerButton(robot, "Keep mine").fire() }
        assertFalse(view.conflictShown)
        assertTrue(view.dirty.get())

        // The same content reported again is no new conflict, and changes no encoding.
        robot.interact { view.reload() }
        robot.interact { view.reload() }
        assertFalse(view.conflictShown)
        assertEquals("main.py is not valid UTF-8: it is opened and saved as Windows-1252.", encodingMessage(robot))

        robot.interact { view.save() }
        assertContentEquals("mine = 1\nä = 1\n".toByteArray(Charsets.ISO_8859_1), file.readBytes())
    }

    @Test
    fun `banners use the system font and give the focus back to the text`(robot: FxRobot) {
        val file = dir.resolve("main.py").apply { writeText("a = 1\n") }
        // As the editor area sets the editor font.
        robot.interact { container.style = "-fx-font-family: \"Monospaced\"; -fx-font-style: italic;" }
        val view = open(file, robot)
        robot.interact { view.area.insertText(0, "mine = 1\n") }
        file.writeText("theirs = 1\n")
        robot.interact { view.reload() }

        val label = robot.lookup(".editor-banner .label").queryAs(Label::class.java)
        assertEquals(Font.getDefault().family, label.font.family)
        assertEquals("Regular", label.font.style)

        robot.interact { bannerButton(robot, "Keep mine").fire() }
        assertSame(view.area, view.area.scene.focusOwner)
    }

    @Test
    fun `edited code is highlighted after a pause and styling is no undo step`(robot: FxRobot) {
        val file = dir.resolve("main.py").apply { writeText("x = 1\n") }
        val view =
            WaitForAsyncUtils
                .asyncFx<CodeView> { CodeView(file, PythonFileViewer().highlighter, highlightPause = SHORT_PAUSE) }
                .get()
        robot.interact { container.children.setAll(view.node) }

        robot.interact { view.area.insertText(0, "def f(): pass\n") }
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS) { "keyword" in view.area.getStyleOfChar(1) }

        robot.interact { view.area.undo() }
        assertEquals("x = 1\n", view.area.text)
        assertFalse(view.dirty.get())
    }

    @Test
    fun `highlighting done for an older text is dropped`(robot: FxRobot) {
        val file = dir.resolve("main.py").apply { writeText("x = 1\n") }
        val results = ConcurrentLinkedQueue<Runnable>()
        val view =
            WaitForAsyncUtils
                .asyncFx<CodeView> {
                    CodeView(file, PythonFileViewer().highlighter, highlightPause = SHORT_PAUSE) { results += it }
                }.get()
        robot.interact { container.children.setAll(view.node) }

        robot.interact { view.area.insertText(0, "def f(): pass\n") }
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS) { results.isNotEmpty() }
        robot.interact {
            view.area.insertText(view.area.length, "y = 2\n")
            results.poll().run()
        }
        assertTrue(view.area.getStyleOfChar(1).isEmpty(), "the result for the older text is not applied")

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS) { results.isNotEmpty() }
        robot.interact { results.poll().run() }
        assertTrue("keyword" in view.area.getStyleOfChar(1))
    }

    @Test
    fun `highlighting that arrives after dispose is dropped`(robot: FxRobot) {
        val file = dir.resolve("main.py").apply { writeText("x = 1\n") }
        val results = ConcurrentLinkedQueue<Runnable>()
        val view =
            WaitForAsyncUtils
                .asyncFx<CodeView> {
                    CodeView(file, PythonFileViewer().highlighter, highlightPause = SHORT_PAUSE) { results += it }
                }.get()
        robot.interact { container.children.setAll(view.node) }

        robot.interact { view.area.insertText(0, "def f(): pass\n") }
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS) { results.isNotEmpty() }
        robot.interact {
            view.dispose()
            results.poll().run()
        }

        assertTrue(view.area.getStyleOfChar(1).isEmpty(), "the result is not applied to a disposed view")
    }

    @Test
    fun `a deleted file keeps the text and saving recreates it`(robot: FxRobot) {
        val file = dir.resolve("main.py").apply { writeText("a = 1\n") }
        val view = open(file, robot)
        robot.interact { view.area.insertText(0, "mine = 1\n") }

        file.deleteExisting()
        robot.interact { view.reload() }

        assertTrue(view.deletedShown)
        assertEquals(
            "main.py was deleted on disk; saving recreates it.",
            robot.lookup(".editor-banner .label").queryAs(Label::class.java).text,
        )
        assertEquals("mine = 1\na = 1\n", view.area.text)

        robot.interact { view.save() }
        assertEquals("mine = 1\na = 1\n", file.readText())
        assertFalse(view.deletedShown)
    }

    @Test
    fun `a moved file keeps its unsaved changes and undo, and is saved and named at its new path`(robot: FxRobot) {
        val file = dir.resolve("main.py").apply { writeText("a = 1\n") }
        val view = open(file, robot)
        robot.interact { view.area.insertText(0, "mine = 1\n") }
        val moved = dir.resolve("app.py")
        file.moveTo(moved)

        robot.interact { assertTrue(view.moved(moved)) }

        assertEquals("mine = 1\na = 1\n", view.area.text)
        assertTrue(view.dirty.get())
        moved.writeText("theirs = 1\n")
        robot.interact { view.reload() }
        assertEquals("app.py changed on disk.", robot.lookup(".editor-banner .label").queryAs(Label::class.java).text)
        robot.interact { bannerButton(robot, "Keep mine").fire() }

        robot.interact { view.save() }
        assertEquals("mine = 1\na = 1\n", moved.readText())
        assertFalse(file.exists(), "nothing is written at the old path")
        robot.interact { view.area.undo() }
        assertEquals("a = 1\n", view.area.text)
    }

    private fun generatedMessage(robot: FxRobot): String? =
        robot
            .lookup(".generated-banner .label")
            .queryAllAs(Label::class.java)
            .singleOrNull()
            ?.text

    @Test
    fun `a generated file shows a banner until dismissed and stays editable`(robot: FxRobot) {
        val lock = dir.resolve("uv.lock").apply { writeText("version = 1\n") }
        val view = WaitForAsyncUtils.asyncFx<CodeView> { CodeView(lock, TomlHighlighter) }.get()
        robot.interact { container.children.setAll(view.node) }

        assertTrue(view.generatedShown)
        assertEquals(
            "uv.lock is generated by uv from pyproject.toml; change the source and run \"uv lock\" instead of " +
                "editing this one.",
            generatedMessage(robot),
        )
        assertTrue(view.area.isEditable)

        robot.interact { bannerButton(robot, "Dismiss").fire() }
        assertFalse(view.generatedShown)
        assertSame(view.area, view.area.scene.focusOwner)
        robot.interact { view.area.insertText(0, "# edited\n") }
        assertTrue(view.dirty.get())

        robot.interact { view.save() }
        assertEquals("# edited\nversion = 1\n", lock.readText())
        assertFalse(view.generatedShown, "dismissed for the view")
    }

    @Test
    fun `plain text views show the banner too and are not highlighted`(robot: FxRobot) {
        val yarn = dir.resolve("yarn.lock").apply { writeText("# yarn lockfile v1\n") }
        val view = WaitForAsyncUtils.asyncFx<FileView> { DefaultTextFileViewer().open(yarn) }.get()
        robot.interact { container.children.setAll(view.node) }
        val area = (view as CodeView).area
        assertTrue(area.isEditable)
        assertTrue(area.getStyleSpans(0, area.length).all { it.style.isEmpty() })

        assertEquals(
            "yarn.lock is generated by Yarn from package.json; change the source and run \"yarn install\" instead " +
                "of editing this one.",
            generatedMessage(robot),
        )
    }

    @Test
    fun `renaming to or from a generated file's name shows or hides the banner`(robot: FxRobot) {
        val project = dir.resolve("pyproject.toml").apply { writeText("[project]\n") }
        val view = WaitForAsyncUtils.asyncFx<CodeView> { CodeView(project, TomlHighlighter) }.get()
        robot.interact { container.children.setAll(view.node) }
        robot.interact { view.findBar.open() }

        val lock = dir.resolve("Cargo.lock")
        project.moveTo(lock)
        robot.interact { assertTrue(view.moved(lock)) }
        assertTrue(view.generatedShown)
        assertEquals(
            "Cargo.lock is generated by Cargo from Cargo.toml; change the source and run \"cargo update\" instead " +
                "of editing this one.",
            generatedMessage(robot),
        )
        assertSame(view.findBar.node, ((view.node as BorderPane).top as VBox).children.first(), "find bar on top")

        robot.interact { bannerButton(robot, "Dismiss").fire() }
        val folder = dir.resolve("crate").createDirectory()
        val inFolder = folder.resolve("cargo.lock")
        lock.moveTo(inFolder)
        robot.interact { view.moved(inFolder) }
        assertFalse(view.generatedShown, "still dismissed: a moved folder, another case, the same tool")

        val renamed = dir.resolve("poetry.lock")
        inFolder.moveTo(renamed)
        robot.interact { view.moved(renamed) }
        assertTrue(view.generatedShown, "decided again for the new name")
        assertTrue(generatedMessage(robot).orEmpty().startsWith("poetry.lock is generated by Poetry"))

        val toml = dir.resolve("Cargo.toml")
        renamed.moveTo(toml)
        robot.interact { view.moved(toml) }
        assertFalse(view.generatedShown)
        assertEquals(null, generatedMessage(robot))
    }
}
