package dev.sluide.editorarea

import dev.sluide.fileviewers.ViewActivity
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.property.SimpleStringProperty
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.shape.Rectangle
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * UI tests of what a tab header shows besides the title: the activity marker, the read-only lock and the icon. Needs a
 * display, see the `uiTest` Gradle task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class TabHeaderMarkersUiTest : EditorAreaUiFixture() {
    @Start
    fun start(stage: Stage) = startView(stage)

    /** A tab that is no file but shows an activity, like a Read Aloud reading. */
    private class BusyTab(
        name: String = "Busy tool",
        private val iconFactory: (() -> Node)? = null,
    ) : EditorTab {
        override val title = SimpleStringProperty(name)
        override val tooltip = name
        override val content = TextField()
        override val activity = SimpleObjectProperty(ViewActivity.NONE)

        override fun icon(): Node? = iconFactory?.invoke()
    }

    @Test
    fun `a file tab shows its view's activity as a marker before its title until it closes`(robot: FxRobot) {
        val viewer = BusyViewer()
        val area = areaWith(robot, viewer)
        robot.interact { area.open(textFile("build.busy")) }
        val view = viewer.views.single()
        val header = tabLabel(robot, "build.busy").parent

        // The style classes of the nodes before the title.
        fun markers() =
            header.childrenUnmodifiable
                .takeWhile { it !is Label }
                .flatMap { it.styleClass }
                .filter { it.startsWith("tab-activity-") }
        assertEquals(emptyList(), markers())

        robot.interact { view.activity.set(ViewActivity.RUNNING) }
        assertEquals(listOf("tab-activity-running"), markers())

        robot.interact { view.activity.set(ViewActivity.FAILED) }
        assertEquals(listOf("tab-activity-failed"), markers())
        assertEquals(listOf("build.busy"), tabTitles(robot))

        robot.interact { view.activity.set(ViewActivity.NONE) }
        assertEquals(emptyList(), markers())

        robot.interact { area.closeAll() }
        robot.interact { view.activity.set(ViewActivity.RUNNING) }
        assertEquals(emptyList(), markers(), "a closed tab's header no longer follows its view")
    }

    @Test
    fun `a file tab shows a lock with its tooltip before the title while its view is marked read-only`(robot: FxRobot) {
        val viewer = BusyViewer()
        val area = areaWith(robot, viewer)
        robot.interact { area.open(textFile("lib.busy")) }
        val view = viewer.views.single()
        val header = tabLabel(robot, "lib.busy").parent

        fun locks() = header.childrenUnmodifiable.filter { TAB_LOCK_STYLE_CLASS in it.styleClass }
        assertEquals(0, locks().size)

        view.lockMarkerText = "Encrypted, locked"
        robot.interact { view.readOnlyMarked.set(true) }
        assertEquals(1, locks().size)
        assertEquals(0, header.childrenUnmodifiable.indexOf(locks().single()))
        assertSame(header.childrenUnmodifiable[1], tabLabel(robot, "lib.busy"))
        val tooltip = locks().single().properties["javafx.scene.control.Tooltip"] as javafx.scene.control.Tooltip
        assertEquals("Encrypted, locked", tooltip.text)

        // Read again whenever the icon is shown, so a view can word it per state.
        view.lockMarkerText = "Unlocked"
        robot.interact { view.readOnlyMarked.set(false) }
        robot.interact { view.readOnlyMarked.set(true) }
        val second = locks().single().properties["javafx.scene.control.Tooltip"] as javafx.scene.control.Tooltip
        assertEquals("Unlocked", second.text)
        robot.interact { view.readOnlyMarked.set(false) }
        robot.interact { view.readOnlyMarked.set(true) }

        robot.interact { view.activity.set(ViewActivity.RUNNING) }
        assertEquals(1, locks().size, "the lock stays beside the activity marker")
        assertEquals(1, header.childrenUnmodifiable.indexOf(locks().single()), "the lock follows the activity marker")
        assertSame(header.childrenUnmodifiable[2], tabLabel(robot, "lib.busy"))

        robot.interact { view.readOnlyMarked.set(false) }
        assertEquals(0, locks().size)

        robot.interact { view.readOnlyMarked.set(true) }
        robot.interact { area.closeAll() }
        robot.interact { view.readOnlyMarked.set(false) }
        robot.interact { view.readOnlyMarked.set(true) }
        assertEquals(0, locks().size, "a closed tab's header no longer follows its view")
    }

    @Test
    fun `a tab that is no file shows its activity as a marker too`(robot: FxRobot) {
        val busy = BusyTab()
        robot.interact { editor.open(busy) }
        val header = tabLabel(robot, "Busy tool").parent

        fun markers() =
            header.childrenUnmodifiable
                .takeWhile { it !is Label }
                .flatMap { it.styleClass }
                .filter { it.startsWith("tab-activity-") }
        assertEquals(emptyList(), markers())

        robot.interact { busy.activity.set(ViewActivity.RUNNING) }
        assertEquals(listOf("tab-activity-running"), markers())
    }

    @Test
    fun `a tab with an icon shows it before its title and in the overflow menu, one without shows none`(
        robot: FxRobot,
    ) {
        robot.interact { editor.open(BusyTab("Plain")) }
        robot.interact { editor.open(BusyTab("Iconic") { Rectangle(16.0, 16.0) }) }
        val plainHeader = tabLabel(robot, "Plain").parent
        val iconicHeader = tabLabel(robot, "Iconic").parent

        assertEquals(emptyList(), plainHeader.childrenUnmodifiable.filter { TAB_ICON_STYLE_CLASS in it.styleClass })
        val children = iconicHeader.childrenUnmodifiable
        val icon = children.single { TAB_ICON_STYLE_CLASS in it.styleClass }
        assertIs<Rectangle>(icon)
        assertEquals(children.indexOf(icon) + 1, children.indexOf(tabLabel(robot, "Iconic")))

        robot.interact { editor.closeAll() }
        robot.interact { stage.width = 300.0 }
        robot.interact { editor.open(BusyTab("Iconic") { Rectangle(16.0, 16.0) }) }
        robot.interact { repeat(8) { editor.open(WarningTab("file-number-$it.txt", null)) } }
        val strip = editor.tabStrip
        robot.interact { strip.overflowMenu.show() }
        val graphics = strip.overflowMenu.items.associate { it.text to it.graphic }
        assertIs<Rectangle>(graphics["Iconic"], "the overflow item of the tab with an icon shows it")
    }
}
