package dev.sluide.ide

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.commandruns.CommandRunSession
import dev.sluide.dockersandbox.DockerSandbox
import dev.sluide.editorarea.EditorArea
import dev.sluide.fileexplorer.FileExplorerView
import dev.sluide.fileviewers.DefaultTextFileViewer
import dev.sluide.fileviewers.ExtensionFileViewer
import dev.sluide.fileviewers.FileView
import dev.sluide.fileviewers.FileViewers
import dev.sluide.filewatcher.FileWatcher
import dev.sluide.git.GitBranches
import dev.sluide.git.GitCommits
import dev.sluide.git.GitLog
import dev.sluide.git.GitPush
import dev.sluide.git.GitRebases
import dev.sluide.git.GitRemotes
import dev.sluide.git.GitStatusService
import dev.sluide.git.GitTrustCheck
import dev.sluide.git.RemoteSourceCache
import dev.sluide.git.cli.GitCli
import dev.sluide.gitlog.GitLogView
import dev.sluide.gradle.GradleSession
import dev.sluide.httpclient.HttpClientSession
import dev.sluide.projects.ProjectPicker
import dev.sluide.projects.RecentProjects
import dev.sluide.projecttrust.ProjectTrust
import dev.sluide.projecttrust.TrustedProjects
import dev.sluide.scratches.ScratchFiles
import dev.sluide.settings.FontSettings
import dev.sluide.settings.SettingsPages
import dev.sluide.settings.SettingsStore
import dev.sluide.settings.ThemeSettings
import dev.sluide.settings.ThemedWindows
import dev.sluide.settings.pages.FontsPage
import dev.sluide.terminal.Terminals
import dev.sluide.terminal.settings.TerminalScrollSettings
import javafx.application.Platform
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.control.Menu
import javafx.scene.control.MenuBar
import javafx.scene.control.MenuItem
import javafx.scene.control.SplitPane
import javafx.scene.control.TableCell
import javafx.scene.control.TreeCell
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.HBox
import javafx.stage.Stage
import javafx.stage.WindowEvent
import org.fxmisc.richtext.CodeArea
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.testfx.api.FxRobot
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.io.path.createDirectories
import kotlin.io.path.name
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

/** The real main window, shared by the `MainWindow*UiTest` classes. Needs a display, see the `uiTest` Gradle task. */
abstract class MainWindowUiFixture {
    @TempDir
    lateinit var project: Path

    @TempDir
    lateinit var otherProject: Path

    @TempDir
    lateinit var settingsDir: Path

    /** The home folder of the Docker sandboxes; `project` and `otherProject` are not below it. */
    @TempDir
    lateinit var home: Path

    internal val projectService = ProjectService()
    internal lateinit var stage: Stage
    internal lateinit var fileExplorer: FileExplorerView
    internal lateinit var editorArea: EditorArea
    internal lateinit var mainWindow: MainWindow
    internal lateinit var fontSettings: FontSettings
    internal val watcher = FileWatcher(Platform::runLater, 50.milliseconds)
    internal lateinit var terminals: Terminals

    /** The environment of the terminals' and sandboxes' shells; tests may change it. */
    internal val shellEnvironment = System.getenv().toMutableMap()

    /** The folders the window asked to open in other windows. */
    internal val openedElsewhere = mutableListOf<Path>()

    /** The files that count as open in other windows ([ProjectWindows.isOpenElsewhere]). */
    internal val openInOtherWindows = mutableSetOf<Path>()

    /** The shared scratch files, in the test's home folder, never in `~/.sluide`; created when a folder opens. */
    internal val scratchFiles by lazy { ScratchFiles(home.resolve(".sluide/scratches")) }

    /** The cache of remote repositories, in the test's home folder, never in `~/.sluide`; nothing fetches in these tests. */
    internal val remoteSourceCache by lazy { RemoteSourceCache(git, home.resolve(".sluide/cache/git")) }

    /** The remembered projects, kept in the test's settings folder, never in `~/.sluide`. */
    internal val recentProjects by lazy { RecentProjects(settingsDir.resolve("projects.properties")) }
    internal val projectWindows by lazy {
        object : ProjectWindows(DefaultListableBeanFactory(), ProjectPicker(recentProjects)) {
            override fun open(folder: Path) {
                openedElsewhere.add(folder)
            }

            override fun isOpenElsewhere(
                path: Path,
                window: MainWindow,
            ) = path in openInOtherWindows
        }
    }

