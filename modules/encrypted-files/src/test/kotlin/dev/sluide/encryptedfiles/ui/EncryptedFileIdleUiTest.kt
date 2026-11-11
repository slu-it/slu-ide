package dev.sluide.encryptedfiles.ui

import javafx.event.Event
import javafx.event.EventType
import javafx.scene.Scene
import javafx.scene.control.TextInputControl
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyEvent
import javafx.scene.layout.Pane
import javafx.stage.Stage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The idle lock of an encrypted file's tab: the view's wiring of the setting, key presses, mouse presses, scrolling and
 * the window's focus into [IdleLock] (its rules are tested in `IdleLockTest`). It counts wall-clock time, so the time
 * a computer slept counts although the timers stood still.
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

    /** An unlocked view of a new file with "one\n", whose idle time [time] drives, at [minutes] idle. */
    private fun openUnlocked(
        robot: FxRobot,
        time: FakeIdleTime,
        minutes: Int = 1,
    ): Pair<Path, EncryptedFileView> {
        val file = views.create(dir.resolve("notes.txt.sluenc"), "one\n")
        views.settings.idleLockMinutes.set(minutes)
        val view = views.open(file, robot, time)
        views.unlock(view, robot)
        return file to view
    }

    @ParameterizedTest
    @EnumSource(Activity::class)
    fun `idle time saves unsaved changes and locks, and a key press, mouse press or scroll moves the deadline`(
        activity: Activity,
        robot: FxRobot,
    ) {
        val time = FakeIdleTime()
        val (file, view) = openUnlocked(robot, time)
        robot.interact { views.area(view).insertText(0, "edited ") }

        // 59 s idle, then the activity: at 61 s the timer finds only 2 s of idle time.
        robot.interact { time.advance(Duration.ofSeconds(59)) }
        robot.interact { view.node.fireEvent(activityEvent(activity)) }
        robot.interact { time.advance(Duration.ofSeconds(2)) }
        assertTrue(views.fx { view.isUnlocked })

        robot.interact { time.advance(Duration.ofSeconds(60)) }
        assertFalse(views.fx { view.isUnlocked })
        assertEquals("edited one\n", views.decrypt(file))
        robot.interact { view.dispose() }
    }

    @Test
    fun `a changed idle setting applies to a tab that is open, and 0 turns it off`(robot: FxRobot) {
        val time = FakeIdleTime()
        val (_, view) = openUnlocked(robot, time, minutes = 5)

        robot.interact { views.settings.idleLockMinutes.set(1) }
        robot.interact { time.advance(Duration.ofMinutes(1)) }
        assertFalse(views.fx { view.isUnlocked })

        views.unlock(view, robot)
        robot.interact { views.settings.idleLockMinutes.set(0) }
        robot.interact { time.advance(Duration.ofMinutes(100)) }
        assertTrue(views.fx { view.isUnlocked })
        robot.interact { view.dispose() }
    }

    @Test
    fun `the idle lock locks a clean tab although the changed-elsewhere warning is still up`(robot: FxRobot) {
        val time = FakeIdleTime()
        val (file, view) = openUnlocked(robot, time)
        robot.interact { views.area(view).insertText(0, "mine ") }
        views.changePasswordElsewhere(file, "another long password")
        robot.interact { view.reload() }
        assertTrue(views.fx { view.session.warningFailure != null })
        // Undoing the edit makes the tab clean again; the warning stays until the file reads again.
        robot.interact { views.area(view).undo() }
        assertFalse(views.fx { view.isDirty })

        robot.interact { time.advance(Duration.ofMinutes(1)) }

        assertFalse(views.fx { view.isUnlocked })
        robot.interact { view.dispose() }
    }

    @Test
    fun `a key press after the computer slept past the idle time saves and locks, and the key goes nowhere`(
        robot: FxRobot,
    ) {
        val time = FakeIdleTime()
        val (file, view) = openUnlocked(robot, time)
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
        val time = FakeIdleTime()
        val (_, view) = openUnlocked(robot, time)
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

    @Test
    fun `Lock All by a key with Alt types nothing into the password field that takes the focus`(robot: FxRobot) {
        val (_, view) = openUnlocked(robot, FakeIdleTime())
        val accelerator =
            KeyCodeCombination(
                KeyCode.L,
                KeyCombination.CONTROL_DOWN,
                KeyCombination.ALT_DOWN,
                KeyCombination.SHIFT_DOWN,
            )
        val scene = stage.scene
        robot.interact {
            scene.accelerators[accelerator] = Runnable { views.registry.lockAll(null) }
            views.area(view).requestFocus()
        }

        // On Linux and Windows JavaFX types the accelerator's character (Ctrl+Alt counts as AltGr) into the new focus.
        fun send(event: KeyEvent) = robot.interact { Event.fireEvent(scene.focusOwner, event) }
        send(KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.L, true, true, true, false))
        views.awaitFx { !view.isUnlocked }
        val field = views.fx { view.node.lookup(".encrypted-password") as TextInputControl }
        views.awaitFx { field.isFocused }
        send(KeyEvent(KeyEvent.KEY_TYPED, "L", "", KeyCode.UNDEFINED, true, true, true, false))
        send(KeyEvent(KeyEvent.KEY_RELEASED, "", "", KeyCode.L, true, true, true, false))

        assertEquals("", views.fx { field.text })
        send(key(KeyEvent.KEY_TYPED, "p"))
        assertEquals("p", views.fx { field.text }, "typing works again once the key is up")
        robot.interact { view.dispose() }
    }
}
