package dev.sluide.barcodes.ui

import dev.sluide.barcodes.payload.WifiSecurity
import javafx.scene.Node
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.ChoiceBox
import javafx.scene.control.Dialog
import javafx.scene.control.PasswordField
import javafx.scene.control.TextField
import javafx.scene.control.ToggleButton
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.kordamp.ikonli.javafx.FontIcon
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The controls of the template dialogs write into their forms and keep Insert's state. Built but not shown (showing
 * blocks); the controls need the toolkit, not a visible stage. Needs a display.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class TemplateDialogsUiTest {
    @Start
    fun start(stage: Stage) = Unit

    private fun <T> fx(block: () -> T): T = WaitForAsyncUtils.asyncFx(block).get()

    private fun <T : Node> Dialog<ButtonType>.control(id: String): T {
        @Suppress("UNCHECKED_CAST")
        return checkNotNull(dialogPane.content.lookup("#$id")) as T
    }

    private fun Dialog<ButtonType>.insertDisabled() = dialogPane.lookupButton(insert()).isDisable

    private fun Dialog<ButtonType>.insert() = dialogPane.buttonTypes.single { it.text == "Insert" }

    @Test
    fun `the Wi-Fi controls write into the form and Insert needs a network`(robot: FxRobot) {
        val form = WifiForm()
        val dialog = fx { TemplateDialogs.wifiDialog(null, form) }
        assertEquals("Wi-Fi Template", fx { dialog.title })
        assertTrue(fx { dialog.insertDisabled() })

        robot.interact {
            dialog.control<TextField>("barcodes-wifi-network").text = "Home"
            dialog.control<PasswordField>("barcodes-wifi-password").text = "pw"
            dialog.control<CheckBox>("barcodes-wifi-hidden").isSelected = true
            dialog.control<ChoiceBox<WifiSecurity>>("barcodes-wifi-security").value = WifiSecurity.WEP
        }

        assertEquals("Home", form.ssid)
        assertEquals("pw", form.password)
        assertTrue(form.hidden)
        assertEquals(WifiSecurity.WEP, form.security)
        assertFalse(fx { dialog.insertDisabled() })

        robot.interact { dialog.control<TextField>("barcodes-wifi-network").text = "" }
        assertTrue(fx { dialog.insertDisabled() })
    }

    @Test
    fun `the password row is disabled for None and the password shows in plain text only after the toggle`(
        robot: FxRobot,
    ) {
        val dialog = fx { TemplateDialogs.wifiDialog(null, WifiForm()) }
        val row = fx { dialog.control<Node>("barcodes-wifi-password-row") }
        val password = fx { dialog.control<PasswordField>("barcodes-wifi-password") }
        val shown = fx { dialog.control<TextField>("barcodes-wifi-password-shown") }
        val toggle = fx { dialog.control<ToggleButton>("barcodes-wifi-password-toggle") }
        assertFalse(fx { row.isDisable })

        robot.interact { dialog.control<ChoiceBox<WifiSecurity>>("barcodes-wifi-security").value = WifiSecurity.NONE }
        assertTrue(fx { row.isDisable })
        robot.interact { dialog.control<ChoiceBox<WifiSecurity>>("barcodes-wifi-security").value = WifiSecurity.WPA }
        assertFalse(fx { row.isDisable })

        robot.interact { password.text = "secret" }
        assertTrue(fx { password.isVisible && !shown.isVisible })
        assertEquals("Show", fx { toggle.tooltip.text })

        robot.interact { toggle.isSelected = true }
        assertTrue(fx { shown.isVisible && !password.isVisible })
        assertEquals("secret", fx { shown.text })
        assertEquals("Hide", fx { toggle.tooltip.text })
        assertEquals("mdi2e-eye-off", fx { (toggle.graphic as FontIcon).iconLiteral })
    }

    @Test
    fun `the Contact controls write into the form and Insert needs one filled field`(robot: FxRobot) {
        val form = ContactForm()
        val dialog = fx { TemplateDialogs.contactDialog(null, form) }
        assertEquals("Contact Template", fx { dialog.title })
        assertTrue(fx { dialog.insertDisabled() })

        robot.interact { dialog.control<TextField>("barcodes-contact-email").text = "ada@example.com" }

        assertEquals("ada@example.com", form.email)
        assertFalse(fx { dialog.insertDisabled() })
        robot.interact { dialog.control<TextField>("barcodes-contact-email").text = "" }
        assertTrue(fx { dialog.insertDisabled() })
    }
}
