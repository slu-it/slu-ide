package dev.sluide.fileexplorer

import dev.sluide.texttypes.TextTypes
import javafx.application.Platform
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.DialogPane
import javafx.stage.Stage
import javafx.stage.Window
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The explorer's own dialogs, when no test replaces them: shown over the explorer's window. Needs a display. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class ExplorerAlertsUiTest : FileExplorerViewUiFixture() {
    private lateinit var window: Window

    @Start
    fun start(stage: Stage) {
        startView(stage)
        window = stage
    }

    /** The dialog pane of the one shown dialog, once there is one. */
    private fun shownDialog(robot: FxRobot): DialogPane {
        var pane: DialogPane? = null
        eventually(robot) {
            robot.interact {
                pane =
                    Window
                        .getWindows()
                        .filter { it !== window && it.isShowing }
                        .mapNotNull { it.scene?.root as? DialogPane }
                        .singleOrNull()
            }
            assertTrue(pane != null, "a dialog shows")
        }
        return checkNotNull(pane)
    }

    private fun close(
        robot: FxRobot,
        pane: DialogPane,
        button: ButtonType,
    ) = robot.interact { pane.lookupButton(button).let { (it as Button).fire() } }

    @Test
    fun `an error shows in an alert over the explorer's window, without a header`(robot: FxRobot) {
        Platform.runLater { view.showError("It failed.") }

        val pane = shownDialog(robot)

        assertEquals("It failed.", pane.contentText)
        assertNull(pane.headerText)
        assertTrue("error" in pane.styleClass, "an error alert: ${pane.styleClass}")
        assertSame(window, (pane.scene.window as Stage).owner)
        close(robot, pane, ButtonType.OK)
    }

    @Test
    fun `a note shows in an information alert over the explorer's window, without a header`(robot: FxRobot) {
        Platform.runLater { view.showInfo("Done.") }

        val pane = shownDialog(robot)

        assertEquals("Done.", pane.contentText)
        assertNull(pane.headerText)
        assertTrue("information" in pane.styleClass, "an information alert: ${pane.styleClass}")
        assertSame(window, (pane.scene.window as Stage).owner)
        close(robot, pane, ButtonType.OK)
    }

    @Test
    fun `the text name dialog shows over the explorer's window, OK answers the name with the detected extension`(
        robot: FxRobot,
    ) {
        val answer = AtomicReference<String?>("not asked")
        val json = checkNotNull(TextTypes.byExtension("json"))
        Platform.runLater { answer.set(view.askTextName("pasted-x", json, project)) }

        val pane = shownDialog(robot)

        assertEquals("Paste Text", (pane.scene.window as Stage).title)
        assertSame(window, (pane.scene.window as Stage).owner)
        close(robot, pane, ButtonType.OK)
        eventually(robot) { assertEquals("pasted-x.json", answer.get()) }
    }
}
