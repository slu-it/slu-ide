package dev.sluide.editorarea

import dev.sluide.fileviewers.FileView
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.fileviewers.FileViewer
import javafx.scene.control.Label
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * UI tests of the editor area: views reading and editing another file's tab through their host (ADR 0057). Needs a
 * display, see the `uiTest` Gradle task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class EditorAreaTextEditsUiTest : EditorAreaUiFixture() {
    @Start
    fun start(stage: Stage) = startView(stage)

    /** The host the editor area gave the view of [file]'s tab, as the view itself would use it. */
    private fun hostOf(file: Path): FileViewHost =
        assertNotNull(
            editor.editorTabs
                .find(file)
                ?.editor
                ?.host,
        )

    @Test
    fun `the current text of an open file includes its unsaved changes, a file not open has none`(robot: FxRobot) {
        val a = textFile("a.txt", "a\n")
        open(robot, a)
        robot.interact { shownArea(robot).insertText(0, "edited ") }

        val host = hostOf(a)

        assertEquals("edited a\n", host.currentText(a))
        assertEquals("edited a\n", host.currentText(dir.resolve("sub/../a.txt")))
        assertNull(host.currentText(textFile("b.txt")))
    }

    @Test
    fun `editing an open file changes its tab's text unsaved and keeps the selected tab`(robot: FxRobot) {
        val a = textFile("a.txt", "a\n")
        val b = textFile("b.txt", "b\n")
        open(robot, b, a)
        val host = hostOf(a)

        var edited = false
        robot.interact { edited = host.editText(b) { it + "more\n" } }

        assertTrue(edited)
        assertEquals("b\nmore\n", host.currentText(b))
        assertEquals("b\n", b.readText())
        assertEquals(listOf("● b.txt", "a.txt"), tabTitles(robot))
        assertEquals(a, editor.selectedPath)
        assertEquals("a\n", shownText(robot))
    }

    @Test
    fun `editing a file not open opens it in a tab that is not selected, the focus stays`(robot: FxRobot) {
        val field = fieldAbove(robot)
        val a = textFile("a.txt", "a\n")
        val c = textFile("c.txt", "c\n")
        open(robot, a)
        robot.interact { field.requestFocus() }
        val host = hostOf(a)

        var edited = false
        robot.interact { edited = host.editText(c) { "{$it}" } }

        assertTrue(edited)
        assertEquals(listOf(a, c), editor.openPaths)
        assertEquals(listOf("a.txt", "● c.txt"), tabTitles(robot))
        assertEquals("{c\n}", host.currentText(c))
        assertEquals("c\n", c.readText())
        assertEquals(a, editor.selectedPath)
        assertSame(field, focusOwner())
    }

    /** Opens `.label` files in a view that shows them but is no [dev.sluide.fileviewers.TextEditableView]. */
    private class LabelViewer : FileViewer {
        override fun canOpen(file: Path) = file.toString().endsWith(".label")

        override fun open(file: Path): FileView =
            object : FileView {
                override val node = Label(file.fileName.toString())

                override fun reload() {}
            }
    }

    @Test
    fun `editing a file whose view does not edit text fails, whether its tab is open or not`(robot: FxRobot) {
        fieldAbove(robot, listOf(LabelViewer()))
        val a = textFile("a.txt", "a\n")
        val shown = textFile("shown.label")
        val closed = textFile("closed.label")
        open(robot, shown, a)
        val host = hostOf(a)
        var asked = false

        var edits = listOf<Boolean>()
        robot.interact {
            edits =
                listOf(closed, shown).map { file -> host.editText(file) { text -> text.also { asked = true } } }
        }

        assertEquals(listOf(false, false), edits)
        assertFalse(asked)
        assertNull(host.currentText(shown))
        assertEquals(listOf(shown, a), editor.openPaths)
        assertEquals(a, editor.selectedPath)
    }

    @Test
    fun `a read-only file gives no text and takes no edit, whether its tab is open or not`(robot: FxRobot) {
        // Valid UTF-8 in the sample that decides whether a file is text, after it a byte undefined in Windows-1252 too:
        // shown read-only.
        val bytes = "a".repeat(9000).toByteArray() + 0x81.toByte()
        val shown = dir.resolve("shown.txt").apply { writeBytes(bytes) }
        val closed = dir.resolve("closed.txt").apply { writeBytes(bytes) }
        val a = textFile("a.txt", "a\n")
        open(robot, shown, a)
        val host = hostOf(a)

        var edits = listOf<Boolean>()
        robot.interact { edits = listOf(closed, shown).map { file -> host.editText(file) { "b" } } }

        assertEquals(listOf(false, false), edits)
        assertNull(host.currentText(shown))
        assertEquals(listOf(shown, a), editor.openPaths)
        assertEquals(listOf("shown.txt", "a.txt"), tabTitles(robot))
    }

    @Test
    fun `a tab opened unselected for an edit reloads when its file changes on disk`(robot: FxRobot) {
        val a = textFile("a.txt", "a\n")
        val c = textFile("c.txt", "c\n")
        open(robot, a)
        val host = hostOf(a)
        robot.interact { assertTrue(host.editText(c) { it }) }

        c.writeText("changed\n")

        eventually(robot) { assertEquals("changed\n", host.currentText(c)) }
        assertEquals(a, editor.selectedPath)
    }
}
