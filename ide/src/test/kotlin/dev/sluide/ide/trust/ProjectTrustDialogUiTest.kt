package dev.sluide.ide.trust

import dev.sluide.projecttrust.ProjectTrustState
import dev.sluide.projecttrust.TrustCheckResult
import dev.sluide.projecttrust.TrustFinding
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** UI tests of the dialog that asks whether to trust a folder (ADR 0053). Needs a display. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class ProjectTrustDialogUiTest {
    private lateinit var stage: Stage
    private val folder = Path.of("/work/project")

    @Start
    fun start(stage: Stage) {
        this.stage = stage
        stage.scene = Scene(StackPane(), 200.0, 100.0)
        stage.show()
    }

    @Test
    fun `the dialog names the tool, keeps git off by default and answers Keep Off when closed`(robot: FxRobot) {
        val state =
            ProjectTrustState.Ask(
                listOf(TrustCheckResult("git", "Git", listOf(TrustFinding("core.fsmonitor = ./watch.sh")))),
                changed = false,
            )
        robot.interact {
            val dialog = trustConfirmation(stage, folder, state)
            assertEquals("Trust Folder?", dialog.title)
            assertEquals("project defines commands that Git would run.", dialog.headerText)
            val keepOff = dialog.dialogPane.lookupButton(KEEP_OFF_BUTTON) as Button
            val trust = dialog.dialogPane.lookupButton(TRUST_BUTTON) as Button
            assertTrue(keepOff.isDefaultButton)
            assertFalse(trust.isDefaultButton)
            // Esc and closing the window answer with the cancel button: Keep Off.
            dialog.show()
            dialog.close()
            assertEquals(KEEP_OFF_BUTTON, dialog.result)
        }
    }

    @Test
    fun `a folder that could not be checked says so`(robot: FxRobot) {
        val state = ProjectTrustState.Untrusted(listOf(TrustCheckResult("git", "Git", null)))
        robot.interact {
            assertEquals(
                "What project would make Git run could not be checked.",
                trustConfirmation(stage, folder, state).headerText,
            )
        }
    }
}
