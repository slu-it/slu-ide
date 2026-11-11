package dev.sluide.encryptedfiles.ui

import javafx.event.EventType
import javafx.scene.Scene
import javafx.scene.control.TextInputControl
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.Pane
import javafx.stage.Stage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The idle lock counts wall-clock time, so the time a computer slept counts although the timers stood still: the view's
 * wiring of key presses and the window's focus into [IdleLock] (its rules are tested in `IdleLockTest`).
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class EncryptedFileIdleUiTest {
    @TempDir
    lateinit var dir: Path

    private val views = EncryptedViewFixture()
    private lateinit var stage: Stage

    @Start
    fun start(stage: Stage) {
        this.stage = stage
        stage.scene = Scene(views.container, 900.0, 400.0)
        stage.show()
    }

    @AfterEach
    fun close() = views.close()

    @Test
    fun `a key press after the computer slept past the idle time saves and locks, and the key goes nowhere`(
        robot: FxRobot,
    ) {
        val file = views.create(dir.resolve("notes.txt.sluenc"), "one\n")
        val time = FakeIdleTime()
        views.settings.idleLockMinutes.set(1)
        val view = views.open(file, robot, time)
        views.unlock(view, robot)
        val area = views.fx { views.area(view) }
        var reached = false
        robot.interact {
            area.insertText(0, "edited ")
            area.addEventHandler(KeyEvent.KEY_PRESSED) { reached = true }
        }
        time.sleep(Duration.ofMinutes(5))

        robot.interact { area.fireEvent(activityEvent(Activity.KEY)) }

        assertFalse(views.fx { view.isUnlocked })
        assertFalse(reached)
        assertEquals("edited one\n", views.decrypt(file))

        // The press's typed character goes to the password field, which now has the focus: it is dropped, and
        // typing after the key's release reaches the field again.
        val field = views.fx { view.node.lookup(".encrypted-password") as TextInputControl }
        robot.interact { field.fireEvent(key(KeyEvent.KEY_TYPED, "a")) }
        assertEquals("", views.fx { field.text })
        robot.interact {
            field.fireEvent(key(KeyEvent.KEY_RELEASED, ""))
            field.fireEvent(key(KeyEvent.KEY_TYPED, "b"))
        }
        assertEquals("b", views.fx { field.text })
        robot.interact { view.dispose() }
    }

    private fun key(
        type: EventType<KeyEvent>,
        character: String,
    ) = KeyEvent(type, character, "", KeyCode.UNDEFINED, false, false, false, false)

    @Test
    fun `the window getting the focus back after the computer slept past the idle time locks`(robot: FxRobot) {
        val file = views.create(dir.resolve("notes.txt.sluenc"), "one\n")
        val time = FakeIdleTime()
        views.settings.idleLockMinutes.set(1)
        val view = views.open(file, robot, time)
        views.unlock(view, robot)
        val other = views.fx { Stage().apply { scene = Scene(Pane(), 100.0, 100.0) } }
        try {
            robot.interact {
                other.show()
                other.requestFocus()
            }
            views.awaitFx { !stage.isFocused }
            time.sleep(Duration.ofMinutes(5))

            robot.interact { stage.requestFocus() }

            views.awaitFx { !view.isUnlocked }
        } finally {
            robot.interact {
                other.hide()
                view.dispose()
            }
        }
    }
}
