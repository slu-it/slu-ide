package dev.sluide.editorarea

import javafx.scene.control.Button
import javafx.stage.Stage
import org.fxmisc.richtext.CodeArea
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * UI tests of the editor area: unsaved changes, saving and asking to save before closing. Needs a display, see the
 * `uiTest` Gradle task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class EditorAreaSavingUiTest : EditorAreaUiFixture() {
    @Start
    fun start(stage: Stage) = startView(stage)

    @Test
    fun `save and saveAll save the edited files and report them`(robot: FxRobot) {
        val a = textFile("a.txt", "a")
        val b = textFile("b.txt", "b")
        val saved = mutableListOf<Path>()
        editor.onFileSaved = { saved.add(it) }
        open(robot, a)
        robot.interact { shownArea(robot).appendText("1") }
        open(robot, b)
        robot.interact { shownArea(robot).appendText("2") }

        robot.interact { assertTrue(editor.save()) }
        assertEquals(listOf("a", "b2"), listOf(a.readText(), b.readText()))
        assertEquals(listOf(b), saved)

        robot.interact { assertTrue(editor.saveAll()) }
        assertEquals(listOf("a1", "b2"), listOf(a.readText(), b.readText()))
        assertEquals(listOf(b, a), saved)
        assertEquals(listOf("a.txt", "b.txt"), tabTitles(robot))
    }

    @Test
    fun `save all saves the other files when one cannot be saved`(robot: FxRobot) {
        val area = areaWith(robot, UnsavableViewer())
        val errors = mutableListOf<String>()
        area.showError = { errors += it }
        val saved = mutableListOf<Path>()
        area.onFileSaved = { saved.add(it) }
        val a = textFile("a.txt", "a")
        val b = textFile("b.txt", "b")
        val notes = textFile("notes.unsavable")
        robot.interact {
            area.open(a)
            area.open(notes)
            area.open(b)
        }
        for (file in listOf(a, b)) {
            robot.interact {
                area.open(file)
                robot.lookup(".editor-content .code-area").queryAs(CodeArea::class.java).appendText("1")
            }
        }

        robot.interact { assertFalse(area.saveAll()) }

        assertEquals(listOf("a1", "b1"), listOf(a.readText(), b.readText()))
        assertEquals(listOf("notes.unsavable could not be saved. Disk full"), errors)
        assertEquals(listOf(a, b), saved, "a file that could not be saved is not reported")
        assertEquals(listOf("a.txt", "\u25cf notes.unsavable", "b.txt"), area.tabs.map { it.title.value })

        robot.interact {
            area.open(notes)
            assertFalse(area.save())
        }
        assertEquals(List(2) { "notes.unsavable could not be saved. Disk full" }, errors)
    }

    @Test
    fun `closing a tab with unsaved changes asks to save, Cancel keeps it, Don't Save discards them`(robot: FxRobot) {
        val a = textFile("a.txt", "a")
        var answer = SaveChoice.CANCEL
        val asked = openEdited(robot, a) { answer }
        val close = tabLabel(robot, "\u25cf a.txt").parent.lookup(".close-button") as Button

        robot.interact { editor.closeSelected() }
        assertEquals(listOf(listOf("a.txt")), asked)
        assertEquals(listOf("\u25cf a.txt"), tabTitles(robot))

        answer = SaveChoice.DISCARD
        robot.interact { close.fire() }
        assertEquals(listOf(listOf("a.txt"), listOf("a.txt")), asked)
        assertEquals(emptyList(), tabTitles(robot))
        assertEquals("a", a.readText())
    }

    @Test
    fun `Save saves the files before their tabs close, and close all asks once for all of them`(robot: FxRobot) {
        val a = textFile("a.txt", "a")
        val b = textFile("b.txt", "b")
        val asked = openEdited(robot, a, b) { SaveChoice.SAVE }
        open(robot, textFile("clean.txt"))
        val saved = mutableListOf<Path>()
        editor.onFileSaved = { saved.add(it) }

        robot.interact {
            editor.tabStrip
                .contextMenuOf(checkNotNull(editor.editorTabs.find(a)))
                .items
                .first { it.text == "Close All" }
                .fire()
        }

        assertEquals(listOf(listOf("a.txt", "b.txt")), asked)
        assertEquals(emptyList(), tabTitles(robot))
        assertEquals(listOf("edited a", "edited b"), listOf(a.readText(), b.readText()))
        assertEquals(listOf(a, b), saved)
    }

    @Test
    fun `confirmCloseAll asks once for all edited files and is false when cancelled`(robot: FxRobot) {
        val a = textFile("a.txt", "a")
        val b = textFile("b.txt", "b")
        var answer = SaveChoice.CANCEL
        val asked = openEdited(robot, a, b) { answer }

        robot.interact { assertFalse(editor.confirmCloseAll()) }
        answer = SaveChoice.DISCARD
        robot.interact { assertTrue(editor.confirmCloseAll()) }

        assertEquals(List(2) { listOf("a.txt", "b.txt") }, asked)
        assertEquals(listOf("a", "b"), listOf(a.readText(), b.readText()))
    }

    @Test
    fun `saveBeforeGitChange asks about the edited files in the folder only, Anyway keeps their changes`(
        robot: FxRobot,
    ) {
        val repo = dir.resolve("repo").createDirectories()
        val a = repo.resolve("a.txt").apply { writeText("a") }
        val b = repo.resolve("b.txt").apply { writeText("b") }
        val outside = textFile("outside.txt", "outside")
        open(robot, repo.resolve("clean.txt").apply { writeText("clean") })
        openEdited(robot, a, b, outside) { error("not asked when closing") }
        val asked = mutableListOf<List<String>>()
        val actions = mutableListOf<String>()
        var answer = SaveChoice.CANCEL
        editor.confirmSaveBeforeGitChange = { files, action ->
            asked += files
            actions += action
            answer
        }

        robot.interact { assertFalse(editor.saveBeforeGitChange(repo, "Check Out")) }
        answer = SaveChoice.DISCARD
        robot.interact { assertTrue(editor.saveBeforeGitChange(repo, "Cherry-Pick")) }

        assertEquals(List(2) { listOf("repo/a.txt", "repo/b.txt") }, asked)
        assertEquals(listOf("clean.txt", "\u25cf a.txt", "\u25cf b.txt", "\u25cf outside.txt"), tabTitles(robot))
        assertEquals(listOf("a", "b"), listOf(a.readText(), b.readText()))
        // The checkout changes a file: its tab keeps the changes and offers the file's new text.
        robot.interact { editor.editorTabs.select(checkNotNull(editor.editorTabs.find(b))) }
        b.writeText("checked out")
        eventually(robot) { assertTrue(bannerText(robot).orEmpty().contains("changed on disk"), bannerText(robot)) }
        assertEquals("edited b", shownText(robot))

        answer = SaveChoice.SAVE
        robot.interact { assertTrue(editor.saveBeforeGitChange(repo, "Reset")) }
        assertEquals("edited a", a.readText())
        assertEquals("outside", outside.readText(), "not in the folder")
        robot.interact { assertTrue(editor.saveBeforeGitChange(dir.resolve("other"), "Revert")) }
        assertEquals(3, asked.size, "nothing edited there")
        assertEquals(listOf("Check Out", "Cherry-Pick", "Reset"), actions, "the action named in the question")
    }

    @Test
    fun `a tab whose file cannot be saved stays open`(robot: FxRobot) {
        val area = areaWith(robot, UnsavableViewer())
        val errors = mutableListOf<String>()
        area.showError = { errors += it }
        area.confirmSave = { SaveChoice.SAVE }
        robot.interact { area.open(textFile("notes.unsavable")) }
        val tab = area.tabs.single()

        robot.interact {
            area.tabStrip
                .contextMenuOf(tab)
                .items
                .first { it.text == "Close" }
                .fire()
        }

        assertEquals(listOf(tab), area.tabs)
        assertEquals(1, errors.size)
    }

    @Test
    fun `closing a tab with a warning asks first and keeps it on cancel, also a file view's`(robot: FxRobot) {
        val viewer = BusyViewer()
        val area = areaWith(robot, viewer)
        val asked = mutableListOf<List<String>>()
        var answer = false
        area.confirmClose = { warnings ->
            asked += warnings
            answer
        }
        val busy = WarningTab("Terminal 1", "Terminal 1 is still running java.")
        robot.interact {
            area.open(busy)
            area.open(textFile("build.busy"))
        }
        viewer.views.single().closeWarning = "build.busy is still running :test."
        val close = tabLabel(robot, "Terminal 1").parent.lookup(".close-button") as Button
        val closeFile = tabLabel(robot, "build.busy").parent.lookup(".close-button") as Button

        robot.interact { close.fire() }
        robot.interact { closeFile.fire() }
        assertEquals(
            listOf(listOf("Terminal 1 is still running java."), listOf("build.busy is still running :test.")),
            asked,
        )
        assertEquals(listOf("Terminal 1", "build.busy"), tabTitles(robot))

        answer = true
        robot.interact { close.fire() }
        robot.interact { closeFile.fire() }
        assertEquals(emptyList(), tabTitles(robot))
    }

    @Test
    fun `tabs without a warning close without asking, close all asks once, closing the window does not`(
        robot: FxRobot,
    ) {
        val asked = mutableListOf<List<String>>()
        editor.confirmClose = { warnings ->
            asked += warnings
            false
        }
        val idle = WarningTab("Terminal 1", null)
        val busy1 = WarningTab("Terminal 2", "Terminal 2 is still running vim.")
        val busy2 = WarningTab("Terminal 3", "Terminal 3 is still running sleep.")
        val a = textFile("a.txt")
        open(robot, a)
        robot.interact { listOf(idle, busy1, busy2).forEach(editor::open) }

        robot.interact {
            editor.tabStrip
                .contextMenuOf(busy2)
                .items
                .first { it.text == "Close All" }
                .fire()
        }
        assertEquals(listOf(listOf("Terminal 2 is still running vim.", "Terminal 3 is still running sleep.")), asked)
        assertEquals(listOf("a.txt", "Terminal 1", "Terminal 2", "Terminal 3"), tabTitles(robot))

        robot.interact {
            editor.tabStrip
                .contextMenuOf(
                    busy1,
                ).items
                .first { it.text == "Close Tabs to the Left" }
                .fire()
        }
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(listOf("Terminal 2", "Terminal 3"), tabTitles(robot))
        assertEquals(1, asked.size, "nothing to confirm for the file and the idle terminal")

        busy1.closeWarning = null
        robot.interact { editor.closeAll() }
        assertEquals(emptyList(), tabTitles(robot))
        assertEquals(1, asked.size, "closing all with the window does not ask")
    }
}
