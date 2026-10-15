package dev.sluide.ide

import dev.sluide.dockersandbox.DockerSandbox
import dev.sluide.editor.EditorArea
import dev.sluide.editor.EditorTab
import dev.sluide.editor.SaveChoice
import dev.sluide.fileexplorer.FileExplorerView
import dev.sluide.fileviewers.DefaultTextFileViewer
import dev.sluide.fileviewers.FileViewers
import dev.sluide.filewatcher.FileWatcher
import dev.sluide.git.GitCli
import dev.sluide.git.GitStatus
import dev.sluide.git.GitStatusService
import dev.sluide.httpclient.HttpClientSession
import dev.sluide.settings.FontArea
import dev.sluide.settings.FontSettings
import dev.sluide.settings.FontSpec
import dev.sluide.settings.FontsPage
import dev.sluide.settings.SettingsDialog
import dev.sluide.settings.SettingsPages
import dev.sluide.settings.SettingsStore
import dev.sluide.settings.ThemeSettings
import dev.sluide.settings.ThemedWindows
import dev.sluide.terminal.Terminals
import javafx.application.Platform
import javafx.beans.property.SimpleStringProperty
import javafx.event.ActionEvent
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.control.Menu
import javafx.scene.control.MenuBar
import javafx.scene.control.MenuItem
import javafx.scene.control.TreeCell
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyEvent
import javafx.scene.layout.HBox
import javafx.scene.text.Text
import javafx.stage.Stage
import javafx.stage.WindowEvent
import org.fxmisc.richtext.CodeArea
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.net.URI
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CompletableFuture
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.moveTo
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** Shows the real main window. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class MainWindowUiTest {
    @TempDir
    lateinit var project: Path

    @TempDir
    lateinit var otherProject: Path

    @TempDir
    lateinit var settingsDir: Path

    /** The home folder of the Docker sandboxes; `project` and `otherProject` are not below it. */
    @TempDir
    lateinit var home: Path

    private val projectService = ProjectService()
    private lateinit var stage: Stage
    private lateinit var fileExplorer: FileExplorerView
    private lateinit var editorArea: EditorArea
    private lateinit var mainWindow: MainWindow
    private lateinit var fontSettings: FontSettings
    private val watcher = FileWatcher(Platform::runLater, 50.milliseconds)
    private lateinit var terminals: Terminals

    /** The environment of the terminals' and sandboxes' shells; tests may change it. */
    private val shellEnvironment = System.getenv().toMutableMap()

    /** The folders the window asked to open in other windows. */
    private val openedElsewhere = mutableListOf<Path>()
    private val projectWindows =
        object : ProjectWindows(DefaultListableBeanFactory()) {
            override fun open(folder: Path) {
                openedElsewhere.add(folder)
            }
        }

    // Without the user's global git configuration, which could ignore other files, and without inherited `GIT_*`
    // variables (a build run by `git rebase -x` inherits `GIT_DIR`, which would point at the SluIde repository).
    private val gitEnvironment =
        System.getenv().filterKeys { !it.startsWith("GIT_") } +
            mapOf("GIT_CONFIG_GLOBAL" to "/dev/null", "GIT_CONFIG_NOSYSTEM" to "1")
    private val git = GitCli("git", gitEnvironment)
    private val gitStatus = GitStatusService(git, watcher)

    @AfterEach
    fun close() {
        Platform.runLater { otherStages.forEach { it.hide() } }
        WaitForAsyncUtils.waitForFxEvents()
        terminals.close()
        gitStatus.close()
        watcher.close()
        httpSessions.forEach(HttpClientSession::close)
    }

    /** The HTTP client sessions of the windows a test created; closed after it. */
    private val httpSessions = mutableListOf<HttpClientSession>()

    @Start
    fun start(stage: Stage) {
        this.stage = stage
        fontSettings =
            FontSettings(SettingsStore(settingsDir.resolve("settings.properties")), FontSettings.systemDefaults())
        terminals = Terminals(fontSettings, shellEnvironment)
        fileExplorer = FileExplorerView(watcher, fontSettings)
        editorArea = editorArea()
        mainWindow = mainWindow(projectService, fileExplorer, editorArea, CompletableFuture.completedFuture(true))
        mainWindow.show(stage)
    }

    private fun editorArea(): EditorArea {
        val fallback = DefaultTextFileViewer()
        return EditorArea(FileViewers(listOf(fallback), fallback), watcher, fontSettings)
    }

    /** A main window whose sandboxes count `sbx` as installed once [sbxInstalled] completes with `true`. */
    private fun mainWindow(
        projectService: ProjectService,
        fileExplorer: FileExplorerView,
        editorArea: EditorArea,
        sbxInstalled: CompletableFuture<Boolean>,
    ) = MainWindow(
        projectService,
        fileExplorer,
        editorArea,
        terminals,
        DockerSandbox(terminals, home, shellEnvironment, sbxInstalled),
        gitStatus,
        SettingsPages(listOf(FontsPage(fontSettings))),
        ThemedWindows(ThemeSettings(SettingsStore(settingsDir.resolve("settings.properties")))),
        projectWindows,
        HttpClientSession().also(httpSessions::add),
        SystemBrowser(),
    )

    /** Windows shown by a test besides the main one; closed after it. */
    private val otherStages = mutableListOf<Stage>()

    /** The Docker Sandbox menu of another window, with a folder below the home folder open, told by [sbxInstalled]. */
    private fun otherWindowSandboxMenu(
        robot: FxRobot,
        sbxInstalled: CompletableFuture<Boolean>,
    ): Menu {
        lateinit var menu: Menu
        robot.interact {
            val stage = Stage().also { otherStages += it }
            val projectService = ProjectService()
            mainWindow(projectService, FileExplorerView(watcher, fontSettings), editorArea(), sbxInstalled).show(stage)
            projectService.open(home.resolve("work/project").createDirectories())
            menu =
                robot
                    .from(stage.scene.root)
                    .lookup(".menu-bar")
                    .queryAs(MenuBar::class.java)
                    .menus
                    .single { it.text == "Docker Sandbox" }
        }
        return menu
    }

    @Test
    fun `starts with an empty explorer and the plain title`(robot: FxRobot) {
        assertEquals("Thoreau", stage.title)
        assertEquals(0, robot.lookup(".tree-cell").queryAllAs(TreeCell::class.java).count { it.text != null })
    }

    @Test
    fun `file views find the window's HTTP client session`() {
        assertIs<HttpClientSession>(editorArea.windowServices.single())
    }

    @Test
    fun `offers File - Open Folder with Ctrl+O`(robot: FxRobot) {
        val fileMenu = menu(robot, "File")
        val openFolder = fileMenu.items.first()

        assertEquals("Open Folder…", openFolder.text)
        val accelerator = openFolder.accelerator as KeyCodeCombination
        assertEquals(KeyCode.O, accelerator.code)
        assertEquals(KeyCombination.ModifierValue.DOWN, accelerator.shortcut)
    }

    @Test
    fun `opening a project updates title and explorer including hidden files except git's`(robot: FxRobot) {
        project.resolve(".git").createDirectories()
        project.resolve(".hidden").createDirectories()
        project.resolve("README.md").createFile()

        robot.interact { projectService.open(project) }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals("${project.name} – Thoreau", stage.title)
        val names =
            robot
                .lookup(".tree-cell")
                .queryAllAs(TreeCell::class.java)
                .sortedBy { it.index }
                .mapNotNull { it.text }
        assertEquals(listOf(project.name, ".hidden", "README.md"), names)
    }

    @Test
    fun `a git repository shows its status as markers in the explorer and follows staging`(robot: FxRobot) {
        assumeTrue(git.available, "git is not installed")
        runGit("init", "-q")
        project.resolve(".gitignore").writeText("*.log\nbuild/\n")
        project.resolve("tracked.md").writeText("one")
        runGit("add", ".")
        runGit("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-q", "-m", "Initial commit")
        project.resolve("tracked.md").writeText("two")
        project.resolve("new.md").createFile()
        project.resolve("debug.log").createFile()
        project
            .resolve("build")
            .createDirectories()
            .resolve("out.txt")
            .createFile()

        robot.interact { projectService.open(project) }

        eventually(robot) {
            // Files and ignored folders: coloured names; other folders: bubbles.
            assertEquals(listOf("file-name-modified"), markersOf(robot, "tracked.md"))
            assertEquals(listOf("file-name-unversioned"), markersOf(robot, "new.md"))
            assertEquals(listOf("file-name-ignored"), markersOf(robot, "debug.log"))
            assertEquals(listOf("file-name-ignored"), markersOf(robot, "build"))
            assertEquals(listOf("file-marker-unversioned", "file-marker-modified"), markersOf(robot, project.name))
            assertEquals(emptyList(), markersOf(robot, ".gitignore"))
        }

        runGit("add", "new.md")
        eventually(robot) { assertEquals(listOf("file-name-added"), markersOf(robot, "new.md")) }

        robot.interact { mainWindow.dispose() }
        assertFalse(gitStatus.active)
    }

    @Test
    fun `modified files open in the editor with their changes against HEAD marked`(robot: FxRobot) {
        assumeTrue(git.available, "git is not installed")
        runGit("init", "-q")
        project.resolve("tracked.md").writeText("one\ntwo\n")
        runGit("add", ".")
        runGit("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-q", "-m", "Initial commit")
        project.resolve("tracked.md").writeText("one\nTWO\nthree\n")
        project.resolve("new.md").writeText("new\n")

        robot.interact { projectService.open(project) }
        eventually(robot) {
            assertEquals(GitStatus.MODIFIED, gitStatus.snapshot.get().fileStatus(project.resolve("tracked.md")))
        }

        fun showsDiff() = robot.lookup(".editor-content .diff-view").tryQuery<Node>().isPresent
        robot.interact { editorArea.open(project.resolve("new.md")) }
        assertEquals(emptyMap(), shownMarkers(robot), "untracked files are new as a whole")
        robot.interact { editorArea.open(project.resolve("tracked.md")) }
        assertFalse(showsDiff())
        assertEquals(mapOf(2 to "diff-modified", 3 to "diff-modified"), shownMarkers(robot))

        runGit("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-q", "-am", "Change")
        eventually(robot) { assertEquals(emptyMap(), shownMarkers(robot), "clean files have no markers") }
        assertEquals(project.resolve("tracked.md"), editorArea.selectedPath)

        // Changed again on disk: the editor asks for the diff as soon as the status changes.
        project.resolve("tracked.md").writeText("one\nTWO\nfour\n")
        eventually(robot) { assertEquals(mapOf(3 to "diff-modified"), shownMarkers(robot)) }
        assertFalse(showsDiff())
    }

    @Test
    fun `View - Show Changes with Shortcut+Shift+D switches between the editor and the diff against HEAD`(
        robot: FxRobot,
    ) {
        assumeTrue(git.available, "git is not installed")
        runGit("init", "-q")
        project.resolve("tracked.md").writeText("one\ntwo\n")
        runGit("add", ".")
        runGit("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-q", "-m", "Initial commit")
        project.resolve("tracked.md").writeText("one\nTWO\n")
        project.resolve("new.md").writeText("new\n")
        robot.interact { projectService.open(project) }
        eventually(robot) {
            assertEquals(GitStatus.MODIFIED, gitStatus.snapshot.get().fileStatus(project.resolve("tracked.md")))
        }
        val showChanges = menu(robot, "View").items.single { it.text == "Show Changes" }
        val accelerator = showChanges.accelerator as KeyCodeCombination
        assertEquals(KeyCode.D, accelerator.code)
        assertEquals(KeyCombination.ModifierValue.DOWN, accelerator.shortcut)
        assertEquals(KeyCombination.ModifierValue.DOWN, accelerator.shift)

        fun diffTitles() = robot.lookup(".editor-content .diff-title").queryAllAs(Label::class.java).map { it.text }
        robot.interact { editorArea.open(project.resolve("new.md")) }
        assertTrue(showChanges.isDisable, "untracked files have no diff")
        robot.interact { editorArea.open(project.resolve("tracked.md")) }
        assertFalse(showChanges.isDisable)

        val mac = System.getProperty("os.name").startsWith("Mac")
        robot.interact {
            Event.fireEvent(
                shownArea(robot),
                KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.D, true, !mac, false, mac),
            )
        }
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(setOf("HEAD", "Working Tree"), diffTitles().toSet())

        robot.interact { showChanges.fire() }
        assertEquals(emptyList(), diffTitles())

        // Other tabs, e.g. terminals, have no changes to show; switching back enables it again.
        robot.interact {
            editorArea.open(
                object : EditorTab {
                    override val title = SimpleStringProperty("Terminal 1")
                    override val tooltip = "Terminal 1"
                    override val content = Label()
                },
            )
        }
        assertTrue(showChanges.isDisable)
        robot.interact { editorArea.open(project.resolve("new.md")) }
        assertTrue(showChanges.isDisable)
        robot.interact { editorArea.open(project.resolve("tracked.md")) }
        assertFalse(showChanges.isDisable)
    }

    /** The change markers in the gutter of the shown editor by line number; lines without a marker are left out. */
    private fun shownMarkers(robot: FxRobot): Map<Int, String> {
        WaitForAsyncUtils.waitForFxEvents()
        return robot
            .lookup(".editor-content .code-editor .gutter")
            .queryAllAs(HBox::class.java)
            .mapNotNull { row ->
                val line = (row.children[0] as Label).text.trim().toInt()
                row.children[1]
                    .styleClass
                    .singleOrNull { it != "change-marker" }
                    ?.let { line to it }
            }.toMap()
    }

    private fun runGit(vararg arguments: String) {
        val process =
            ProcessBuilder(listOf("git") + arguments)
                .directory(project.toFile())
                .redirectErrorStream(true)
                .apply {
                    environment().clear()
                    environment().putAll(gitEnvironment)
                }.start()
        val output = process.inputStream.readAllBytes().decodeToString()
        assertEquals(0, process.waitFor(), "git ${arguments.joinToString(" ")}: $output")
    }

    /** The style class colouring [name] in the explorer, if any, then those of the bubbles after it, in order. */
    private fun markersOf(
        robot: FxRobot,
        name: String,
    ): List<String> {
        val graphic =
            robot
                .lookup(".tree-cell")
                .queryAllAs(TreeCell::class.java)
                .first { it.text == name }
                .graphic
        val nameColour = graphic.lookup(".label").styleClass.filter { it.startsWith("file-name-") }
        val bubbles =
            graphic
                .lookupAll(".file-marker")
                .filter { it.parent.isVisible }
                .sortedBy { it.localToScene(0.0, 0.0).x }
                .map { node -> node.styleClass.single { it != "file-marker" } }
        return nameColour + bubbles
    }

    /** Repeats [assertion] on the FX thread until it passes or 20 s are over. */
    private fun eventually(
        robot: FxRobot,
        assertion: () -> Unit,
    ) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (true) {
            WaitForAsyncUtils.waitForFxEvents()
            var failure: Throwable? = null
            robot.interact { failure = runCatching(assertion).exceptionOrNull() }
            val error = failure ?: return
            if (System.nanoTime() > deadline) throw error
            Thread.sleep(50)
        }
    }

    @Test
    fun `opening a file in the explorer shows it in an editable tab`(robot: FxRobot) {
        val readme = project.resolve("README.md").apply { writeText("# Hello") }
        robot.interact { projectService.open(project) }

        robot.interact { fileExplorer.onOpenFile(readme) }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(listOf("README.md"), tabTitles(robot))
        val text = robot.lookup(".editor-content .code-area").queryAs(CodeArea::class.java)
        assertEquals("# Hello", text.text)
        assertEquals(true, text.isEditable)
    }

    @Test
    fun `the tab of a file renamed in the explorer follows it`(robot: FxRobot) {
        val notes = project.resolve("notes.txt").apply { writeText("notes") }
        robot.interact { projectService.open(project) }
        robot.interact { fileExplorer.onOpenFile(notes) }
        val renamed = project.resolve("todo.txt")

        robot.interact {
            notes.moveTo(renamed)
            fileExplorer.onMoved(notes, renamed)
        }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(listOf("todo.txt"), tabTitles(robot))
        assertEquals(listOf(renamed), editorArea.openPaths)
    }

    @Test
    fun `deleting in the explorer counts the unsaved files and closes the tabs below`(robot: FxRobot) {
        val docs = project.resolve("docs").createDirectories()
        val notes = docs.resolve("notes.txt").apply { writeText("notes") }
        val todo = docs.resolve("todo.txt").apply { writeText("todo") }
        val readme = project.resolve("README.md").apply { writeText("# Hello") }
        robot.interact { projectService.open(project) }
        robot.interact { fileExplorer.onOpenFile(notes) }
        WaitForAsyncUtils.waitForFxEvents()
        robot.interact { shownArea(robot).appendText("!") }
        robot.interact {
            fileExplorer.onOpenFile(todo)
            fileExplorer.onOpenFile(readme)
        }

        val unsaved = fileExplorer.unsavedUnder(docs)
        assertEquals(listOf(notes), unsaved)

        docs.toFile().deleteRecursively()
        robot.interact { fileExplorer.onDeleted(docs, unsaved) }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(listOf("README.md"), tabTitles(robot))
    }

    private fun fileMenuItem(
        robot: FxRobot,
        text: String,
    ): MenuItem = menu(robot, "File").items.single { it.text == text }

    /** The code area of the selected file tab. */
    private fun shownArea(robot: FxRobot): CodeArea =
        robot.lookup(".editor-content .code-area").queryAs(CodeArea::class.java)

    /**
     * Presses Shortcut+S, with Alt if [alt], on [target] as the keyboard would: unless [target] consumes the key, it
     * reaches the scene's accelerators.
     */
    private fun pressShortcutS(
        target: Node,
        alt: Boolean = false,
    ) {
        val mac = System.getProperty("os.name").startsWith("Mac")
        Event.fireEvent(target, KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.S, false, !mac, alt, mac))
    }

    @Test
    fun `offers File - Save with Ctrl+S and Save All with Ctrl+Alt+S`(robot: FxRobot) {
        val save = fileMenuItem(robot, "Save").accelerator as KeyCodeCombination
        assertEquals(KeyCode.S, save.code)
        assertEquals(KeyCombination.ModifierValue.DOWN, save.shortcut)
        assertEquals(KeyCombination.ModifierValue.UP, save.alt)

        val saveAll = fileMenuItem(robot, "Save All").accelerator as KeyCodeCombination
        assertEquals(KeyCode.S, saveAll.code)
        assertEquals(KeyCombination.ModifierValue.DOWN, saveAll.shortcut)
        assertEquals(KeyCombination.ModifierValue.DOWN, saveAll.alt)
    }

    @Test
    fun `Save saves the selected file, Save All every edited one`(robot: FxRobot) {
        val a = project.resolve("a.txt").apply { writeText("a") }
        val b = project.resolve("b.txt").apply { writeText("b") }
        robot.interact {
            projectService.open(project)
            editorArea.open(a)
            shownArea(robot).appendText("1")
            editorArea.open(b)
            shownArea(robot).appendText("2")
        }
        assertEquals(listOf("● a.txt", "● b.txt"), tabTitles(robot))

        robot.interact { fileMenuItem(robot, "Save").fire() }
        assertEquals("b2", b.readText())
        assertEquals("a", a.readText())
        assertEquals(listOf("● a.txt", "b.txt"), tabTitles(robot))

        robot.interact { fileMenuItem(robot, "Save All").fire() }
        assertEquals("a1", a.readText())
        assertEquals(listOf("a.txt", "b.txt"), tabTitles(robot))
    }

    @Test
    fun `the save shortcut works in the editor, and in a terminal Ctrl+S stays the shell's`(robot: FxRobot) {
        val a = project.resolve("a.txt").apply { writeText("a") }
        robot.interact {
            projectService.open(project)
            editorArea.open(a)
            shownArea(robot).appendText("1")
        }
        robot.interact { pressShortcutS(shownArea(robot)) }
        assertEquals("a1", a.readText())

        var saves = 0
        var savesAll = 0
        fileMenuItem(robot, "Save").addEventHandler(ActionEvent.ACTION) { saves++ }
        fileMenuItem(robot, "Save All").addEventHandler(ActionEvent.ACTION) { savesAll++ }
        robot.interact {
            (checkNotNull(editorArea.selectedTab).content.lookup(".code-area") as CodeArea).appendText("2")
            editorArea.open(terminals.open(project, "Job", "sleep 300"))
        }
        val terminal = checkNotNull(editorArea.selectedTab).content
        robot.interact { pressShortcutS(terminal) }
        // Cmd+S on macOS is no key for the shell; Ctrl+S elsewhere is (it stops the output).
        if (!System.getProperty("os.name").startsWith("Mac")) assertEquals(0, saves)

        robot.interact { pressShortcutS(terminal, alt = true) }
        assertEquals(1, savesAll)
        assertEquals("a12", a.readText())
    }

    @Test
    fun `the first folder opens in the window, later ones in other windows, keeping the tabs`(robot: FxRobot) {
        val readme = project.resolve("README.md").apply { writeText("# Hello") }
        robot.interact {
            mainWindow.openFolder(project)
            fileExplorer.onOpenFile(readme)
        }
        assertEquals(project, mainWindow.folder)
        assertEquals(emptyList(), openedElsewhere)

        robot.interact { mainWindow.openFolder(otherProject) }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(listOf(otherProject), openedElsewhere)
        assertEquals(project, mainWindow.folder)
        assertEquals("${project.name} – Thoreau", stage.title)
        assertEquals(listOf("README.md"), tabTitles(robot))
    }

    @Test
    fun `offers File - Settings with Ctrl+Comma, which opens the settings dialog`(robot: FxRobot) {
        val settings = menu(robot, "File").items.last()
        assertEquals("Settings…", settings.text)
        val accelerator = settings.accelerator as KeyCodeCombination
        assertEquals(KeyCode.COMMA, accelerator.code)
        assertEquals(KeyCombination.ModifierValue.DOWN, accelerator.shortcut)

        var shownFor: Stage? = null
        mainWindow.showSettings = { shownFor = it }
        robot.interact { settings.fire() }
        assertEquals(stage, shownFor)
    }

    @Test
    fun `the settings dialog shows Display - Fonts and applying a font changes the open views`(robot: FxRobot) {
        val readme = project.resolve("README.md").apply { writeText("# Hello") }
        robot.interact {
            projectService.open(project)
            fileExplorer.onOpenFile(readme)
        }
        val page = FontsPage(fontSettings)
        lateinit var dialog: SettingsDialog
        robot.interact {
            dialog = SettingsDialog(SettingsPages(listOf(page)).pages, stage)
            dialog.show()
        }
        assertEquals(listOf("Display", "Fonts"), dialog.shownPage?.path)

        robot.interact {
            page.apply() // nothing edited: nothing changes
            fontSettings.apply(mapOf(FontArea.EDITOR to FontSpec("Serif", 22.0)))
            dialog.close()
        }
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(
            22.0,
            robot
                .lookup(".editor-content .code-area .text")
                .queryAllAs(Text::class.java)
                .first { it.text.contains("Hello") }
                .font.size,
        )
    }

    @Test
    fun `offers Terminal - New Terminal with Ctrl+Shift+T`(robot: FxRobot) {
        val newTerminal = menu(robot, "Terminal").items.single()

        assertEquals("New Terminal", newTerminal.text)
        val accelerator = newTerminal.accelerator as KeyCodeCombination
        assertEquals(KeyCode.T, accelerator.code)
        assertEquals(KeyCombination.ModifierValue.DOWN, accelerator.shortcut)
        assertEquals(KeyCombination.ModifierValue.DOWN, accelerator.shift)
    }

    @Test
    fun `a new terminal opens as a tab in the project folder, disposing the window ends it`(robot: FxRobot) {
        robot.interact { projectService.open(project) }

        robot.interact { menu(robot, "Terminal").items.single().fire() }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(listOf("Terminal 1"), tabTitles(robot))
        val tab = checkNotNull(editorArea.selectedTab)
        assertEquals("Terminal in $project", tab.tooltip)

        robot.interact { mainWindow.dispose() }
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(emptyList(), tabTitles(robot))
        assertEquals(0, terminals.openCount)
        assertEquals(0, fileExplorer.root?.children?.size ?: 0)
    }

    @Test
    fun `closing the window asks once while tabs run something, Cancel keeps it open`(robot: FxRobot) {
        robot.interact { projectService.open(project) }
        robot.interact { editorArea.open(terminals.open(project, "Job 1", "sleep 300")) }
        robot.interact { editorArea.open(terminals.open(project, "Job 2", "sleep 300")) }
        val asked = mutableListOf<List<String>>()
        var answer = false
        mainWindow.confirmCloseWindow = { _, warnings ->
            asked += warnings
            answer
        }

        robot.interact { requestClose() }
        assertEquals(listOf(listOf("Job 1 is still running: sleep 300", "Job 2 is still running: sleep 300")), asked)
        assertTrue(stage.isShowing)
        assertEquals(2, terminals.openCount)

        answer = true
        robot.interact { requestClose() }
        assertEquals(2, asked.size)
        assertFalse(stage.isShowing)
    }

    @Test
    fun `closing the window asks to save edited files first, Cancel keeps it open`(robot: FxRobot) {
        val a = project.resolve("a.txt").apply { writeText("a") }
        robot.interact {
            projectService.open(project)
            editorArea.open(a)
            shownArea(robot).appendText("1")
            editorArea.open(terminals.open(project, "Job 1", "sleep 300"))
        }
        val asked = mutableListOf<String>()
        var answer = SaveChoice.CANCEL
        editorArea.confirmSave = { files ->
            asked += "save $files"
            answer
        }
        mainWindow.confirmCloseWindow = { _, _ ->
            asked += "close"
            true
        }

        robot.interact { requestClose() }
        assertEquals(listOf("save [a.txt]"), asked)
        assertTrue(stage.isShowing)

        answer = SaveChoice.SAVE
        robot.interact { requestClose() }
        assertEquals(listOf("save [a.txt]", "save [a.txt]", "close"), asked)
        assertEquals("a1", a.readText())
        assertFalse(stage.isShowing)
    }

    @Test
    fun `closing the window with Don't Save leaves the files as they are`(robot: FxRobot) {
        val a = project.resolve("a.txt").apply { writeText("a") }
        robot.interact {
            projectService.open(project)
            editorArea.open(a)
            shownArea(robot).appendText("1")
        }
        editorArea.confirmSave = { SaveChoice.DISCARD }
        mainWindow.confirmCloseWindow = { _, _ -> error("nothing runs") }

        robot.interact { requestClose() }

        assertFalse(stage.isShowing)
        assertEquals("a", a.readText())
    }

    @Test
    fun `closing the window does not ask when nothing runs`(robot: FxRobot) {
        robot.interact { projectService.open(project) }
        mainWindow.confirmCloseWindow = { _, _ -> error("must not ask") }

        robot.interact { requestClose() }

        assertFalse(stage.isShowing)
    }

    /** Asks to close the window like its close button does; closes it unless a handler consumes the request. */
    private fun requestClose() {
        val event = WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST)
        Event.fireEvent(stage, event)
        if (!event.isConsumed) stage.hide()
    }

    @Test
    fun `offers Docker Sandbox - Claude Code and Environment, disabled without a folder`(robot: FxRobot) {
        val items = sandboxItems(robot)

        assertEquals(listOf("Claude Code", "Environment"), items.map { it.text })
        assertEquals(listOf(true, true), items.map { it.isDisable })
    }

    @Test
    fun `the sandbox actions are disabled for the home folder`(robot: FxRobot) {
        home.resolve("sbxenv.yaml").createFile()
        robot.interact { projectService.open(home) }
        assertEquals(listOf(true, true), sandboxItems(robot).map { it.isDisable })
    }

    @Test
    fun `the sandbox actions are disabled for folders not below the home folder`(robot: FxRobot) {
        project.resolve("sbxenv.yaml").createFile()
        robot.interact { projectService.open(project) }
        assertEquals(listOf(true, true), sandboxItems(robot).map { it.isDisable })
    }

    @Test
    fun `Environment is enabled below the home folder once the folder has an sbxenv yaml`(robot: FxRobot) {
        val folder = home.resolve("work/project").createDirectories()
        robot.interact { projectService.open(folder) }
        assertEquals(listOf(false, true), sandboxItems(robot).map { it.isDisable })

        folder.resolve("sbxenv.yaml").createFile()
        robot.interact { menu(robot, "Docker Sandbox").onShowing.handle(null) }

        assertEquals(listOf(false, false), sandboxItems(robot).map { it.isDisable })
    }

    @Test
    fun `starting a sandbox where it may not run shows an error and opens no tab`(robot: FxRobot) {
        val errors = mutableListOf<String>()
        mainWindow.showError = { _, message -> errors += message }
        robot.interact { projectService.open(home) }

        // Fired directly: the menu would not offer it, the start is checked again.
        robot.interact { sandboxItems(robot).first().fire() }

        assertEquals(1, errors.size)
        assertTrue(errors.single().startsWith("Claude Code cannot be started. Docker sandboxes run only"), errors[0])
        assertEquals(emptyList(), tabTitles(robot))
        assertEquals(0, terminals.openCount)
    }

    @Test
    fun `a terminal or sandbox whose shell cannot be started shows an error and opens no tab`(robot: FxRobot) {
        val errors = mutableListOf<String>()
        mainWindow.showError = { _, message -> errors += message }
        val folder = home.resolve("work/project").createDirectories()
        robot.interact { projectService.open(folder) }
        val shell = home.resolve("no-shell")
        shellEnvironment["SHELL"] = shell.toString()

        robot.interact {
            menu(robot, "Terminal").items.single().fire()
            sandboxItems(robot).first().fire()
        }

        assertEquals(2, errors.size, errors.toString())
        assertTrue(errors[0].startsWith("The terminal cannot be started. $shell could not be started in $folder ("))
        assertTrue(errors[1].startsWith("Claude Code cannot be started. $shell could not be started in $folder ("))
        assertEquals(emptyList(), tabTitles(robot))
        assertEquals(0, terminals.openCount)

        // The failed starts used up no numbers. A stub sbx, first on the PATH of the login shell through the .profile of
        // the test home folder, so a real sbx on this machine is never started.
        val calls = home.resolve("sbx-calls.txt")
        val bin = home.resolve("bin").createDirectories()
        bin.resolve("sbx").apply {
            writeText("#!/bin/sh\necho \"$*\" >> '$calls'\n")
            setPosixFilePermissions(PosixFilePermissions.fromString("rwxr-xr-x"))
        }
        home.resolve(".profile").writeText("PATH=\"$bin:\$PATH\"\nexport PATH\n")
        shellEnvironment += mapOf("SHELL" to "/bin/sh", "HOME" to home.toString())
        robot.interact {
            menu(robot, "Terminal").items.single().fire()
            sandboxItems(robot).first().fire()
        }
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(listOf("Terminal 1", "Claude Code 1"), tabTitles(robot).map { it.removeSuffix(" (exited)") })
        eventually(robot) { assertEquals(listOf("run claude ."), calls.takeIf { it.exists() }?.readLines()) }
    }

    @Test
    fun `the Docker Sandbox menu is disabled without sbx`(robot: FxRobot) {
        assertFalse(menu(robot, "Docker Sandbox").isDisable)

        val menu = otherWindowSandboxMenu(robot, CompletableFuture.completedFuture(false))
        WaitForAsyncUtils.waitForFxEvents()

        assertTrue(menu.isDisable)
    }

    @Test
    fun `the Docker Sandbox menu is disabled until sbx has been found`(robot: FxRobot) {
        val sbxInstalled = CompletableFuture<Boolean>()
        val menu = otherWindowSandboxMenu(robot, sbxInstalled)
        WaitForAsyncUtils.waitForFxEvents()
        assertTrue(menu.isDisable)

        sbxInstalled.complete(true)
        WaitForAsyncUtils.waitForFxEvents()

        assertFalse(menu.isDisable)
        assertEquals(listOf(false, true), menu.items.map { it.isDisable })
    }

    private fun sandboxItems(robot: FxRobot) = menu(robot, "Docker Sandbox").items.toList()

    private fun menu(
        robot: FxRobot,
        text: String,
    ): Menu =
        robot
            .lookup(".menu-bar")
            .queryAs(MenuBar::class.java)
            .menus
            .single { it.text == text }

    private fun tabTitles(robot: FxRobot): List<String> =
        robot.lookup(".editor-tab .label").queryAllAs(Label::class.java).map { it.text }

    @Test
    fun `web pages asked for by file views open through the window`() {
        val opened = mutableListOf<URI>()
        mainWindow.openWebPage = { opened += it }

        editorArea.openWebPage(URI("https://example.com/docs"))

        assertEquals(listOf(URI("https://example.com/docs")), opened)
    }

    @Test
    fun `file views get the window's project folder`(robot: FxRobot) {
        assertNull(editorArea.projectFolder())

        robot.interact { projectService.open(project) }

        assertEquals(project, editorArea.projectFolder())
    }
}