    // Without the user's global git configuration, which could ignore other files, and without inherited `GIT_*`
    // variables (a build run by `git rebase -x` inherits `GIT_DIR`, which would point at the slu-ide repository).
    internal val gitEnvironment =
        System.getenv().filterKeys { !it.startsWith("GIT_") } +
            mapOf("GIT_CONFIG_GLOBAL" to "/dev/null", "GIT_CONFIG_NOSYSTEM" to "1")
    internal val git = GitCli("git", gitEnvironment)

    /** Git's trust check, a singleton bean in the application: the trust runs it, the status reads its inputs. */
    private val gitTrustCheck by lazy { GitTrustCheck(git) }

    /** The folder's trust, running git's check; decisions are kept in the test's settings folder. */
    internal val projectTrust by lazy {
        ProjectTrust(
            listOf(gitTrustCheck),
            TrustedProjects(settingsDir.resolve("project-trust.properties")),
            BackgroundTasks.forTests(),
        )
    }
    internal val gitStatus by lazy {
        GitStatusService(git, watcher, projectTrust, BackgroundTasks.forTests(), gitTrustCheck)
    }

    @AfterEach
    fun close() {
        Platform.runLater { otherStages.forEach { it.hide() } }
        WaitForAsyncUtils.waitForFxEvents()
        terminals.close()
        gitStatus.close()
        projectTrust.close()
        gitLogViews.forEach(GitLogView::close)
        watcher.close()
        httpSessions.forEach(HttpClientSession::close)
        gradleSessions.forEach(GradleSession::close)
        commandRunSessions.forEach(CommandRunSession::close)
        backgroundTasks.forEach(BackgroundTasks::close)
    }

    /** The git log views of the windows a test created, the main window's first; closed after it. */
    internal val gitLogViews = mutableListOf<GitLogView>()
    internal val gitLogView get() = gitLogViews.first()

    /** The HTTP client sessions of the windows a test created; closed after it. */
    internal val httpSessions = mutableListOf<HttpClientSession>()

    /** The Gradle sessions of the windows a test created, the main window's first; closed after it. */
    internal val gradleSessions = mutableListOf<GradleSession>()

    /** The command run sessions of the windows a test created; closed after it. */
    internal val commandRunSessions = mutableListOf<CommandRunSession>()

    /** The background tasks of the windows a test created, the main window's first; closed after it. */
    internal val backgroundTasks = mutableListOf<BackgroundTasks>()

    internal fun startView(stage: Stage) {
        this.stage = stage
        fontSettings =
            FontSettings(SettingsStore(settingsDir.resolve("settings.properties")), FontSettings.systemDefaults())
        terminals =
            Terminals(
                fontSettings,
                TerminalScrollSettings(SettingsStore(settingsDir.resolve("settings.properties"))),
                shellEnvironment,
            )
        fileExplorer = FileExplorerView(watcher, fontSettings)
        editorArea = editorArea()
        mainWindow = mainWindow(projectService, fileExplorer, editorArea, CompletableFuture.completedFuture(true))
        mainWindow.show(stage)
    }

    internal val fileViewers = DefaultTextFileViewer().let { FileViewers(listOf(it, BinaryFileViewer()), it) }

    internal fun editorArea(): EditorArea = EditorArea(fileViewers, watcher, fontSettings)

