package dev.sluide.codeintelligence.rename

import javafx.application.Platform
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.DialogPane
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import javafx.stage.Window
import javafx.stage.WindowEvent
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The rename preview dialog: owned by the window, titled "Rename", the question as header; a preview naming a refusal
 * offers Cancel only, dismissing counts as Cancel, Rename (the default) applies. The preview's content is
 * `RenameModelTest`'s. Needs a display: a dialog is a window of its own, shown and answered in a nested event loop.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
internal class RenameDialogUiTest {
    private lateinit var stage: Stage

    @Start
    fun start(stage: Stage) {
        this.stage = stage
        stage.scene = Scene(StackPane(), 400.0, 300.0)
        stage.show()
    }

    private fun <T> fx(block: () -> T): T = WaitForAsyncUtils.asyncFx(Callable { block() }).get(5, TimeUnit.SECONDS)

    private fun preview(refusal: String?) =
        RenamePreview(
            "Rename “run” to “start”?",
            "3 changes in 2 files",
            listOf(PreviewRow("a.py", 2, null), PreviewRow("b.py", 1, "written to disk")),
            refusal,
        )

    /** Shows the dialog for [preview] (answered later, in its nested event loop); the answer when it closes. */
    private fun show(preview: RenamePreview): CompletableFuture<Boolean> {
        val answer = CompletableFuture<Boolean>()
        Platform.runLater { answer.complete(confirmRename(stage, preview)) }
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS) { dialog() != null }
        return answer
    }

    /** The dialog's window while it shows. */
    private fun dialog(): Stage? =
        fx { Window.getWindows().filterIsInstance<Stage>().firstOrNull { it !== stage && it.isShowing } }

    private fun pane(dialog: Stage): DialogPane = fx { dialog.scene.root as DialogPane }

    @Test
    fun `a preview naming a refusal offers Cancel only, and dismissing the dialog answers no`(robot: FxRobot) {
        val answer = show(preview("The rename cannot be applied: lib.pyi is outside the project."))
        val dialog = checkNotNull(dialog())
        fx {
            assertEquals("Rename", dialog.title)
            assertSame(stage, dialog.owner)
            assertEquals("Rename “run” to “start”?", pane(dialog).headerText)
            assertEquals(listOf(ButtonType.CANCEL), pane(dialog).buttonTypes.toList())
        }
        robot.interact { dialog.fireEvent(WindowEvent(dialog, WindowEvent.WINDOW_CLOSE_REQUEST)) }
        assertFalse(answer.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun `without a refusal Rename is the default button and applies`(robot: FxRobot) {
        val answer = show(preview(null))
        val dialog = checkNotNull(dialog())
        val rename = fx { pane(dialog).buttonTypes.first() }
        assertEquals(listOf("Rename", "Cancel"), fx { pane(dialog).buttonTypes.map { it.text } })
        val button = fx { pane(dialog).lookupButton(rename) as Button }
        assertTrue(fx { button.isDefaultButton })
        robot.interact { button.fire() }
        assertTrue(answer.get(5, TimeUnit.SECONDS))
    }
}
