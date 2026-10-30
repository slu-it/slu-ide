package dev.sluide.ide

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.codecs.Codecs
import dev.sluide.coloring.Coloring
import dev.sluide.coloring.SavedColors
import dev.sluide.commandruns.ArgumentHistories
import dev.sluide.commandruns.CommandRunSession
import dev.sluide.dockersandbox.DockerSandbox
import dev.sluide.editorarea.EditorArea
import dev.sluide.fileexplorer.ExplorerClipboard
import dev.sluide.fileexplorer.ExplorerSettings
import dev.sluide.fileexplorer.FileExplorerView
import dev.sluide.fileviewers.DefaultTextFileViewer
import dev.sluide.fileviewers.FileViewers
import dev.sluide.filewatcher.FileWatcher
import dev.sluide.git.GitBranches
import dev.sluide.git.GitCommits
import dev.sluide.git.GitFileComparison
import dev.sluide.git.GitLog
import dev.sluide.git.GitMerges
import dev.sluide.git.GitPulls
import dev.sluide.git.GitPush
import dev.sluide.git.GitRebases
import dev.sluide.git.GitRemotes
import dev.sluide.git.GitRewords
import dev.sluide.git.GitStashes
import dev.sluide.git.GitStatusService
import dev.sluide.git.GitTrustCheck
import dev.sluide.git.RemoteSourceCache
import dev.sluide.git.cli.GitCli
import dev.sluide.gitlog.GitLogView
import dev.sluide.gradle.GradleSession
import dev.sluide.httpclient.HttpClientSession
import dev.sluide.projectdata.ProjectDataStore
import dev.sluide.projects.ProjectPicker
import dev.sluide.projects.RecentProjects
import dev.sluide.projecttrust.ProjectTrust
import dev.sluide.projecttrust.TrustedProjects
import dev.sluide.scratches.SCRATCH_TYPES
import dev.sluide.scratches.ScratchFiles
import dev.sluide.settings.FontSettings
import dev.sluide.settings.SettingsPages
import dev.sluide.settings.SettingsStore
import dev.sluide.settings.ThemeSettings
import dev.sluide.settings.ThemedWindows
import dev.sluide.settings.pages.FontsPage
import dev.sluide.terminal.Terminals
import dev.sluide.terminal.settings.TerminalScrollSettings
import dev.sluide.todos.TodoPanel
import dev.sluide.todos.Todos
import javafx.application.Platform
import javafx.event.Event
import javafx.scene.control.Label
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.stage.Stage
import javafx.stage.Window
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.function.Supplier
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** Opens real windows from a Spring context with the window scope. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class ProjectWindowsUiTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var context: AnnotationConfigApplicationContext
    private lateinit var windows: ProjectWindows
    private lateinit var first: Stage
    private lateinit var recentProjects: RecentProjects

    /** How often the application was asked to end. */
    private var exits = 0

    /** The error messages shown. */
    private val errors = mutableListOf<String>()

    @Start
    fun start(stage: Stage) {
        first = stage
        val store = SettingsStore(dir.resolve("settings/settings.properties"))
        // Never the user's ~/.sluide.
        val projectData =
            ProjectDataStore(
                dir.resolve("settings/projects"),
                dir.resolve("settings/shared.json"),
                emptyList(),
            )
        recentProjects = RecentProjects(projectData)
        context =
            AnnotationConfigApplicationContext().apply {
                register(WindowScopeConfiguration::class.java, ProjectWindows::class.java, SystemBrowser::class.java)
                registerBean(SettingsStore::class.java, Supplier { store })
                registerBean(ProjectDataStore::class.java, Supplier { projectData })
                registerBean(RecentProjects::class.java, Supplier { recentProjects })
                registerBean(ScratchFiles::class.java, Supplier { ScratchFiles(dir.resolve("scratches")) })
                registerBean(ProjectPicker::class.java, Supplier { ProjectPicker(recentProjects) })
                registerBean(FontSettings::class.java, Supplier { FontSettings(store) })
                registerBean(TerminalScrollSettings::class.java, Supplier { TerminalScrollSettings(store) })
                registerBean(ThemedWindows::class.java, Supplier { ThemedWindows(ThemeSettings(store)) })
                registerBean(
                    SettingsPages::class.java,
                    Supplier { SettingsPages(listOf(FontsPage(getBean(FontSettings::class.java)))) },
                )
                registerBean(FileWatcher::class.java, Supplier { FileWatcher(Platform::runLater, 50.milliseconds) })
                register(ExplorerClipboard::class.java)
                register(ExplorerSettings::class.java)
                registerBean(GitCli::class.java, Supplier { GitCli(dir.resolve("no-git").toString(), System.getenv()) })
                registerBean(
                    TrustedProjects::class.java,
                    Supplier { TrustedProjects(projectData) },
                )
                register(GitTrustCheck::class.java)
                register(ArgumentHistories::class.java)
                register(Todos::class.java)
                registerBean(SavedColors::class.java, Supplier { SavedColors(projectData) })
                register(GitLog::class.java)
                register(GitFileComparison::class.java)
                register(GitBranches::class.java)
                register(GitCommits::class.java)
                register(GitRebases::class.java)
                register(GitMerges::class.java)
                register(GitPulls::class.java)
                register(GitRewords::class.java)
                register(GitRemotes::class.java)
                register(GitPush::class.java)
                register(GitStashes::class.java)
                registerBean(
                    RemoteSourceCache::class.java,
                    Supplier { RemoteSourceCache(getBean(GitCli::class.java), dir.resolve("cache/git")) },
                )
                val fallback = DefaultTextFileViewer()
                registerBean(FileViewers::class.java, Supplier { FileViewers(listOf(fallback), fallback) })
                // The window-scoped beans, as in the application.
                register(
                    MainWindow::class.java,
                    ProjectService::class.java,
                    FileExplorerView::class.java,
                    EditorArea::class.java,
                    Terminals::class.java,
                    DockerSandbox::class.java,
                    GitStatusService::class.java,
                    GitLogView::class.java,
                    HttpClientSession::class.java,
                    GradleSession::class.java,
                    CommandRunSession::class.java,
                    ProjectTrust::class.java,
                    BackgroundTasks::class.java,
                    TodoPanel::class.java,
                    Coloring::class.java,
                    Codecs::class.java,
                )
                refresh()
            }
        windows = context.getBean(ProjectWindows::class.java)
        windows.exit = { exits++ }
        windows.showError = { errors += it }
        windows.show(stage)
    }

    @AfterEach
    fun close() {
        // Windows and pickers a test opened would stay on screen for the next ones; the first window is the primary
        // stage, which the next test shows again. Hiding a picker calls the stubbed exit at most.
        Platform.runLater {
            windows.stages.filter { it !== first }.forEach(Stage::hide)
            pickersOnFx().forEach(Stage::hide)
            context.close()
        }
        WaitForAsyncUtils.waitForFxEvents()
    }

    private fun pickersOnFx() =
        Window
            .getWindows()
            .filterIsInstance<Stage>()
            .filter {
                it.isShowing && it.scene
                    ?.root
                    ?.styleClass
                    ?.contains("project-picker") == true
            }

    /** The project pickers on screen. */
    private fun pickers(): List<Stage> = WaitForAsyncUtils.asyncFx(Callable { pickersOnFx() }).get()

    /** Clicks the picker's row of the project named [name]. */
    private fun choose(
        robot: FxRobot,
        picker: Stage,
        name: String,
    ) {
        robot.interact {
            val row =
                picker.scene.root
                    .lookupAll(".project-name")
                    .filterIsInstance<Label>()
                    .single { it.text == name }
            Event.fireEvent(
                row,
                MouseEvent(
                    MouseEvent.MOUSE_CLICKED,
                    1.0,
                    1.0,
                    1.0,
                    1.0,
                    MouseButton.PRIMARY,
                    1,
                    false,
                    false,
                    false,
                    false,
                    true,
                    false,
                    false,
                    true,
                    false,
                    false,
                    null,
                ),
            )
        }
        WaitForAsyncUtils.waitForFxEvents()
    }

    /** Presses Escape in [picker]. */
    private fun escape(
        robot: FxRobot,
        picker: Stage,
    ) {
        robot.interact {
            Event.fireEvent(
                picker.scene.focusOwner ?: picker.scene.root,
                KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false),
            )
        }
        WaitForAsyncUtils.waitForFxEvents()
    }

    private fun window(stage: Stage) = windows.windowOf(stage)

    @Test
    fun `the first folder opens in the first window, others in new windows with their own beans`(robot: FxRobot) {
        val a = dir.resolve("a").createDirectories()
        val b = dir.resolve("b").createDirectories()

        robot.interact {
            window(first).openFolder(a)
            window(first).tools.openTerminal(first)
        }
        robot.interact { window(first).openFolder(b) }

        assertEquals(2, windows.stages.size)
        assertEquals(
            1,
            first.scene.root
                .lookupAll(".editor-tab")
                .size,
            "the first window keeps its tabs",
        )
        assertEquals(setOf(a, b), recentProjects.list().map { it.folder }.toSet(), "opened folders are remembered")
        val second = windows.stages[1]
        assertSame(first, windows.stages[0])
        assertEquals(a, window(first).folder)
        assertEquals(b, window(second).folder)
        assertEquals("a – ${appTitle()}", first.title)
        assertEquals("b – ${appTitle()}", second.title)
        assertTrue(second.isShowing)
        assertNotSame(first.scene.root, second.scene.root)
        // The explorers are different nodes, each in its own window.
        val explorers = windows.stages.map { it.scene.lookup(".tree-view") }
        assertNotSame(explorers[0], explorers[1])
        // Cascaded, but not necessarily by the full offset: macOS keeps windows on screen, and on the 1024 px wide
        // CI display it moves the 1000 px wide window back.
        assertTrue(second.x > first.x, "second window at x=${second.x}, first at x=${first.x}")
    }

    @Test
    fun `opening a folder that is already open opens no second window for it`(robot: FxRobot) {
        val a = dir.resolve("a").createDirectories()
        val b = dir.resolve("b").createDirectories()
        robot.interact {
            window(first).openFolder(a)
            window(first).openFolder(b)
        }

        robot.interact { window(windows.stages[1]).openFolder(a.resolve(".")) }

        assertEquals(2, windows.stages.size)
        assertEquals(listOf(a.name, b.name), windows.stages.map { window(it).folder?.name })
    }

    @Test
    fun `closing a window ends its terminals and forgets it`(robot: FxRobot) {
        val a = dir.resolve("a").createDirectories()
        val b = dir.resolve("b").createDirectories()
        robot.interact {
            window(first).openFolder(a)
            window(first).openFolder(b)
        }
        val second = windows.stages[1]
        lateinit var terminals: Terminals
        robot.interact {
            window(second).tools.openTerminal(second)
            terminals = window(second).tools.terminals
        }
        assertEquals(1, terminals.openCount)

        robot.interact { second.hide() }

        assertEquals(0, terminals.openCount)
        assertEquals(listOf(first), windows.stages)
        assertEquals(a, window(first).folder)
    }

    @Test
    fun `an empty scratch file open in another window is deleted only when the last of them closes`(robot: FxRobot) {
        val a = dir.resolve("a").createDirectories()
        val b = dir.resolve("b").createDirectories()
        robot.interact {
            window(first).openFolder(a)
            window(first).openFolder(b)
        }
        val second = windows.stages[1]
        robot.interact {
            for (stage in listOf(first, second)) {
                window(stage).scratches.askType = { _, onPick -> onPick(SCRATCH_TYPES.first()) }
            }
            window(first).scratches.newScratchFile(first)
        }
        val scratch = dir.resolve("scratches/scratch.txt")
        assertTrue(Files.isRegularFile(scratch))
        assertTrue(windows.isOpenElsewhere(scratch, window(second)))
        assertFalse(windows.isOpenElsewhere(scratch, window(first)))
        // The same file under another name counts too.
        val link = b.resolve("link.txt").createSymbolicLinkPointingTo(scratch)
        assertTrue(windows.isOpenElsewhere(link, window(second)))

        robot.interact { second.hide() }
        assertTrue(Files.isRegularFile(scratch), "the first window still has it open")

        robot.interact { first.hide() }
        assertFalse(Files.exists(scratch))
        assertEquals(1, pickers().size)
    }

    @Test
    fun `choosing a remembered project in the picker opens a window for it`(robot: FxRobot) {
        val remembered = dir.resolve("remembered").createDirectories()
        recentProjects.opened(remembered)
        robot.interact { first.hide() }

        choose(robot, pickers().single(), "remembered")

        assertEquals(emptyList(), pickers())
        val stage = windows.stages.single()
        assertTrue(stage.isShowing)
        assertEquals(remembered, window(stage).folder)
        assertEquals(0, exits)
        robot.interact { stage.hide() }
    }

    /** Shows the picker for the remembered folder [name] after closing the only window, and chooses it. */
    private fun chooseAfterLastWindow(
        robot: FxRobot,
        name: String,
    ) {
        recentProjects.opened(dir.resolve(name).createDirectories())
        robot.interact { first.hide() }
        choose(robot, pickers().single(), name)
    }

    private fun showingStages(): List<Stage> =
        WaitForAsyncUtils
            .asyncFx(Callable { Window.getWindows().filterIsInstance<Stage>().filter { it.isShowing } })
            .get()

    @Test
    fun `a chosen folder that vanished before its window opens shows an error and the picker again`(robot: FxRobot) {
        windows.isFolder = { false }

        chooseAfterLastWindow(robot, "vanished")

        assertEquals(emptyList(), windows.stages)
        val picker = pickers().single()
        assertEquals(listOf(picker), showingStages(), "no window without a folder stays open")
        assertTrue(errors.single().contains("vanished"), errors.single())
        assertEquals(0, exits)
    }

    @Test
    fun `a window that fails to open is closed again, with an error and the picker again`(robot: FxRobot) {
        // The folder vanishes right after the check, so the window's project service refuses it.
        windows.isFolder = { folder ->
            Files.delete(folder)
            true
        }

        chooseAfterLastWindow(robot, "failing")

        assertEquals(emptyList(), windows.stages)
        val picker = pickers().single()
        assertEquals(listOf(picker), showingStages(), "the failed window is closed")
        assertTrue(errors.single().contains("failing"), errors.single())
        assertEquals(0, exits)
    }

    @Test
    fun `a command-line folder that fails shows an error and the other folders still open`(robot: FxRobot) {
        val missing = dir.resolve("missing")
        val b = dir.resolve("b").createDirectories()
        lateinit var stage: Stage
        robot.interact {
            stage = Stage()
            windows.start(stage, listOf(missing, b))
        }
        WaitForAsyncUtils.waitForFxEvents()

        assertTrue(errors.single().contains("missing"), errors.single())
        assertFalse(stage.isShowing)
        assertEquals(listOf(first.title, "b – ${appTitle()}"), windows.stages.map { it.title })
        assertEquals(emptyList(), pickers())
    }

    @Test
    fun `when no command-line folder opens, the picker appears`(robot: FxRobot) {
        // Without any window or picker, as at startup.
        robot.interact { first.hide() }
        robot.interact { pickersOnFx().forEach(Stage::hide) }
        exits = 0
        lateinit var stage: Stage

        robot.interact {
            stage = Stage()
            windows.start(stage, listOf(dir.resolve("missing")))
        }
        WaitForAsyncUtils.waitForFxEvents()

        assertTrue(errors.single().contains("missing"), errors.single())
        assertEquals(emptyList(), windows.stages)
        assertEquals(listOf(pickers().single()), showingStages())
        assertEquals(0, exits)
    }

    @Test
    fun `a window that fails while closing still leaves the picker`(robot: FxRobot) {
        // Not the first window: a failing handler interrupts hiding, which would leave the primary stage unusable for
        // the next tests.
        robot.interact {
            window(first).openFolder(dir.resolve("a").createDirectories())
            window(first).openFolder(dir.resolve("b").createDirectories())
        }
        val last = windows.stages[1]
        robot.interact { first.hide() }
        val failure = IllegalStateException("dispose failed")
        windows.disposeWindow = { throw failure }
        var thrown: Throwable? = null

        robot.interact { thrown = runCatching { last.hide() }.exceptionOrNull() }

        assertSame(failure, thrown)
        assertEquals(emptyList(), windows.stages)
        assertEquals(appTitle(), pickers().single().title)
        assertEquals(0, exits)
    }

    @Test
    fun `closing the last window shows the project picker, cancelling it ends the application`(robot: FxRobot) {
        robot.interact { first.hide() }
        val picker = pickers().single()
        assertEquals(appTitle(), picker.title)
        assertFalse(picker in windows.stages)
        assertEquals(emptyList(), windows.stages)
        assertEquals(0, exits)

        escape(robot, picker)

        assertEquals(emptyList(), pickers())
        assertEquals(1, exits)
    }

    @Test
    fun `cancelling the picker while a window is open keeps the application running`(robot: FxRobot) {
        robot.interact { windows.showPicker() }

        escape(robot, pickers().single())

        assertEquals(emptyList(), pickers())
        assertEquals(0, exits)
        assertEquals(listOf(first), windows.stages)
    }

    @Test
    fun `picking a project from a window shows one picker, and the chosen project opens in another window`(
        robot: FxRobot,
    ) {
        val current = dir.resolve("current").createDirectories()
        val remembered = dir.resolve("remembered").createDirectories()
        recentProjects.opened(remembered)
        robot.interact { window(first).openFolder(current) }

        robot.interact { windows.pickProject() }
        robot.interact { windows.pickProject() }
        choose(robot, pickers().single(), "remembered")

        assertEquals(emptyList(), pickers())
        val (kept, opened) = windows.stages
        assertSame(first, kept)
        assertEquals(current, window(first).folder)
        assertEquals(remembered, window(opened).folder)
        assertEquals(0, exits)
        robot.interact { opened.hide() }
    }

    @Test
    fun `a project picked from a window that does not open shows an error and the picker again`(robot: FxRobot) {
        recentProjects.opened(dir.resolve("vanished").createDirectories())
        robot.interact { window(first).openFolder(dir.resolve("current").createDirectories()) }
        windows.isFolder = { false }

        robot.interact { windows.pickProject() }
        choose(robot, pickers().single(), "vanished")

        assertTrue(errors.single().contains("vanished"), errors.single())
        assertEquals(1, pickers().size)
        assertEquals(listOf(first), windows.stages)
    }

    @Test
    fun `a picker picked from the last window stays the only one when it closes, and cancelling it then ends`(
        robot: FxRobot,
    ) {
        robot.interact { window(first).openFolder(dir.resolve("current").createDirectories()) }
        robot.interact { windows.pickProject() }
        val picker = pickers().single()

        robot.interact { first.hide() }

        assertEquals(listOf(picker), pickers())
        assertEquals(0, exits)
        escape(robot, picker)
        assertEquals(1, exits)
    }

    @Test
    fun `closing the context closes the windows without showing the picker`(robot: FxRobot) {
        robot.interact { window(first).openFolder(dir.resolve("a").createDirectories()) }

        robot.interact { context.close() }

        assertEquals(emptyList(), windows.stages)
        assertEquals(emptyList(), pickers())
        assertEquals(0, exits)
    }

    @Test
    fun `closing the context on another thread leaves the windows alone`(robot: FxRobot) {
        robot.interact { window(first).openFolder(dir.resolve("a").createDirectories()) }
        val dispose = windows.disposeWindow
        var disposed = 0
        windows.disposeWindow = {
            disposed++
            dispose(it)
        }
        var thrown: Throwable? = null

        // On a thread of its own, as Spring's shutdown hook does on SIGTERM.
        val closer =
            Thread { thrown = runCatching { context.close() }.exceptionOrNull() }.apply {
                start()
                join(10_000)
            }
        WaitForAsyncUtils.waitForFxEvents()

        assertFalse(closer.isAlive)
        assertNull(thrown)
        assertEquals(0, disposed)
        assertEquals(listOf(first), windows.stages)
        assertEquals(emptyList(), pickers())
        assertEquals(0, exits)
        // The toolkit goes on here, so release the window as a normal end would.
        robot.interact { windows.destroy() }
        assertEquals(1, disposed)
    }

    @Test
    fun `starting without folders shows the picker instead of a window`(robot: FxRobot) {
        lateinit var stage: Stage
        robot.interact {
            stage = Stage()
            windows.start(stage, emptyList())
        }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(appTitle(), pickers().single().title)
        assertFalse(stage.isShowing)
        assertEquals(listOf(first), windows.stages)
    }

    @Test
    fun `starting with folders opens them without the picker`(robot: FxRobot) {
        val a = dir.resolve("a").createDirectories()
        val b = dir.resolve("b").createDirectories()
        lateinit var stage: Stage
        robot.interact {
            stage = Stage()
            windows.start(stage, listOf(a, b))
        }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(emptyList(), pickers())
        val started = windows.stages.drop(1)
        assertEquals(2, started.size)
        assertSame(stage, started[0])
        assertEquals(listOf(a, b), started.map { window(it).folder })
        assertTrue(started.all { it.isShowing })
        robot.interact { started.forEach(Stage::hide) }
    }
}
