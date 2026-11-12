package dev.sluide.codereview.window

import dev.sluide.codereview.ReviewScope
import dev.sluide.codereview.Reviews
import dev.sluide.codereview.list.ReviewDiffView
import dev.sluide.codereview.model.ExpandDirection
import dev.sluide.codereview.model.ReviewLayout
import dev.sluide.fileviewers.DefaultTextFileViewer
import dev.sluide.fileviewers.FileViewers
import dev.sluide.projectdata.ProjectDataStore
import dev.sluide.settings.FontSettings
import dev.sluide.settings.SettingsStore
import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.event.Event
import javafx.geometry.Rectangle2D
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.Region
import javafx.stage.Screen
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A diff view that only records what the window asks of it. */
private class FakeDiffView : ReviewDiffView {
    override val node: Node = Region()
    override var onExpand: (String, Int, ExpandDirection) -> Unit = { _, _, _ -> }
    override var onViewedChanged: (String, Boolean) -> Unit = { _, _ -> }
    override var onCollapsedChanged: (String, Boolean) -> Unit = { _, _ -> }
    override var onLoadDiff: (String) -> Unit = {}
    override val topFile: ReadOnlyObjectProperty<String?> = ReadOnlyObjectWrapper<String?>(null).readOnlyProperty

    override fun scrollToRow(
        index: Int,
        offset: Double,
    ) {}

    override fun firstVisible() = 0 to 0.0

    override fun rowCell(index: Int): Node? = null
}

/** What needs a scene: the window's stage, its toggles and notice bar wired to the loader and the stored settings. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class ReviewWindowUiTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var ide: Stage
    private val source = FakeSource(listOf(file("a.kt")))
    private val store by lazy { ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList()) }
    private val fonts by lazy {
        FontSettings(SettingsStore(dir.resolve("settings.properties")), FontSettings.systemDefaults())
    }
    private val viewers by lazy { DefaultTextFileViewer().let { FileViewers(listOf(it), it) } }
    private val reviews by lazy { Reviews(source, fonts, store, viewers) }
    private val project by lazy { dir.resolve("project") }

    @Start
    fun start(stage: Stage) {
        ide = stage
        stage.scene = Scene(Region(), 300.0, 200.0)
        stage.show()
    }

    private fun window(robot: FxRobot): ReviewWindow {
        var window: ReviewWindow? = null
        robot.interact {
            window =
                ReviewWindow(
                    project,
                    ReviewScope.Branch("HEAD"),
                    ide,
                    Rectangle2D(0.0, 0.0, 800.0, 600.0),
                    source,
                    fonts,
                    ReviewPrefs(store),
                    { _, _ -> null },
                    ReviewActions({ _, _ -> }, {}),
                    diffViewFactory = { _, _ -> FakeDiffView() },
                ).also(ReviewWindow::show)
        }
        return checkNotNull(window)
    }

    private fun eventually(assertion: () -> Unit) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (true) {
            WaitForAsyncUtils.waitForFxEvents()
            val failure = runCatching(assertion).exceptionOrNull() ?: return
            if (System.nanoTime() > deadline) throw failure
            Thread.sleep(50) // polling for the review thread's result
        }
    }

    @Test
    fun `a window opens without an owner, with its title, 80 percent of the screen, and the next one 30 px off`(
        robot: FxRobot,
    ) {
        robot.interact {
            reviews.open(project, ReviewScope.Branch("HEAD"), ide)
            reviews.open(project, ReviewScope.Branch("HEAD"), ide)
        }
        val (first, second) = reviews.windows.map { it.stage }
        val area = Screen.getScreensForRectangle(ide.x, ide.y, ide.width, ide.height).first().visualBounds

        eventually { assertEquals("Review – main..feature – project", first.title) }
        robot.interact {
            assertNull(first.owner)
            assertEquals(area.width * 0.8, first.width, 1.0)
            assertEquals(area.height * 0.8, first.height, 1.0)
            assertEquals(area.minX + (area.width - first.width) / 2, first.x, 1.0)
            assertEquals(area.minY + (area.height - first.height) / 2, first.y, 1.0)
            assertEquals(first.x + 30, second.x, 1.0)
            assertEquals(first.y + 30, second.y, 1.0)
        }
    }

    @Test
    fun `closeAll closes every window`(robot: FxRobot) {
        robot.interact {
            reviews.open(project, ReviewScope.Branch("HEAD"), ide)
            reviews.open(project, ReviewScope.Branch("HEAD"), ide)
        }
        val stages = reviews.windows.map { it.stage }

        robot.interact { reviews.closeAll() }

        robot.interact {
            assertTrue(stages.none { it.isShowing })
            assertTrue(reviews.windows.isEmpty())
        }
    }

    @Test
    fun `Shortcut+W closes the window and cancels its load`(robot: FxRobot) {
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        source.fileDiffsHook = { _, _, cancellation ->
            cancellation.onCancel { cancelled.countDown() }
            started.countDown()
            cancelled.await(10, TimeUnit.SECONDS)
        }
        val window = window(robot)
        assertTrue(started.await(10, TimeUnit.SECONDS))
        val mac = System.getProperty("os.name").contains("Mac", ignoreCase = true)

        robot.interact {
            Event.fireEvent(
                window.stage.scene.root,
                KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.W, false, !mac, false, mac),
            )
        }

        assertTrue(cancelled.await(10, TimeUnit.SECONDS), "the load is cancelled")
        robot.interact { assertFalse(window.stage.isShowing) }
    }

    @Test
    fun `the layout toggle sets the document's layout and is remembered`(robot: FxRobot) {
        val window = window(robot)

        robot.interact { window.layoutButtons.getValue(ReviewLayout.UNIFIED).fire() }

        robot.interact { assertEquals(ReviewLayout.UNIFIED, window.document.layout.get()) }
        eventually { assertEquals(ReviewLayout.UNIFIED, ReviewPrefs(store).read().layout) }
        // Clicking the selected one keeps it selected.
        robot.interact {
            window.layoutButtons.getValue(ReviewLayout.UNIFIED).fire()
            assertTrue(window.layoutButtons.getValue(ReviewLayout.UNIFIED).isSelected)
        }
    }

    @Test
    fun `the whitespace toggle reloads the diffs with the flag and is remembered`(robot: FxRobot) {
        val window = window(robot)
        eventually { assertEquals(1, source.diffCalls.size) }

        robot.interact { window.hideWhitespace.fire() }

        eventually { assertEquals(listOf(listOf("a.kt") to false, listOf("a.kt") to true), source.diffCalls.toList()) }
        assertEquals(1, source.scopes.size, "no new resolve")
        eventually { assertTrue(ReviewPrefs(store).read().hideWhitespace) }
    }

    @Test
    fun `a changed fingerprint shows the notice bar after repositoryChanged, and Reload hides it`(robot: FxRobot) {
        robot.interact { reviews.open(project, ReviewScope.Branch("HEAD"), ide) }
        val window = reviews.windows.single()
        eventually { assertEquals(1, source.fingerprints.size) }
        eventually { robot.interact { assertFalse(window.noticeBar.isVisible) } }

        source.print = "changed"
        robot.interact { reviews.repositoryChanged() }

        eventually { robot.interact { assertTrue(window.noticeBar.isVisible && window.noticeBar.isManaged) } }
        robot.interact { window.reloadButton.fire() }
        eventually { assertEquals(2, source.scopes.size) }
        eventually { robot.interact { assertFalse(window.noticeBar.isVisible) } }
    }
}
