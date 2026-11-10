package dev.sluide.encryptedfiles.settings

import dev.sluide.settings.SettingsStore
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.input.KeyCode
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The real Encrypted Files page: its spinner reaches the draft of the model and `load` the spinner, and typed text
 * that is no number is ignored; the model's rules are in [EncryptedFilesSettingsTest]. Needs a display.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class EncryptedFilesSettingsPageUiTest {
    @TempDir
    lateinit var dir: Path

    private val settings by lazy { EncryptedFilesSettings(SettingsStore(dir.resolve("settings.properties"))) }
    private val page by lazy { EncryptedFilesSettingsPage(settings) }

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(page.node as Parent, 500.0, 200.0)
        stage.show()
    }

    @Test
    fun `the spinner shows the setting, edits the draft, and apply stores it`(robot: FxRobot) {
        robot.interact { page.load() }
        assertEquals(10, page.spinner.value)

        robot.interact { page.spinner.valueFactory.value = 3 }
        assertEquals(3, page.model.idleLockMinutes.get())
        assertEquals(10, settings.currentIdleLockMinutes(), "a draft changes nothing")
        robot.interact { page.apply() }
        assertEquals(3, settings.currentIdleLockMinutes())

        robot.interact { page.spinner.valueFactory.value = 7 }
        robot.interact { page.load() }
        assertEquals(3, page.spinner.value, "load drops the unapplied edit")
    }

    @Test
    fun `typed text counts on Enter, text that is no number keeps the value`(robot: FxRobot) {
        robot.interact { page.spinner.editor.text = "25" }
        assertEquals("25", page.spinner.editor.text, "typing writes nothing back")
        robot.interact { page.spinner.editor.fireEvent(enter()) }
        assertEquals(25, page.model.idleLockMinutes.get())

        robot.interact { page.spinner.editor.text = "abc" }
        robot.interact { page.spinner.editor.fireEvent(enter()) }

        assertEquals(25, page.model.idleLockMinutes.get())
        assertEquals("25", page.spinner.editor.text)
    }

    private fun enter() =
        javafx.scene.input.KeyEvent(
            javafx.scene.input.KeyEvent.KEY_PRESSED,
            "",
            "",
            KeyCode.ENTER,
            false,
            false,
            false,
            false,
        )
}
