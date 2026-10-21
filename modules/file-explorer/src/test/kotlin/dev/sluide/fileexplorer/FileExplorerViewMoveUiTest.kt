package dev.sluide.fileexplorer

import dev.sluide.fileexplorer.actions.MoveQuestion
import dev.sluide.fileexplorer.actions.moveQuestion
import dev.sluide.fileexplorer.tree.FileTreeItem
import dev.sluide.filewatcher.FileWatcher
import dev.sluide.settings.FontSettings
import dev.sluide.settings.SettingsStore
import javafx.application.Platform
import javafx.event.Event
import javafx.scene.Scene
import javafx.scene.control.TreeItem
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.stage.Stage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** Moving entries into another folder, as a drop does, with the question replaced. Needs a display, see `uiTest`. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class FileExplorerViewMoveUiTest {
    @TempDir
    lateinit var project: Path

    @TempDir
    lateinit var scratches: Path

    @TempDir
    lateinit var settingsDir: Path

    private lateinit var view: FileExplorerView
    private val watcher = FileWatcher(Platform::runLater, 50.milliseconds)
    private val moved = mutableListOf<Pair<Path, Path>>()
    private val questions = mutableListOf<MoveQuestion>()
    private val errors = mutableListOf<String>()

    /** What the question answers; may change the disk first, as can happen while the dialog is open. */
    private var answer: () -> Boolean = { true }

    @Start
    fun start(stage: Stage) {
        val fontSettings =
            FontSettings(SettingsStore(settingsDir.resolve("settings.properties")), FontSettings.systemDefaults())
        view = FileExplorerView(watcher, fontSettings)
        view.onMoved = { from, to -> moved += from to to }
        view.confirmMove = {
            questions += it
            answer()
        }
        view.showError = { errors += it }
        stage.scene = Scene(view, 400.0, 600.0)
        stage.show()
    }

    @AfterEach
    fun closeWatcher() = watcher.close()

    private fun open(robot: FxRobot) {
        robot.interact { view.open(project, listOf(ExplorerRoot(scratches, "Scratches"))) }
        WaitForAsyncUtils.waitForFxEvents()
    }

    /** The shown entry named [name] (a path relative to its top-level folder), loading the folders down to it. */
    private fun item(
        name: String,
        top: TreeItem<Path> = view.projectNode,
    ): FileTreeItem =
        name.split('/').fold(top) { folder, segment ->
            folder.children.first { it.value.fileName.toString() == segment }
        } as FileTreeItem

    private fun scratchesItem() = view.root.children.last() as FileTreeItem

    private fun move(
        robot: FxRobot,
        items: List<FileTreeItem>,
        folder: FileTreeItem,
    ) {
        robot.interact { view.move(items, folder) }
        WaitForAsyncUtils.waitForFxEvents()
    }

    private fun selectedPaths(): List<Path> =
        view.selectionModel.selectedItems
            .filterNotNull()
            .sortedBy { view.getRow(it) }
            .map { it.value }

    @Test
    fun `moves the entries after one question, reports each move and selects them in the folder`(robot: FxRobot) {
        project.resolve("a.txt").writeText("a")
        project.resolve("src/main").createDirectories()
        project.resolve("src/main/inner.txt").writeText("inner")
        project.resolve("target").createDirectories()
        open(robot)
        robot.interact { item("src").isExpanded = true }

        move(robot, listOf(item("a.txt"), item("src")), item("target"))

        val target = project.resolve("target")
        assertEquals(listOf(moveQuestion(listOf("a.txt", "src"), emptyList(), "target")), questions)
        assertEquals(
            listOf(
                project.resolve("a.txt") to target.resolve("a.txt"),
                project.resolve("src") to target.resolve("src"),
            ),
            moved,
        )
        assertEquals("a", target.resolve("a.txt").readText())
        assertEquals("inner", target.resolve("src/main/inner.txt").readText())
        assertFalse(project.resolve("src").exists())
        robot.interact {
            assertTrue(item("target").isExpanded)
            assertTrue(item("target/src").isExpanded, "a moved folder stays expanded")
            assertEquals(listOf("target"), view.projectNode.children.map { it.value.fileName.toString() })
            // Folders first, as listed.
            assertEquals(listOf(target.resolve("src"), target.resolve("a.txt")), selectedPaths())
        }
        assertEquals(emptyList(), errors)
    }

    @Test
    fun `entries whose names exist in the folder are skipped after saying so, never replaced`(robot: FxRobot) {
        project.resolve("a.txt").writeText("a")
        project.resolve("b.txt").writeText("mine")
        project.resolve("target").createDirectories()
        project.resolve("target/b.txt").writeText("theirs")
        open(robot)

        move(robot, listOf(item("a.txt"), item("b.txt")), item("target"))

        val question = questions.single()
        assertEquals(moveQuestion(listOf("a.txt"), listOf("b.txt"), "target"), question)
        assertTrue(question.skips)
        assertEquals(listOf(project.resolve("a.txt") to project.resolve("target/a.txt")), moved)
        assertEquals("mine", project.resolve("b.txt").readText())
        assertEquals("theirs", project.resolve("target/b.txt").readText())
        robot.interact { assertEquals(listOf(project.resolve("target/a.txt")), selectedPaths()) }
    }

    @Test
    fun `when every name exists in the folder an error is shown and nothing moves`(robot: FxRobot) {
        project.resolve("a.txt").writeText("mine")
        project.resolve("b.txt").createFile()
        project.resolve("target").createDirectories()
        project.resolve("target/a.txt").writeText("theirs")
        project.resolve("target/b.txt").createFile()
        open(robot)

        move(robot, listOf(item("a.txt")), item("target"))
        move(robot, listOf(item("a.txt"), item("b.txt")), item("target"))

        assertEquals(
            listOf(
                "“a.txt” already exists in “target”.",
                "None of the 2 entries can be moved: their names already exist in “target”.",
            ),
            errors,
        )
        assertEquals(emptyList(), questions)
        assertEquals(emptyList(), moved)
        assertEquals("mine", project.resolve("a.txt").readText())
        assertEquals("theirs", project.resolve("target/a.txt").readText())
    }

    @Test
    fun `Cancel moves nothing and selects the entries again, nested ones too`(robot: FxRobot) {
        project.resolve("a.txt").createFile()
        project.resolve("src").createDirectories()
        project.resolve("src/b.txt").createFile()
        project.resolve("target").createDirectories()
        open(robot)
        answer = { false }
        robot.interact {
            item("src").isExpanded = true
            view.selectionModel.clearSelection()
        }

        move(robot, listOf(item("src"), item("src/b.txt"), item("a.txt")), item("target"))

        assertEquals(1, questions.size)
        assertEquals(emptyList(), moved)
        assertTrue(project.resolve("a.txt").exists())
        assertFalse(project.resolve("target/a.txt").exists())
        robot.interact {
            assertEquals(
                listOf(project.resolve("src"), project.resolve("src/b.txt"), project.resolve("a.txt")),
                selectedPaths(),
            )
        }
    }

    @Test
    fun `entries move into the extra root and out of it`(robot: FxRobot) {
        project.resolve("a.txt").createFile()
        scratches.resolve("notes").createDirectories()
        scratches.resolve("notes/s.md").createFile()
        open(robot)

        move(robot, listOf(item("a.txt")), scratchesItem())

        assertEquals("Move “a.txt” to “Scratches”?", questions.single().header)
        assertEquals(listOf(project.resolve("a.txt") to scratches.resolve("a.txt")), moved)
        robot.interact {
            assertTrue(scratchesItem().isExpanded)
            assertEquals(listOf(scratches.resolve("a.txt")), selectedPaths())
        }

        move(robot, listOf(item("notes/s.md", scratchesItem())), view.projectNode as FileTreeItem)

        assertEquals(scratches.resolve("notes/s.md") to project.resolve("s.md"), moved.last())
        assertTrue(project.resolve("s.md").exists())
        robot.interact { assertEquals(listOf(project.resolve("s.md")), selectedPaths()) }
    }

    @Test
    fun `a failure stops the moves after it and is shown, the earlier ones stay`(robot: FxRobot) {
        project.resolve("a.txt").createFile()
        project.resolve("b.txt").createFile()
        project.resolve("c.txt").createFile()
        project.resolve("target").createDirectories()
        open(robot)
        answer = {
            project.resolve("b.txt").deleteExisting()
            true
        }

        move(robot, listOf(item("a.txt"), item("b.txt"), item("c.txt")), item("target"))

        assertEquals(listOf(project.resolve("a.txt") to project.resolve("target/a.txt")), moved)
        assertEquals(listOf("Could not move “b.txt”: ${project.resolve("b.txt")} does not exist."), errors)
        assertTrue(project.resolve("c.txt").exists())
        robot.interact {
            assertEquals(
                listOf("c.txt", "target"),
                view.projectNode.children
                    .map { it.value.fileName.toString() }
                    .sorted(),
            )
            assertEquals(listOf(project.resolve("target/a.txt")), selectedPaths())
        }
    }

    @Test
    fun `a name taken while asking stops with an error and replaces nothing`(robot: FxRobot) {
        project.resolve("a.txt").writeText("mine")
        project.resolve("target").createDirectories()
        open(robot)
        answer = {
            project.resolve("target/a.txt").writeText("theirs")
            true
        }

        move(robot, listOf(item("a.txt")), item("target"))

        assertEquals(emptyList(), moved)
        assertEquals(listOf("“a.txt” already exists in “target”."), errors)
        assertEquals("mine", project.resolve("a.txt").readText())
        assertEquals("theirs", project.resolve("target/a.txt").readText())
    }

    @Test
    fun `an entry inside a moved folder goes along with it, not on its own`(robot: FxRobot) {
        project.resolve("src").createDirectories()
        project.resolve("src/inner.txt").createFile()
        project.resolve("target").createDirectories()
        open(robot)

        move(robot, listOf(item("src/inner.txt"), item("src")), item("target"))

        assertEquals("Move “src” to “target”?", questions.single().header)
        assertEquals(listOf(project.resolve("src") to project.resolve("target/src")), moved)
        assertTrue(project.resolve("target/src/inner.txt").exists())
    }

    @Test
    fun `nothing happens for a folder inside a moved one, the folder the entries are in, or a top-level entry`(
        robot: FxRobot,
    ) {
        project.resolve("a.txt").createFile()
        project.resolve("src/deep").createDirectories()
        open(robot)

        move(robot, listOf(item("src")), item("src"))
        move(robot, listOf(item("src")), item("src/deep"))
        move(robot, listOf(item("a.txt")), view.projectNode as FileTreeItem)
        move(robot, listOf(scratchesItem()), view.projectNode as FileTreeItem)
        move(robot, listOf(item("src")), item("a.txt"))

        assertEquals(emptyList(), questions)
        assertEquals(emptyList(), moved)
        assertEquals(emptyList(), errors)
        assertTrue(Files.isDirectory(project.resolve("src/deep")))
    }

    /** Starts deleting [names] after a confirmed question, leaving the background run pending; returns the run. */
    private fun startDeleting(vararg names: String): MutableList<Runnable> {
        val pending = mutableListOf<Runnable>()
        view.confirmDelete = { true }
        view.runInBackground = { pending += it }
        view.delete(names.map { item(it) })
        return pending
    }

    @Test
    fun `while an entry is deleted nothing moves into it, its folders do not move and cannot be renamed or deleted`(
        robot: FxRobot,
    ) {
        project.resolve("a/b/c").createDirectories()
        project.resolve("x.txt").createFile()
        project.resolve("target").createDirectories()
        open(robot)
        val renames = mutableListOf<String>()
        var deleteQuestions = 0
        robot.interact {
            item("a").isExpanded = true
            item("a/b").isExpanded = true
            view.askName = { _, initial, _ ->
                renames += initial
                null
            }
        }
        lateinit var pending: MutableList<Runnable>
        robot.interact { pending = startDeleting("a/b") }
        view.confirmDelete = {
            deleteQuestions++
            true
        }

        move(robot, listOf(item("x.txt")), item("a/b"))
        move(robot, listOf(item("x.txt")), item("a/b/c"))
        move(robot, listOf(item("a")), item("target"))
        robot.interact {
            view.selectOnly(item("a"))
            Event.fireEvent(view, KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F2, false, false, false, false))
            Event.fireEvent(view, KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.DELETE, false, false, false, false))
        }

        assertEquals(emptyList(), questions)
        assertEquals(emptyList(), moved)
        assertEquals(emptyList(), renames, "the folder holding the entry being deleted cannot be renamed")
        assertEquals(0, deleteQuestions)

        // Once the delete is done, the folder can be changed again.
        robot.interact { pending.forEach(Runnable::run) }
        WaitForAsyncUtils.waitForFxEvents()
        robot.interact {
            view.selectOnly(item("a"))
            Event.fireEvent(view, KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F2, false, false, false, false))
        }
        assertEquals(listOf("a"), renames)
    }

    @Test
    fun `a delete started while asking stops the move with an error`(robot: FxRobot) {
        project.resolve("a.txt").createFile()
        project.resolve("target").createDirectories()
        open(robot)
        answer = {
            startDeleting("target")
            true
        }

        move(robot, listOf(item("a.txt")), item("target"))

        assertEquals(1, questions.size)
        assertEquals(emptyList(), moved)
        assertEquals(listOf("Nothing was moved: “target” or one of the entries is being deleted."), errors)
        assertTrue(project.resolve("a.txt").exists())
    }
}
