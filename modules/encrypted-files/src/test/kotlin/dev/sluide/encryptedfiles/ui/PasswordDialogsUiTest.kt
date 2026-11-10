package dev.sluide.encryptedfiles.ui

import dev.sluide.encryptedfiles.newPasswordDialog
import javafx.application.Platform
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Labeled
import javafx.scene.control.PasswordField
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** The New Password dialog: its fields and buttons are controls of a scene (the rules are in PasswordEntryModelTest). */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class PasswordDialogsUiTest {
    private val password = "another long password"

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(StackPane(), 200.0, 100.0)
        stage.show()
    }

    private fun <T> fx(read: () -> T): T = WaitForAsyncUtils.asyncFx<T>(read).get()

    private fun awaitFx(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (!fx(condition)) {
            if (System.nanoTime() > deadline) fail("condition not met in time")
            // Poll interval while the work runs on its thread.
            Thread.sleep(20)
        }
        WaitForAsyncUtils.waitForFxEvents()
    }

    private fun field(
        robot: FxRobot,
        id: String,
    ) = robot.lookup(id).queryAs(PasswordField::class.java)

    private fun dialogShown(robot: FxRobot) = fx { robot.lookup("#password-new").tryQuery<Node>().isPresent }

    private fun button(
        robot: FxRobot,
        text: String,
    ) = fx { robot.lookup(".dialog-pane .button").queryAllAs(Button::class.java).single { it.text == text } }

    private fun status(robot: FxRobot) = fx { robot.lookup("#password-status").queryAs(Labeled::class.java).text }

    private fun show(
        robot: FxRobot,
        open: () -> Unit,
    ) {
        Platform.runLater(open)
        awaitFx { dialogShown(robot) }
    }

    private fun type(
        robot: FxRobot,
        new: String = password,
        repeat: String = new,
    ) = robot.interact {
        field(robot, "#password-new").text = new
        field(robot, "#password-repeat").text = repeat
    }

    @Test
    fun `Create is enabled only for a long enough matching password, and runs the work`(robot: FxRobot) {
        val received = AtomicReference<CharArray>()
        val seen = AtomicReference<String>()
        val result = AtomicReference<Boolean>()
        show(robot) {
            result.set(
                newPasswordDialog(null, "New Encrypted Scratch File") { typed ->
                    received.set(typed)
                    seen.set(String(typed))
                },
            )
        }

        assertTrue(fx { button(robot, "Create").isDisable })
        type(robot, "short")
        assertTrue(fx { button(robot, "Create").isDisable })
        type(robot, repeat = password + "x")
        assertTrue(fx { button(robot, "Create").isDisable })
        type(robot)
        assertFalse(fx { button(robot, "Create").isDisable })
        robot.interact { button(robot, "Create").fire() }
        awaitFx { result.get() != null }

        assertEquals(true, result.get())
        assertEquals(password, seen.get())
        assertTrue(received.get().all { it == '\u0000' }, "the password array is zeroed after the work")
    }

    @Test
    fun `an IOException of the work is shown and the dialog stays, Cancel returns false`(robot: FxRobot) {
        val result = AtomicReference<Boolean>()
        show(robot) {
            result.set(newPasswordDialog(null, "New Encrypted Scratch File") { throw IOException("Disk is full") })
        }
        type(robot)
        robot.interact { button(robot, "Create").fire() }
        awaitFx { status(robot) == "Disk is full" }

        assertTrue(dialogShown(robot))
        assertNull(result.get())
        robot.interact { button(robot, "Cancel").fire() }
        awaitFx { result.get() != null }
        assertEquals(false, result.get())
    }
}
