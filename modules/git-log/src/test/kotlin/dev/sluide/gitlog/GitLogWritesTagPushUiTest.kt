package dev.sluide.gitlog

import dev.sluide.gitlog.branches.BranchMenuEntry
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.TextArea
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

/** UI tests of pushing tags from the branches pane's menu (ADR 0173). Needs a display. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class GitLogWritesTagPushUiTest : GitLogWritesUiFixture() {
    @Start
    fun start(stage: Stage) = startView(stage)

    @Test
    fun `with one remote a tag is pushed at once, without a question`(robot: FxRobot) {
        openAndExpand(robot)
        selectBranches(robot, "Tags/v0.9")

        fireLater(branches.menu.item(BranchMenuEntry.PUSH_TAG))

        eventually { assertEquals("Pushed tag v0.9 to origin.", status(robot)) }
        assertNoDialog()
        assertEquals(listOf("push-tags origin refs/tags/v0.9"), writes.calls)
        assertEquals(setOf("sluide-git-write"), writes.threads.toSet())
    }

    @Test
    fun `with several remotes a dialog asks where, origin checked, and pushes only to the checked ones`(
        robot: FxRobot,
    ) {
        currentRemotes = listOf("upstream", "origin", "fork")
        openAndExpand(robot)
        selectBranches(robot, "Tags/v0.9", "Tags/v1.0")

        fireLater(branches.menu.item(BranchMenuEntry.PUSH_TAG))
        val pane = awaitDialog(robot)

        assertEquals("Push 2 tags?", pane.headerText)
        assertEquals("v0.9\nv1.0", (pane.lookup(".git-push-tag-names") as TextArea).text)
        val boxes = pane.lookupAll(".git-push-tag-remote").map { it as CheckBox }
        assertEquals(listOf("upstream", "origin", "fork"), boxes.map { it.text })
        assertEquals(listOf(false, true, false), boxes.map { it.isSelected })
        val push = pane.lookupButton(pane.buttonTypes.single { it.text == "Push" }) as Button
        assertTrue(push.isDefaultButton)
        assertFalse((pane.lookupButton(ButtonType.CANCEL) as Button).isDefaultButton)
        robot.interact { boxes[1].isSelected = false }
        assertTrue(push.isDisable, "no remote checked")
        robot.interact { boxes[2].isSelected = true }
        assertFalse(push.isDisable)
        robot.interact { boxes[0].isSelected = true }
        press(robot, pane, ButtonType.CANCEL)
        assertNoDialog()
        assertEquals(emptyList(), writes.calls, "Cancel pushes nothing")

        fireLater(branches.menu.item(BranchMenuEntry.PUSH_TAG))
        val again = awaitDialog(robot)
        val againBoxes = again.lookupAll(".git-push-tag-remote").map { it as CheckBox }
        robot.interact { againBoxes.forEach { it.isSelected = it.text != "origin" } }
        press(robot, again, again.buttonTypes.single { it.text == "Push" })

        eventually { assertEquals("Pushed 2 tags to upstream and fork.", status(robot)) }
        assertEquals(
            listOf(
                "push-tags upstream refs/tags/v0.9 refs/tags/v1.0",
                "push-tags fork refs/tags/v0.9 refs/tags/v1.0",
            ),
            writes.calls,
        )
    }
}
