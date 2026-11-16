package dev.sluide.gitlog.area

import dev.sluide.gitlog.ui.GIT_LOG_CSS
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleIntegerProperty
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.layout.BorderPane
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The header bar alone, laid out only by the pulses, as on screen: a scene is needed for the stylesheet's look and
 * spacing and the laid-out button widths. Its buttons' actions are tested through the view (`GitLogViewAreaUiTest`).
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class HeaderBarUiTest {
    private lateinit var bar: HeaderBar
    private val stashOff = SimpleBooleanProperty(false)
    private val popOff = SimpleBooleanProperty(false)
    private val stashCount = SimpleIntegerProperty(0)
    private val stashRuns = mutableListOf<String>()

    @Start
    fun start(stage: Stage) {
        val off = SimpleBooleanProperty(false)
        bar =
            HeaderBar(
                Label("Git Log"),
                Label(),
                SimpleBooleanProperty(),
                off,
                off,
                off,
                HeaderAction({}, off),
                HeaderAction({}, off),
                HeaderAction({}, off),
                HeaderAction({ stashRuns += "stash" }, stashOff),
                HeaderAction({ stashRuns += "pop" }, popOff),
                stashCount,
                {},
                {},
                {},
                {},
                {},
            )
        stage.scene = Scene(root, 600.0, 200.0).apply { stylesheets += GIT_LOG_CSS }
        stage.show()
    }

    private val root = BorderPane()

    @Test
    fun `the groups are one button width apart from the first layout on, and follow the spacing`(robot: FxRobot) {
        // Added to a window that is shown already and keeps its size, like the area when a folder opens: no resize
        // lays the header out a second time, so the gaps must be there after its first layout.
        WaitForAsyncUtils.waitForFxEvents()
        robot.interact { root.bottom = bar.node }
        eventually { assertGroups() }

        robot.interact { bar.node.style = "-fx-spacing: 10" }
        eventually { assertGroups() }
    }

    @Test
    fun `the actions are outlined alike and show their tooltips after 250 ms`(robot: FxRobot) {
        robot.interact { root.bottom = bar.node }

        eventually {
            val actions =
                listOf(
                    bar.reviewButton,
                    bar.rebaseDefaultButton,
                    bar.newBranchDefaultButton,
                    bar.stashButton,
                    bar.popButton,
                    bar.pushButton,
                    bar.pullButton,
                    bar.fetchButton,
                    bar.refreshButton,
                )
            // Painted alike: Modena's outlined button (border and body fills), none flat.
            val fills = actions.map { it.background.fills.map { fill -> fill.fill } }
            assertTrue(fills.all { it == fills.first() }, "the same background on every action: $fills")
            assertTrue(fills.first().size > 1, "an outlined button, not a flat one: ${fills.first()}")
            actions.forEach { assertEquals(250.0, it.tooltip.showDelay.toMillis(), it.tooltip.text) }
            assertEquals("Stash Changes with Untracked Files", bar.stashButton.tooltip.text)
        }
    }

    @Test
    fun `Stash and Pop follow their header actions, and Pop's tooltip follows the count`(robot: FxRobot) {
        robot.interact { root.bottom = bar.node }
        robot.interact {
            assertEquals("Pop Latest Stash", bar.popButton.tooltip.text)
            assertFalse(bar.stashButton.isDisable)
            assertFalse(bar.popButton.isDisable)
            bar.stashButton.fire()
            bar.popButton.fire()
            assertEquals(listOf("stash", "pop"), stashRuns)

            stashOff.set(true)
            assertTrue(bar.stashButton.isDisable)
            assertFalse(bar.popButton.isDisable, "each button has its own rule")
            stashOff.set(false)
            popOff.set(true)
            assertTrue(bar.popButton.isDisable)
            assertFalse(bar.stashButton.isDisable)

            stashCount.set(1)
            assertEquals("Pop Latest Stash (1 stash)", bar.popButton.tooltip.text)
            stashCount.set(3)
            assertEquals("Pop Latest Stash (3 stashes)", bar.popButton.tooltip.text)
        }
    }

    /**
     * Review | Fetch and Rebase, Fetch and New Branch | Stash, Pop | Push, Pull, Fetch, Refresh: one button width
     * between the groups, the spacing within one. FX thread.
     */
    private fun assertGroups() {
        fun gap(
            left: Node,
            right: Node,
        ) = right.boundsInParent.minX - left.boundsInParent.maxX
        val width = bar.refreshButton.width
        assertEquals(width, gap(bar.reviewButton, bar.rebaseDefaultButton), 1.0)
        assertEquals(bar.node.spacing, gap(bar.rebaseDefaultButton, bar.newBranchDefaultButton), 0.5)
        assertEquals(width, gap(bar.newBranchDefaultButton, bar.stashButton), 1.0)
        assertEquals(bar.node.spacing, gap(bar.stashButton, bar.popButton), 0.5)
        assertEquals(width, gap(bar.popButton, bar.pushButton), 1.0)
        assertEquals(bar.node.spacing, gap(bar.pushButton, bar.pullButton), 0.5)
    }

    /** Waits until [assertion] holds on the FX thread, through as many pulses as it takes, for at most 5 s. */
    private fun eventually(assertion: () -> Unit) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (true) {
            WaitForAsyncUtils.waitForFxEvents()
            val failure = WaitForAsyncUtils.asyncFx<Throwable?> { runCatching(assertion).exceptionOrNull() }.get()
            if (failure == null) return
            if (System.nanoTime() > deadline) throw failure
            Thread.sleep(20)
        }
    }
}