    /** A main window whose sandboxes count `sbx` as installed once [sbxInstalled] completes with `true`. */
    internal fun mainWindow(
        projectService: ProjectService,
        fileExplorer: FileExplorerView,
        editorArea: EditorArea,
        sbxInstalled: CompletableFuture<Boolean>,
    ): MainWindow {
        val tasks = BackgroundTasks().also(backgroundTasks::add)
        val commandRuns = CommandRunSession(terminals, tasks).also(commandRunSessions::add)
        return MainWindow(
            projectService,
            fileExplorer,
            editorArea,
            terminals,
            DockerSandbox(terminals, home, shellEnvironment, sbxInstalled),
            gitStatus,
            GitLogView(
                GitLog(git),
                GitBranches(git),
                GitCommits(git),
                GitRebases(git),
                GitRemotes(git),
                GitPush(git),
                tasks,
            ).also(gitLogViews::add),
            fileViewers,
            SettingsPages(listOf(FontsPage(fontSettings))),
            ThemedWindows(ThemeSettings(SettingsStore(settingsDir.resolve("settings.properties")))),
            projectWindows,
            HttpClientSession(tasks).also(httpSessions::add),
            recentProjects,
            projectTrust,
            tasks,
            scratchFiles,
            SystemBrowser(),
            remoteSourceCache,
            GitRemotes(git),
            GradleSession(projectTrust, commandRuns).also(gradleSessions::add),
            commandRuns,
        )
    }

    /** Windows shown by a test besides the main one; closed after it. */
    internal val otherStages = mutableListOf<Stage>()

    /** The Docker Sandbox menu of another window, with a folder below the home folder open, told by [sbxInstalled]. */
    internal fun otherWindowSandboxMenu(
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

    internal fun splitPaneOf(node: Node): SplitPane =
        generateSequence(node.parent) { it.parent }.filterIsInstance<SplitPane>().first()

    /** The change markers in the gutter of the shown editor by line number; lines without a marker are left out. */
    internal fun shownMarkers(robot: FxRobot): Map<Int, String> {
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

    internal fun runGit(vararg arguments: String): String {
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
        return output.trim()
    }

    /** The style class colouring [name] in the explorer, if any, then those of the bubbles after it, in order. */
    internal fun markersOf(
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

    /** The subjects in the git log's Subject cells that show a commit, top to bottom. */
    internal fun shownSubjects(robot: FxRobot): List<String> =
        robot
            .lookup(".git-graph-cell")
            .queryAllAs(TableCell::class.java)
            .filter { !it.isEmpty && it.tableRow?.isVisible == true }
            .sortedBy { it.index }
            .map { it.text }

    /** Repeats [assertion] on the FX thread until it passes or 20 s are over. */
    internal fun eventually(
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

    internal fun fileMenuItem(
        robot: FxRobot,
        text: String,
    ): MenuItem = menu(robot, "File").items.single { it.text == text }

    /** The code area of the selected file tab. */
    internal fun shownArea(robot: FxRobot): CodeArea =
        robot.lookup(".editor-content .code-area").queryAs(CodeArea::class.java)

    /**
     * Presses Shortcut+S, with Alt if [alt], on [target] as the keyboard would: unless [target] consumes the key, it
     * reaches the scene's accelerators.
     */
    internal fun pressShortcutS(
        target: Node,
        alt: Boolean = false,
    ) = pressShortcut(target, KeyCode.S, alt)

    /** Presses Shortcut+[code], with Alt if [alt], on [target] as [pressShortcutS] does. */
    internal fun pressShortcut(
        target: Node,
        code: KeyCode,
        alt: Boolean = false,
    ) {
        val mac = System.getProperty("os.name").startsWith("Mac")
        Event.fireEvent(target, KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, !mac, alt, mac))
    }

    /** Asks to close the window like its close button does; closes it unless a handler consumes the request. */
    internal fun requestClose() {
        val event = WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST)
        Event.fireEvent(stage, event)
        if (!event.isConsumed) stage.hide()
    }

    internal fun sandboxItems(robot: FxRobot) = menu(robot, "Docker Sandbox").items.toList()

    internal fun menu(
        robot: FxRobot,
        text: String,
    ): Menu =
        robot
            .lookup(".menu-bar")
            .queryAs(MenuBar::class.java)
            .menus
            .single { it.text == text }

    internal fun tabTitles(robot: FxRobot): List<String> =
        robot.lookup(".editor-tab .label").queryAllAs(Label::class.java).map { it.text }
}

// How far the explorer's width may be off its start width: the divider position is a fraction, rounded to pixels.
internal const val DIVIDER_SLACK = 2.0

/** Shows `.bin` files, which no diff shows as text. */
private class BinaryFileViewer : ExtensionFileViewer {
    override val fileExtensions = setOf("bin")

    override fun canOpen(file: Path) = file.toString().endsWith(".bin")

    override fun open(file: Path): FileView = error("not opened in tests")
}
