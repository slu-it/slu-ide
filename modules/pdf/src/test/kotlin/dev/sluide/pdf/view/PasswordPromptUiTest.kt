package dev.sluide.pdf.view

import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.PasswordField
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import javafx.stage.Window
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The password dialog: it stays open on a wrong password and says so, disables Open while a password is tried, and
 * reports Cancel. Needs a scene (a dialog window, its buttons and focus).
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
internal class PasswordPromptUiTest {
    private lateinit var stage: Stage
    private var dialog: Dialog<ButtonType>? = null
    private val tried = mutableListOf<String>()
    private val answers = mutableListOf<(Boolean) -> Unit>()
    private val arrays = mutableListOf<CharArray>()
    private var cancelled = 0

    @Start
    fun start(stage: Stage) {
        this.stage = stage
        stage.scene = Scene(StackPane(), 400.0, 300.0)
        stage.show()
    }

    @AfterEach
    fun close() {
        fx { dialog?.close() }
    }

    private fun <T> fx(block: () -> T): T = WaitForAsyncUtils.asyncFx<T> { block() }.get(30, TimeUnit.SECONDS)

    private fun show(): Dialog<ButtonType> =
        fx {
            PasswordPrompt
                .dialog(
                    PasswordRequest(
                        stage,
                        "secret.pdf",
                        { typed, done ->
                            tried += String(typed)
                            arrays += typed
                            answers += done
                        },
                        { cancelled++ },
                    ),
                ).also {
                    dialog = it
                    it.show()
                }
        }

    private fun Dialog<ButtonType>.field() = dialogPane.lookup(".pdf-password") as PasswordField

    private fun Dialog<ButtonType>.wrong() = dialogPane.lookup(".pdf-wrong-password") as Label

    private fun Dialog<ButtonType>.button(type: ButtonType) = dialogPane.lookupButton(type) as Button

    @Test
    fun `asks for the password of the file, owned by the window`() {
        val dialog = show()
        assertEquals("Open PDF", fx { dialog.title })
        assertNull(fx { dialog.headerText })
        assertSame(stage, fx { dialog.owner })
        assertTrue(
            fx {
                dialog.dialogPane.lookupAll(".label").any {
                    (it as Label).text ==
                        "secret.pdf is password-protected."
                }
            },
        )
        assertTrue(fx { dialog.button(PasswordPrompt.OPEN).isDefaultButton })
        assertFalse(fx { dialog.wrong().isVisible || dialog.wrong().isManaged })
        assertSame(dialog.field(), fx { dialog.dialogPane.scene.focusOwner })
    }

    @Test
    fun `a wrong password keeps the dialog open and says so, a right one closes it`() {
        val dialog = show()
        val open = dialog.button(PasswordPrompt.OPEN)
        fx { dialog.field().text = "wrong" }
        fx { open.fire() }
        assertEquals(listOf("wrong"), tried)
        assertTrue(fx { open.isDisable })
        assertTrue(fx { dialog.isShowing })
        assertTrue(arrays.single().all { it == '\u0000' })
        fx { answers.last()(false) }
        assertTrue(fx { dialog.isShowing && dialog.wrong().isVisible && dialog.wrong().isManaged && !open.isDisable })
        assertSame(dialog.field(), fx { dialog.dialogPane.scene.focusOwner })
        assertEquals("wrong", fx { dialog.field().selectedText })
        // The dialog grew with the line: its buttons are still whole.
        WaitForAsyncUtils.waitForFxEvents()
        val buttons = fx { open.localToScene(open.layoutBounds) to dialog.dialogPane.scene }
        assertTrue(
            fx { buttons.first.maxY <= buttons.second.height && buttons.first.maxX <= buttons.second.width },
            fx { "${buttons.first} in ${buttons.second.width} x ${buttons.second.height}" },
        )
        fx { dialog.field().text = "user" }
        fx { open.fire() }
        fx { answers.last()(true) }
        assertEquals(listOf("wrong", "user"), tried)
        assertFalse(fx { dialog.isShowing })
        assertEquals(0, cancelled)
        assertEquals("", fx { dialog.field().text })
    }

    @Test
    fun `show shows the dialog at once, and the function it returns closes it`() {
        val close =
            fx {
                PasswordPrompt.show(PasswordRequest(stage, "secret.pdf", { _, _ -> }, { cancelled++ }))
            }
        val shown = fx { Window.getWindows().filterIsInstance<Stage>().single { it.title == "Open PDF" } }
        assertTrue(fx { shown.isShowing })
        fx { close() }
        assertFalse(fx { shown.isShowing })
        assertEquals(1, cancelled)
    }

    @Test
    fun `Cancel reports that the user gave up`() {
        val dialog = show()
        fx { dialog.button(ButtonType.CANCEL).fire() }
        assertFalse(fx { dialog.isShowing })
        assertEquals(1, cancelled)
        assertEquals(emptyList(), tried)
    }
}
