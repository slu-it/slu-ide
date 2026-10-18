package dev.sluide.fileviewers

import dev.sluide.fileviewers.highlight.TomlHighlighter
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
import java.nio.file.Path
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
    fun `save writes the text in place and makes the view clean`(robot: FxRobot) {
        val file = dir.resolve("main.py").apply { writeText("x = 1\n") }
        val view = open(file, robot)

        robot.interact {
            view.area.insertText(0, "y = 2\n")
            view.save()
        }

        assertEquals("y = 2\nx = 1\n", file.readText())
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

    @Test
    fun `a file that is not valid UTF-8 is shown read-only and never saved`(robot: FxRobot) {
        // Valid in the sample that decides whether a file is text, invalid after it.
        val bytes = ByteArray(9000) { 'a'.code.toByte() } + 0xE9.toByte()
        val file = dir.resolve("latin1.txt").apply { writeBytes(bytes) }
        assertTrue(isTextFile(file))
        val view = open(file, robot)

        assertTrue(view.readOnly)
        assertFalse(view.area.isEditable)
        assertEquals(
            "latin1.txt is not valid UTF-8; it is shown read-only.",
            robot.lookup(".editor-banner .label").queryAs(Label::class.java).text,
        )
        assertTrue(view.area.text.endsWith("a\uFFFD"))

        robot.interact { view.save() }
        assertContentEquals(bytes, file.readBytes())
        assertFalse(view.dirty.get())

        file.writeText("fixed\n")
        robot.interact { view.reload() }
        assertEquals("fixed\n", view.area.text)
        assertTrue(view.area.isEditable)
        assertFalse(view.readOnly)
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
    fun `keeping the unsaved changes writes them over the file on the next save`(robot: FxRobot) {
        val file = dir.resolve("main.py").apply { writeText("a = 1\n") }
        val view = open(file, robot)
        robot.interact { view.area.insertText(0, "mine = 1\n") }
        file.writeText("theirs = 1\n")
        robot.interact { view.reload() }

        robot.interact { bannerButton(robot, "Keep mine").fire() }
        assertFalse(view.conflictShown)
        assertTrue(view.dirty.get())

        // The same content reported again is no new conflict.
        robot.interact { view.reload() }
        assertFalse(view.conflictShown)

        robot.interact { view.save() }
        assertEquals("mine = 1\na = 1\n", file.readText())
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
        val view = open(file, robot)

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
                .asyncFx<CodeView> { CodeView(file, PythonFileViewer().highlighter) { results += it } }
                .get()
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
            "uv.lock is generated by uv from pyproject.toml; change that file and run \"uv lock\" instead of " +
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
    fun `other files show no banner about being generated`(robot: FxRobot) {
        val project = dir.resolve("pyproject.toml").apply { writeText("[project]\n") }
        val view = WaitForAsyncUtils.asyncFx<CodeView> { CodeView(project, TomlHighlighter) }.get()
        robot.interact { container.children.setAll(view.node) }

        assertFalse(view.generatedShown)
        assertEquals(null, generatedMessage(robot))
    }

    @Test
    fun `plain text views show the banner too`(robot: FxRobot) {
        val yarn = dir.resolve("yarn.lock").apply { writeText("# yarn lockfile v1\n") }
        val view = WaitForAsyncUtils.asyncFx<FileView> { DefaultTextFileViewer().open(yarn) }.get()
        robot.interact { container.children.setAll(view.node) }

        assertEquals(
            "yarn.lock is generated by Yarn from package.json; change that file and run \"yarn install\" instead " +
                "of editing this one.",
            generatedMessage(robot),
        )
    }

    @Test
    fun `Pipfile lock opens as JSON with the banner`(robot: FxRobot) {
        val pipfileLock = dir.resolve("Pipfile.lock").apply { writeText("{\"_meta\": {}}\n") }
        val view = WaitForAsyncUtils.asyncFx<FileView> { JsonFileViewer().open(pipfileLock) }.get()
        robot.interact { container.children.setAll(view.node) }

        assertEquals(
            "Pipfile.lock is generated by Pipenv from Pipfile; change that file and run \"pipenv lock\" instead of " +
                "editing this one.",
            generatedMessage(robot),
        )
        assertTrue("property" in (view as CodeView).area.getStyleOfChar(2))
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
            "Cargo.lock is generated by Cargo from Cargo.toml; change that file and run \"cargo update\" instead " +
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
