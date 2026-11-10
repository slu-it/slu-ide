package dev.sluide.previews.image

import javafx.event.ActionEvent
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.Label
import javafx.scene.control.Spinner
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The size dialog of the custom PNG export: needs a scene for its spinners. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class PngSizeDialogUiTest {
    private lateinit var owner: Stage

    @Start
    fun start(stage: Stage) {
        owner = stage
        stage.scene = Scene(StackPane(), 200.0, 100.0)
        stage.show()
    }

    private fun <T> fx(block: () -> T): T = WaitForAsyncUtils.asyncFx<T> { block() }.get()

    private fun open(natural: Pair<Int, Int>): PngSizeDialog = fx { PngSizeDialog(owner, natural) }

    @Suppress("UNCHECKED_CAST")
    private fun PngSizeDialog.spinner(id: String) = dialogPane.lookup("#$id") as Spinner<Int>

    private fun PngSizeDialog.exportButton() =
        dialogPane.buttonTypes
            .first { it.buttonData == ButtonBar.ButtonData.OK_DONE }
            .let { dialogPane.lookupButton(it) as Button }

    private fun PngSizeDialog.hint() = dialogPane.lookupAll(".png-size-hint").single() as Label

    @Test
    fun `typing in one field updates the other as you type`() {
        val dialog = open(300 to 200)
        fx {
            assertEquals("300", dialog.spinner("png-width").editor.text)
            assertEquals("200", dialog.spinner("png-height").editor.text)
            dialog.spinner("png-width").editor.text = "150"
            assertEquals("100", dialog.spinner("png-height").editor.text)
            dialog.spinner("png-height").editor.text = "40"
            assertEquals("60", dialog.spinner("png-width").editor.text)
        }
    }

    @Test
    fun `stepping a field with its arrows keeps the ratio`() {
        val dialog = open(300 to 200)
        fx {
            dialog.spinner("png-width").increment(10)
            assertEquals("310", dialog.spinner("png-width").editor.text)
            assertEquals("207", dialog.spinner("png-height").editor.text)
        }
    }

    @Test
    fun `Enter and the arrows after invalid or empty text keep a valid value`() {
        val dialog = open(300 to 200)
        fx {
            val width = dialog.spinner("png-width")
            for (text in listOf("x", "")) {
                val before = width.value
                width.editor.text = text
                width.editor.fireEvent(ActionEvent())
                assertEquals(before, width.value, "Enter after '$text'")
                assertEquals(before.toString(), width.editor.text)
                width.editor.text = text
                width.increment(1)
                assertEquals(before + 1, width.value, "arrow after '$text'")
                assertEquals(width.value.toString(), width.editor.text)
                width.decrement(1)
            }
        }
    }

    @Test
    fun `beyond the limits the hint shows and Export is off`() {
        val dialog = open(100 to 100)
        fx {
            assertFalse(dialog.hint().isVisible)
            assertFalse(dialog.exportButton().isDisable)
            dialog.spinner("png-width").editor.text = "8193"
            assertTrue(dialog.hint().isVisible)
            assertEquals("At most 8192 px per side and about 33 megapixels", dialog.hint().text)
            assertTrue(dialog.exportButton().isDisable)
            dialog.spinner("png-width").editor.text = "x"
            assertTrue(dialog.exportButton().isDisable)
            dialog.spinner("png-width").editor.text = "32"
            assertFalse(dialog.hint().isVisible)
            assertFalse(dialog.exportButton().isDisable)
        }
    }

    @Test
    fun `Export returns the size`() {
        val dialog = open(800 to 800)
        val result =
            fx {
                dialog.spinner("png-width").editor.text = "32"
                dialog.resultConverter.call(
                    dialog.dialogPane.buttonTypes.first { it.buttonData == ButtonBar.ButtonData.OK_DONE },
                )
            }
        assertEquals(32 to 32, result)
        assertEquals(null, fx { dialog.resultConverter.call(ButtonType.CANCEL) })
    }
}
