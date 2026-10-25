package dev.sluide.ide

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.backgroundtasks.BackgroundTasksView
import dev.sluide.commandruns.CommandRunSession
import dev.sluide.fileexplorer.ExplorerBar
import dev.sluide.fileexplorer.ExplorerClipboard
import dev.sluide.fileexplorer.FileExplorerView
import dev.sluide.fileviewers.IgnoreRuleEffects
import dev.sluide.githubactions.RemoteSources
import dev.sluide.gradle.GradleSession
import dev.sluide.httpclient.HttpClientSession
import dev.sluide.ide.github.WindowWorkflowSources
import dev.sluide.settings.FontArea
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.control.MenuItem
import javafx.scene.control.TreeCell
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.layout.VBox
import javafx.stage.Stage
import javafx.stage.Window
import org.fxmisc.richtext.CodeArea
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.moveTo
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * UI tests of the main window: opening a project, the explorer and the tabs it opens. Needs a display, see the `uiTest`
 * Gradle task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class MainWindowProjectUiTest : MainWindowUiFixture() {
    @Start
    fun start(stage: Stage) = startView(stage)

    @Test
    fun `starts with an empty explorer and file views get the window's services and callbacks`(robot: FxRobot) {
        assertEquals(appTitle(), stage.title)
        assertEquals(0, robot.lookup(".tree-cell").queryAllAs(TreeCell::class.java).count { it.text != null })
        assertEquals(listOf("New HTTP Client Setup"), fileExplorer.folderActions.map { it.label })
        assertEquals(listOf("Run"), fileExplorer.fileActions.map { it.label })

        assertSame(httpSessions.single(), editorArea.windowServices.get(HttpClientSession::class.java))
        assertSame(gradleSessions.single(), editorArea.windowServices.get(GradleSession::class.java))
        assertSame(commandRunSessions.single(), editorArea.windowServices.get(CommandRunSession::class.java))
        assertSame(backgroundTasks.single(), editorArea.windowServices.get(BackgroundTasks::class.java))
        assertIs<WindowWorkflowSources>(editorArea.windowServices.get(RemoteSources::class.java))
        assertIs<IgnoreRuleEffects>(editorArea.windowServices.get(IgnoreRuleEffects::class.java))

        val opened = mutableListOf<URI>()
        mainWindow.openWebPage = { opened += it }
        editorArea.openWebPage(URI("https://example.com/docs"))
        assertEquals(listOf(URI("https://example.com/docs")), opened)

        assertNull(editorArea.projectFolder())
        robot.interact { projectService.open(project) }
        assertEquals(project, editorArea.projectFolder())
    }

    @Test
    fun `background tasks are listed below the explorer once they ran past the delay`(robot: FxRobot) {
        val left = fileExplorer.parent as VBox
        assertEquals<Node>(left, splitPaneOf(editorArea).items.first())
        val view = left.children.filterIsInstance<BackgroundTasksView>().single()
        val bar = left.children.first()
        assertIs<ExplorerBar>(bar, "the button bar is above the explorer")
        assertEquals(listOf<Node>(bar, fileExplorer, view), left.children)
        assertFalse(view.isVisible, "idle")
        assertFalse(view.isManaged)
        val minWidth = left.minWidth(-1.0)

        val task = backgroundTasks.single().start("Fetching origin…")
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS) { view.isVisible }
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(
            "Fetching origin…",
            robot
                .from(view)
                .lookup(".background-task-name")
                .queryAs(Label::class.java)
                .text,
        )
        assertTrue(view.height > 0)
        assertEquals(fileExplorer.layoutY + fileExplorer.height, view.layoutY, 1.0, "below the explorer")
        assertEquals(left.height, bar.layoutBounds.height + fileExplorer.height + view.height, 1.0)
        assertEquals(minWidth, left.minWidth(-1.0), "a narrow explorer stays narrow")

        task.close()
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS) { !view.isVisible }
    }

    @Test
    fun `the File menu offers its items in order, with their accelerators, and Terminal offers New Terminal`(
        robot: FxRobot,
    ) {
        fun describe(item: MenuItem): String {
            val key = item.accelerator as? KeyCodeCombination ?: return item.text ?: "-"

            fun on(modifier: KeyCombination.ModifierValue) = modifier == KeyCombination.ModifierValue.DOWN
            val modifiers =
                listOfNotNull(
                    "shortcut".takeIf { on(key.shortcut) },
                    "shift".takeIf { on(key.shift) },
                    "alt".takeIf { on(key.alt) },
                )
            return "${item.text} ${modifiers.joinToString("+")}+${key.code}"
        }

        assertEquals(
            listOf(
                "New Scratch File… shortcut+shift+N",
                "-",
                "Open Folder… shortcut+O",
                "Open Project…",
                "-",
                "Save shortcut+S",
                "Save All shortcut+alt+S",
                "-",
                "Close Tab shortcut+W",
                "-",
                "Settings… shortcut+COMMA",
            ),
            menu(robot, "File").items.map(::describe),
        )
        assertEquals(
            KeyCombination.ModifierValue.UP,
            (fileMenuItem(robot, "Save").accelerator as KeyCodeCombination).alt,
            "Save is not Save All",
        )
        assertTrue(fileMenuItem(robot, "Close Tab").isDisable, "no tab is open")
        assertEquals(listOf("New Terminal shortcut+shift+T"), menu(robot, "Terminal").items.map(::describe))
    }

    @Test
    fun `offers File - Open Project after Open Folder, which shows the project picker`(robot: FxRobot) {
        val openProject = menu(robot, "File").items[3]
        assertEquals("Open Project…", openProject.text)
        // The fixture's windows know no window: closing the picker would end the application.
        projectWindows.exit = {}

        robot.interact { openProject.fire() }

        val pickers =
            WaitForAsyncUtils
                .asyncFx(
                    Callable {
                        Window.getWindows().filterIsInstance<Stage>().filter {
                            it.isShowing && "project-picker" in it.scene.root.styleClass
                        }
                    },
                ).get()
        // Hidden before asserting, so that no picker stays on screen for the next tests.
        robot.interact { pickers.forEach(Stage::hide) }
        assertEquals(1, pickers.size)
    }

    @Test
    fun `opening a project updates title and explorer including hidden files except git's`(robot: FxRobot) {
        project.resolve(".git").createDirectories()
        project.resolve(".hidden").createDirectories()
        project.resolve("README.md").createFile()

        robot.interact { projectService.open(project) }

        assertEquals("${project.name} – ${appTitle()}", stage.title)
        val names =
            robot
                .lookup(".tree-cell")
                .queryAllAs(TreeCell::class.java)
                .sortedBy { it.index }
                .mapNotNull { it.text }
        // The scratches below the project, collapsed (ADR 0066).
        assertEquals(listOf(project.name, ".hidden", "README.md", "Scratches"), names)
    }

    @Test
    fun `the explorer starts as wide as its names need, and resizing the window resizes only the editor area`(
        robot: FxRobot,
    ) {
        project.resolve("README.md").createFile()
        robot.interact { projectService.open(project) }
        val fontSize = fontSettings.font(FontArea.EXPLORER).value.size
        val shortNames = fileExplorer.startWidth()
        assertTrue(shortNames < 24 * fontSize - DIVIDER_SLACK, "start width $shortNames for font size $fontSize")

        // A longer name changes the start width, but not the width the explorer already has.
        project.resolve("a-longer-file-name-${project.name}.txt").createFile()
        robot.interact { fileExplorer.refreshAll() }
        assertTrue(fileExplorer.startWidth() > shortNames, "a longer name needs more")
        val start = fileExplorer.width
        assertEquals(shortNames, start, DIVIDER_SLACK)

        // Shrinks the window before growing it back: it cannot grow past the screen, which on the macOS runners is
        // barely wider than its 1000 px.
        val editorWidth = editorArea.width
        robot.interact { stage.width -= 300 }
        assertEquals(start, fileExplorer.width, DIVIDER_SLACK)
        assertEquals(editorWidth - 300, editorArea.width, DIVIDER_SLACK)

        robot.interact { stage.width += 300 }
        assertEquals(start, fileExplorer.width, DIVIDER_SLACK)
        assertEquals(editorWidth, editorArea.width, DIVIDER_SLACK)
    }

    @Test
    fun `the explorer gets its start width, also without a folder, and at most half of a narrow window`(
        robot: FxRobot,
    ) {
        // The bounds of the start width are StartWidthUiTest's in file-explorer.
        assertEquals(fileExplorer.startWidth(), fileExplorer.width, DIVIDER_SLACK)

        project.resolve("x".repeat(200)).createFile()
        val width = stage.width
        try {
            robot.interact { stage.width = 400.0 }
            robot.interact { projectService.open(project) }
            val split = splitPaneOf(fileExplorer)
            assertTrue(fileExplorer.startWidth() > split.width / 2, "a name that needs more than half the window")
            assertEquals(split.width / 2, fileExplorer.width, DIVIDER_SLACK)
        } finally {
            // The stage is shared by the tests of the class.
            robot.interact { stage.width = width }
        }
    }

    @Test
    fun `a window opened with its folder starts the explorer as wide as its names need`(robot: FxRobot) {
        project.resolve("README.md").createFile()
        val explorer = FileExplorerView(watcher, fontSettings, ExplorerClipboard())
        robot.interact {
            val stage = Stage().also { otherStages += it }
            val projectService = ProjectService().apply { open(project) }
            mainWindow(projectService, explorer, editorArea(), CompletableFuture.completedFuture(true)).show(stage)
        }
        WaitForAsyncUtils.waitForFxEvents()

        val fontSize = fontSettings.font(FontArea.EXPLORER).value.size
        assertTrue(explorer.startWidth() > 12 * fontSize, "the folder's name needs more than the smallest width")
        assertEquals(explorer.startWidth(), explorer.width, DIVIDER_SLACK)
    }

    @Test
    fun `a git repository shows its status as markers in the explorer and follows staging`(robot: FxRobot) {
        assumeTrue(git.available, "git is not installed")
        runGit("init", "-q")
        project.resolve("tracked.md").writeText("one")
        runGit("add", ".")
        runGit("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-q", "-m", "Initial commit")
        project.resolve("tracked.md").writeText("two")
        project.resolve("new.md").createFile()

        robot.interact { projectService.open(project) }

        eventually(robot) {
            // Files: coloured names; the folder: bubbles.
            assertEquals(listOf("file-name-modified"), markersOf(robot, "tracked.md"))
            assertEquals(listOf("file-name-unversioned"), markersOf(robot, "new.md"))
            assertEquals(listOf("file-marker-unversioned", "file-marker-modified"), markersOf(robot, project.name))
        }

        runGit("add", "new.md")
        eventually(robot) { assertEquals(listOf("file-name-added"), markersOf(robot, "new.md")) }

        robot.interact { mainWindow.dispose() }
        assertFalse(gitStatus.active)
    }

    @Test
    fun `opening a file in the explorer shows it in an editable tab`(robot: FxRobot) {
        val readme = project.resolve("README.md").apply { writeText("# Hello") }
        robot.interact { projectService.open(project) }

        robot.interact { fileExplorer.onOpenFile(readme) }

        assertEquals(listOf("README.md"), tabTitles(robot))
        val text = robot.lookup(".editor-content .code-area").queryAs(CodeArea::class.java)
        assertEquals("# Hello", text.text)
        assertEquals(true, text.isEditable)
    }

    /** Stores run arguments for each of [files] in the project's per-file data. */
    private fun storeArguments(vararg files: Path) =
        files.forEach { file -> projectData.updateFileData(file, project) { it.putArray("runArguments").add("--x") } }

    /** The run arguments stored for [file], as JSON; `null` if none are. */
    private fun storedArguments(file: Path) = projectData.fileData(file, project)?.get("runArguments")?.toString()

    @Test
    fun `the tab and the project data of a file renamed in the explorer follow it`(robot: FxRobot) {
        val notes = project.resolve("notes.txt").apply { writeText("notes") }
        val script =
            project
                .resolve("lib")
                .createDirectories()
                .resolve("run.sh")
                .apply { writeText("echo") }
        storeArguments(notes, script)
        robot.interact { projectService.open(project) }
        robot.interact { fileExplorer.onOpenFile(notes) }
        val renamed = project.resolve("todo.txt")
        val tools = project.resolve("tools")

        robot.interact {
            notes.moveTo(renamed)
            fileExplorer.onMoved(notes, renamed)
            // A folder with a file that is not open.
            script.parent.moveTo(tools)
            fileExplorer.onMoved(script.parent, tools)
        }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(listOf("todo.txt"), tabTitles(robot))
        assertEquals(listOf(renamed), editorArea.openPaths)
        assertEquals("[\"--x\"]", storedArguments(renamed))
        assertEquals("[\"--x\"]", storedArguments(tools.resolve("run.sh")))
        assertNull(storedArguments(notes))
        assertNull(storedArguments(script))
    }

    @Test
    fun `deleting in the explorer counts the unsaved files, closes the tabs and forgets the project data below`(
        robot: FxRobot,
    ) {
        val docs = project.resolve("docs").createDirectories()
        val notes = docs.resolve("notes.txt").apply { writeText("notes") }
        val todo = docs.resolve("todo.txt").apply { writeText("todo") }
        val readme = project.resolve("README.md").apply { writeText("# Hello") }
        val script = docs.resolve("run.sh").apply { writeText("echo") }
        storeArguments(notes, script, readme)
        robot.interact { projectService.open(project) }
        robot.interact { fileExplorer.onOpenFile(notes) }
        robot.interact { shownArea(robot).appendText("!") }
        robot.interact {
            fileExplorer.onOpenFile(todo)
            fileExplorer.onOpenFile(readme)
        }

        val unsaved = fileExplorer.unsavedUnder(docs)
        assertEquals(listOf(notes), unsaved)

        docs.toFile().deleteRecursively()
        robot.interact { fileExplorer.onDeleted(docs, unsaved) }

        assertEquals(listOf("README.md"), tabTitles(robot))
        // The project data below the deleted folder is forgotten, also of files that were not open.
        assertNull(storedArguments(notes))
        assertNull(storedArguments(script))
        assertEquals("[\"--x\"]", storedArguments(readme))
    }

    @Test
    fun `Select Opened File reveals the selected tab's file in the explorer and focuses it, from menu, bar and tab`(
        robot: FxRobot,
    ) {
        val file =
            project
                .resolve(
                    "src/main/kotlin/Deep.kt",
                ).also { it.parent.createDirectories() }
                .also { it.createFile() }
        robot.interact { projectService.open(project) }
        val select = menu(robot, "View").items.single { it.text == "Select Opened File" }
        assertEquals(KeyCodeCombination(KeyCode.F1, KeyCombination.ALT_DOWN), select.accelerator)
        robot.interact { assertTrue(select.isDisable, "no tab") }
        val bar = (fileExplorer.parent as VBox).children.filterIsInstance<ExplorerBar>().single()
        robot.interact { assertTrue(bar.selectOpenedButton.isDisable, "no tab") }

        robot.interact { editorArea.open(file) }
        robot.interact { assertFalse(select.isDisable) }
        robot.interact { assertFalse(bar.selectOpenedButton.isDisable) }

        // The tab menu offers the item for the file, since the explorer can show it.
        assertTrue(WaitForAsyncUtils.asyncFx(Callable { editorArea.tabMenu.canSelectInExplorer(file) }).get())

        // Each trigger shows the file from a collapsed tree and moves the focus into the explorer.
        val triggers =
            mapOf<String, () -> Unit>(
                "View menu" to { select.fire() },
                "bar button" to { bar.selectOpenedButton.fire() },
                "tab menu hook" to { checkNotNull(editorArea.selectInExplorer)(file) },
            )
        for ((name, fire) in triggers) {
            robot.interact {
                fileExplorer.collapseAll()
                fileExplorer.scene.root.requestFocus()
            }
            assertFalse(renderedExplorerNames(robot).contains("Deep.kt"), name)

            robot.interact { fire() }

            robot.interact {
                assertEquals(file, fileExplorer.selectionModel.selectedItem?.value, name)
                assertTrue(fileExplorer.isFocused, "$name: the explorer has the focus")
            }
            assertTrue(renderedExplorerNames(robot).contains("Deep.kt"), name)
        }

        robot.interact { menu(robot, "Terminal").items.single().fire() }
        robot.interact { assertTrue(select.isDisable, "a terminal tab has no file") }
        robot.interact { assertTrue(bar.selectOpenedButton.isDisable, "a terminal tab has no file") }
    }

    private fun renderedExplorerNames(robot: FxRobot): List<String?> =
        robot.lookup(".tree-cell").queryAllAs(TreeCell::class.java).mapNotNull { it.text }
}
