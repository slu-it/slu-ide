package dev.sluide.fileviewers

import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [SourceSplit]: the mode buttons switch the sides of a real SplitPane. Needs a display. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class SourceSplitUiTest {
    private val container = StackPane()
    private val source = StackPane()
    private val other = StackPane()

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(container, 400.0, 200.0)
        stage.show()
    }

    private fun shown(
        robot: FxRobot,
        initial: SplitMode = SplitMode.SPLIT,
    ): SourceSplit {
        lateinit var split: SourceSplit
        robot.interact {
            split = SourceSplit(source, other, "Gradle", "gradle-mode", initial)
            container.children.setAll(split.node)
        }
        return split
    }

    private fun SourceSplit.button(label: String) = modeButtons.single { it.text == label }

    @Test
    fun `starts split with three mode buttons and narrowable sides`(robot: FxRobot) {
        val split = shown(robot)

        assertEquals(listOf<Node>(source, other), split.node.items.toList())
        assertEquals(SplitMode.SPLIT, split.mode.get())
        assertEquals(listOf("Source", "Split", "Gradle"), split.modeButtons.map { it.text })
        assertEquals(listOf(false, true, false), split.modeButtons.map { it.isSelected })
        assertTrue(split.modeButtons.all { "gradle-mode" in it.styleClass && !it.isFocusTraversable })
        assertEquals(0.0, source.minWidth)
        assertEquals(0.0, other.minWidth)
    }

    @Test
    fun `starts in the initial mode`(robot: FxRobot) {
        val split = shown(robot, SplitMode.OTHER)

        assertEquals(listOf<Node>(other), split.node.items.toList())
        assertTrue(split.button("Gradle").isSelected)
    }

    @Test
    fun `the buttons and the mode switch the shown sides`(robot: FxRobot) {
        val split = shown(robot)

        robot.interact { split.button("Source").fire() }
        assertEquals(SplitMode.SOURCE, split.mode.get())
        assertEquals(listOf<Node>(source), split.node.items.toList())

        robot.interact { split.button("Gradle").fire() }
        assertEquals(SplitMode.OTHER, split.mode.get())
        assertEquals(listOf<Node>(other), split.node.items.toList())

        robot.interact { split.mode.set(SplitMode.SPLIT) }
        assertEquals(listOf<Node>(source, other), split.node.items.toList())
        assertTrue(split.button("Split").isSelected)
        assertFalse(split.button("Gradle").isSelected)
    }

    @Test
    fun `clicking the selected button keeps it selected`(robot: FxRobot) {
        val split = shown(robot)

        robot.interact { split.button("Split").fire() }

        assertTrue(split.button("Split").isSelected)
        assertEquals(SplitMode.SPLIT, split.mode.get())
        assertEquals(listOf<Node>(source, other), split.node.items.toList())
    }

    @Test
    fun `the divider stays where it was while one side is hidden`(robot: FxRobot) {
        val split = shown(robot)
        assertEquals(0.5, split.node.dividerPositions[0], 0.01)
        robot.interact { split.node.setDividerPositions(0.3) }

        robot.interact { split.mode.set(SplitMode.SOURCE) }
        robot.interact { split.mode.set(SplitMode.OTHER) }
        robot.interact { split.mode.set(SplitMode.SPLIT) }

        assertEquals(0.3, split.node.dividerPositions[0], 0.01)
    }
}
