package dev.sluide.editorarea

import dev.sluide.fileviewers.DefaultTextFileViewer
import dev.sluide.fileviewers.LineChange
import dev.sluide.fileviewers.TextDiff
import dev.sluide.fileviewers.languages.PythonFileViewer
import javafx.beans.property.SimpleStringProperty
import javafx.scene.layout.Pane
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
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
 * UI tests of the editor area: files changed, deleted or renamed on disk. Needs a display, see the `uiTest` Gradle
 * task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class EditorAreaFileChangesUiTest : EditorAreaUiFixture() {
    @Start
    fun start(stage: Stage) = startView(stage)

    @Test
    fun `an edit on disk reloads the shown content`(robot: FxRobot) {
        val a = textFile("a.txt", "first")
        open(robot, a)

        a.writeText("second")

        eventually(robot) { assertEquals("second", shownText(robot)) }
        assertEquals(a, editor.selectedPath)
    }

    @Test
    fun `deleting a file on disk closes its tab`(robot: FxRobot) {
        val a = textFile("a.txt")
        val b = textFile("b.txt")
        val closed = mutableListOf<Path>()
        editor.onFileClosed = { closed.add(it) }
        open(robot, a, b)

        b.deleteExisting()

        eventually(robot) { assertEquals(listOf("a.txt"), tabTitles(robot)) }
        assertEquals("content of a.txt", shownText(robot))
        assertEquals(listOf(b), closed)
    }

    @Test
    fun `a deleted file with unsaved changes keeps its tab, and saving recreates it`(robot: FxRobot) {
        val a = textFile("a.txt", "a")
        open(robot, a)
        robot.interact { shownArea(robot).appendText("1") }

        a.deleteExisting()

        eventually(robot) { assertEquals("a.txt was deleted on disk; saving recreates it.", bannerText(robot)) }
        assertEquals(listOf("\u25cf a.txt"), tabTitles(robot))

        robot.interact { assertTrue(editor.save()) }
        assertEquals("a1", a.readText())
        assertEquals(listOf("a.txt"), tabTitles(robot))
        eventually(robot) { assertNull(bannerText(robot)) }
    }

    @Test
    fun `saving a file whose folder was deleted shows why it failed`(robot: FxRobot) {
        val folder = dir.resolve("gen").createDirectories()
        val file = folder.resolve("out.txt").apply { writeText("v1") }
        val errors = mutableListOf<String>()
        editor.showError = { errors += it }
        open(robot, file)
        robot.interact { shownArea(robot).appendText("2") }

        folder.toFile().deleteRecursively()
        eventually(robot) { assertNotNull(bannerText(robot)) }

        robot.interact { assertFalse(editor.save()) }
        assertEquals(listOf("out.txt could not be saved. Its folder does not exist."), errors)
        assertEquals(listOf("\u25cf out.txt"), tabTitles(robot))
    }

    @Test
    fun `a file that became binary is closed`(robot: FxRobot) {
        val a = textFile("a.txt")
        open(robot, a)

        a.writeBytes(byteArrayOf(0x00, 0x01, 0x02))

        eventually(robot) { assertEquals(emptyList(), tabTitles(robot)) }
    }

    @Test
    fun `closing tabs ends their watches`(robot: FxRobot) {
        val files = listOf("a.txt", "b.txt", "c.txt").map { textFile(it) }
        open(robot, *files.toTypedArray())
        assertEquals(3, editor.watchCount)

        robot.interact { editor.editorTabs.closeOthers(checkNotNull(editor.editorTabs.find(files[0]))) }
        assertEquals(1, editor.watchCount)

        robot.interact { editor.closeAll() }
        assertEquals(0, editor.watchCount)

        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(emptyList(), tabTitles(robot))
        assertNull(shownText(robot))
        assertNull(editor.selectedPath)
    }

    @Test
    fun `an open file keeps reloading after its folder was deleted and recreated`(robot: FxRobot) {
        val folder = dir.resolve("gen").createDirectories()
        val file = folder.resolve("out.txt").apply { writeText("v1") }
        open(robot, file)

        folder.toFile().deleteRecursively()
        folder.createDirectories()
        file.writeText("v2")
        eventually(robot) { assertEquals("v2", shownText(robot)) }

        file.writeText("v3")
        eventually(robot) { assertEquals("v3", shownText(robot)) }
    }

    @Test
    fun `a renamed file keeps its tab, which follows it to the new path`(robot: FxRobot) {
        val a = textFile("a.txt", "new\n")
        val b = textFile("b.txt")
        editor.diffOf = immediately { if (it == a) TextDiff("old\n", listOf(LineChange(0..0, 0..0))) else null }
        open(robot, a, b)
        val tab = checkNotNull(editor.editorTabs.find(a))
        robot.interact {
            editor.editorTabs.select(tab)
            editor.toggleDiff()
        }
        val renamed = dir.resolve("renamed.txt")

        move(robot, a, renamed)

        assertEquals(listOf(renamed, b), editor.openPaths)
        assertSame(tab, editor.editorTabs.find(renamed), "the same tab and view")
        assertEquals(listOf("renamed.txt", "b.txt"), tabTitles(robot))
        assertEquals(renamed.toString(), editor.tabStrip.tooltipOf(tab).text)
        assertEquals(emptyList(), shownDiff(robot), "the diff of the old path is gone, as is the new path's")
        assertEquals(false, editor.canToggleDiff.value)
        // On the FX thread: a late event of the old watch renews the watch there, and between closing and watching
        // again the count is one lower.
        robot.interact { assertEquals(2, editor.watchCount) }
        // Reloaded after the old path's deletion was reported, which comes first.
        renamed.writeText("changed")
        eventually(robot) { assertEquals("changed", shownText(robot)) }
        assertEquals(listOf("renamed.txt", "b.txt"), tabTitles(robot), "the old path's deletion closes nothing")
    }

    @Test
    fun `a renamed file with unsaved changes stays edited and is saved at its new path`(robot: FxRobot) {
        val a = textFile("a.txt", "a")
        val b = textFile("b.txt", "b")
        open(robot, a)
        robot.interact { shownArea(robot).appendText("1") }
        open(robot, b)
        val renamed = dir.resolve("renamed.txt")

        move(robot, a, renamed)
        // Reloaded after the old path's deletion was reported: the same folder, so the same or a later batch.
        b.writeText("changed")
        eventually(robot) { assertEquals("changed", shownText(robot)) }

        assertEquals(listOf("\u25cf renamed.txt", "b.txt"), tabTitles(robot))
        robot.interact { editor.editorTabs.select(checkNotNull(editor.editorTabs.find(renamed))) }
        assertNull(bannerText(robot), "not deleted")
        robot.interact { assertTrue(editor.save()) }
        assertEquals("a1", renamed.readText())
        assertFalse(a.exists(), "nothing is written at the old path")
        assertEquals(listOf("renamed.txt", "b.txt"), tabTitles(robot))
    }

    @Test
    fun `renaming a folder moves the tabs of the files in it`(robot: FxRobot) {
        val folder = dir.resolve("src").createDirectories()
        val inner =
            folder
                .resolve("x")
                .createDirectories()
                .resolve("inner.txt")
                .apply { writeText("inner") }
        val top = folder.resolve("top.txt").apply { writeText("top") }
        val other =
            dir
                .resolve("src2")
                .createDirectories()
                .resolve("other.txt")
                .apply { writeText("other") }
        open(robot, inner, top, other)
        robot.interact { editor.editorTabs.select(checkNotNull(editor.editorTabs.find(top))) }
        robot.interact { shownArea(robot).appendText("!") }
        val renamed = dir.resolve("lib")

        move(robot, folder, renamed)

        assertEquals(
            listOf(renamed.resolve("x/inner.txt"), renamed.resolve("top.txt"), other),
            editor.openPaths,
        )
        assertEquals(listOf("inner.txt", "\u25cf top.txt", "other.txt"), tabTitles(robot))
        assertEquals(renamed.resolve("top.txt"), editor.selectedPath)
        robot.interact { assertTrue(editor.save()) }
        assertEquals("top!", renamed.resolve("top.txt").readText())
        robot.interact { editor.editorTabs.select(editor.editorTabs.files.first()) }
        // The folder's watches follow it: the new paths reload, and nothing reported closes a tab.
        renamed.resolve("x/inner.txt").writeText("changed")
        eventually(robot) { assertEquals("changed", shownText(robot)) }
        assertEquals(listOf("inner.txt", "top.txt", "other.txt"), tabTitles(robot))
    }

    @Test
    fun `a file renamed to another type opens anew in its tab, unless it has unsaved changes`(robot: FxRobot) {
        val a = textFile("a.txt", "x = 1\n")
        val b = textFile("b.txt", "y = 2\n")
        val c = textFile("c.txt")
        open(robot, a, b, c)
        robot.interact { shownArea(robot).appendText("!") }
        robot.interact { editor.editorTabs.select(checkNotNull(editor.editorTabs.find(a))) }
        val oldTab = editor.selectedTab
        val bTab = editor.editorTabs.find(b)

        move(robot, a, dir.resolve("a.py"))
        move(robot, b, dir.resolve("b.md"))
        move(robot, c, dir.resolve("c.py"))

        assertEquals(listOf("a.py", "b.md", "c.py").map(dir::resolve), editor.openPaths)
        val aTab = checkNotNull(editor.editorTabs.find(dir.resolve("a.py")))
        assertTrue(aTab !== oldTab, "a new tab in its place")
        assertTrue(aTab.viewer is PythonFileViewer)
        assertSame(aTab, editor.selectedTab)
        assertEquals("x = 1\n", shownText(robot))
        assertSame(bTab, editor.editorTabs.find(dir.resolve("b.md")), "the same viewer shows it")
        val cTab = checkNotNull(editor.editorTabs.find(dir.resolve("c.py")))
        assertTrue(cTab.viewer is DefaultTextFileViewer, "unsaved changes keep their view")
        assertEquals(listOf("a.py", "b.md", "\u25cf c.py"), tabTitles(robot))
        // On the FX thread, as a late event of an old watch renews a watch there.
        robot.interact { assertEquals(3, editor.watchCount) }
    }

    @Test
    fun `a file with unsaved changes renamed to the path of another tab keeps them at its old path`(robot: FxRobot) {
        val a = textFile("a.txt", "a")
        val b = textFile("b.txt", "b")
        open(robot, a)
        robot.interact { shownArea(robot).appendText("1") }
        a.deleteExisting()
        eventually(robot) { assertEquals("a.txt was deleted on disk; saving recreates it.", bannerText(robot)) }
        open(robot, b)
        robot.interact { shownArea(robot).appendText("2") }

        move(robot, b, a)

        assertEquals(listOf(a, b), editor.openPaths)
        assertEquals(listOf("\u25cf a.txt", "\u25cf b.txt"), tabTitles(robot))
        assertEquals("b.txt was deleted on disk; saving recreates it.", bannerText(robot))
        robot.interact { editor.editorTabs.select(checkNotNull(editor.editorTabs.find(a))) }
        assertEquals("a.txt changed on disk.", bannerText(robot))
    }

    @Test
    fun `a file renamed to the path of a deleted file with unsaved changes leaves that tab to it`(robot: FxRobot) {
        val a = textFile("a.txt", "a")
        val b = textFile("b.txt", "b")
        open(robot, a)
        robot.interact { shownArea(robot).appendText("1") }
        a.deleteExisting()
        eventually(robot) { assertEquals("a.txt was deleted on disk; saving recreates it.", bannerText(robot)) }
        open(robot, b)

        move(robot, b, a)

        assertEquals(listOf(a), editor.openPaths)
        assertEquals(listOf("\u25cf a.txt"), tabTitles(robot))
        assertEquals("a.txt changed on disk.", bannerText(robot), "at once, so that saving cannot overwrite it unseen")
    }

    @Test
    fun `closing the tabs below a deleted path does not ask, but keeps changes made since`(robot: FxRobot) {
        val folder = dir.resolve("src").createDirectories()
        val inner = folder.resolve("inner.txt").apply { writeText("inner") }
        val clean = folder.resolve("clean.txt").apply { writeText("clean") }
        val later = folder.resolve("later.txt").apply { writeText("later") }
        val other =
            dir
                .resolve("src2")
                .createDirectories()
                .resolve("other.txt")
                .apply { writeText("other") }
        val terminal =
            object : EditorTab {
                override val title = SimpleStringProperty("Terminal")
                override val tooltip = "a terminal"
                override val content = Pane()
                override val closeWarning = "Terminal is still running."
            }
        editor.confirmSave = { error("asked to save $it") }
        editor.confirmClose = { error("asked to close $it") }
        open(robot, inner)
        robot.interact { shownArea(robot).appendText("!") }
        open(robot, clean, other)
        robot.interact { editor.open(terminal) }
        val discarded = editor.dirtyUnder(folder)
        // Edited after the delete was confirmed, while it ran.
        open(robot, later)
        robot.interact { shownArea(robot).appendText("!") }
        folder.toFile().deleteRecursively()

        robot.interact { editor.closeUnder(folder, discarded) }

        assertEquals(listOf(inner), discarded)
        assertEquals(listOf(other, later), editor.openPaths)
        assertEquals(listOf("other.txt", "Terminal", "\u25cf later.txt"), tabTitles(robot))
        assertEquals("later.txt was deleted on disk; saving recreates it.", bannerText(robot))
        assertFalse(inner.exists(), "nothing saved")

        robot.interact { editor.closeUnder(other, emptyList()) }
        assertEquals(listOf("Terminal", "\u25cf later.txt"), tabTitles(robot))
    }
}
