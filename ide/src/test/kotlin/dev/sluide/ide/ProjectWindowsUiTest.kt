package dev.sluide.ide

import dev.sluide.dockersandbox.DockerSandbox
import dev.sluide.editor.EditorArea
import dev.sluide.fileexplorer.FileExplorerView
import dev.sluide.fileviewers.DefaultTextFileViewer
import dev.sluide.fileviewers.FileViewers
import dev.sluide.filewatcher.FileWatcher
import dev.sluide.git.GitCli
import dev.sluide.git.GitStatusService
import dev.sluide.httpclient.HttpClientSession
import dev.sluide.settings.FontSettings
import dev.sluide.settings.FontsPage
import dev.sluide.settings.SettingsPages
import dev.sluide.settings.SettingsStore
import dev.sluide.settings.ThemeSettings
import dev.sluide.settings.ThemedWindows
import dev.sluide.terminal.Terminals
import javafx.application.Platform
import javafx.stage.Stage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Path
import java.util.function.Supplier
import kotlin.io.path.createDirectories
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
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

    @Start
    fun start(stage: Stage) {
        first = stage
        val store = SettingsStore(dir.resolve("settings/settings.properties"))
        context =
            AnnotationConfigApplicationContext().apply {
                register(WindowScopeConfiguration::class.java, ProjectWindows::class.java, SystemBrowser::class.java)
                registerBean(SettingsStore::class.java, Supplier { store })
                registerBean(FontSettings::class.java, Supplier { FontSettings(store) })
                registerBean(ThemedWindows::class.java, Supplier { ThemedWindows(ThemeSettings(store)) })
                registerBean(
                    SettingsPages::class.java,
                    Supplier { SettingsPages(listOf(FontsPage(getBean(FontSettings::class.java)))) },
                )
                registerBean(FileWatcher::class.java, Supplier { FileWatcher(Platform::runLater, 50.milliseconds) })
                registerBean(GitCli::class.java, Supplier { GitCli(dir.resolve("no-git").toString(), System.getenv()) })
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
                    HttpClientSession::class.java,
                )
                refresh()
            }
        windows = context.getBean(ProjectWindows::class.java)
        windows.show(stage)
    }

    @AfterEach
    fun close() {
        Platform.runLater { context.close() }
        WaitForAsyncUtils.waitForFxEvents()
    }

    private fun window(stage: Stage) = windows.windowOf(stage)

    @Test
    fun `the first folder opens in the first window, others in new windows with their own beans`(robot: FxRobot) {
        val a = dir.resolve("a").createDirectories()
        val b = dir.resolve("b").createDirectories()

        robot.interact { window(first).openFolder(a) }
        robot.interact { window(first).openFolder(b) }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(2, windows.stages.size)
        val second = windows.stages[1]
        assertSame(first, windows.stages[0])
        assertEquals(a, window(first).folder)
        assertEquals(b, window(second).folder)
        assertEquals("a – Thoreau", first.title)
        assertEquals("b – Thoreau", second.title)
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
    fun `opening a folder that is already open brings its window to the front`(robot: FxRobot) {
        val a = dir.resolve("a").createDirectories()
        val b = dir.resolve("b").createDirectories()
        robot.interact {
            window(first).openFolder(a)
            window(first).openFolder(b)
        }

        robot.interact { window(windows.stages[1]).openFolder(a.resolve(".")) }
        WaitForAsyncUtils.waitForFxEvents()

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
            window(second).openTerminal(second)
            terminals = window(second).terminals
        }
        assertEquals(1, terminals.openCount)

        robot.interact { second.hide() }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(0, terminals.openCount)
        assertEquals(listOf(first), windows.stages)
        assertEquals(a, window(first).folder)
    }
}
