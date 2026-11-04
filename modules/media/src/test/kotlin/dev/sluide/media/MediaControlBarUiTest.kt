package dev.sluide.media

import javafx.event.Event
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Slider
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import javafx.util.Duration
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The control bar's wiring needs a scene: events, key routing and disabling (ADR 0123). */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class MediaControlBarUiTest {
    private val playback = FakePlayback()
    private val events = mutableListOf<String>()
    private lateinit var bar: MediaControlBar
    private val view = StackPane()

    @Start
    fun start(stage: Stage) {
        bar = MediaControlBar(playback) { events += "onPlay:" + playback.calls.size }
        bar.install(view)
        view.children += bar.node
        stage.scene = Scene(view, 700.0, 80.0)
        stage.show()
    }

    private fun button(
        robot: FxRobot,
        tip: String,
    ): Button = robot.lookup(".button").queryAllAs(Button::class.java).first { it.tooltip.text.startsWith(tip) }

    private fun key(
        robot: FxRobot,
        code: KeyCode,
    ) = robot.interact {
        val target = view.scene.focusOwner ?: view
        Event.fireEvent(target, KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false))
    }

    private fun disabled(robot: FxRobot): Boolean {
        var result = false
        robot.interact { result = bar.node.isDisable }
        return result
    }

    @Test
    fun `play button calls onPlay then play`(robot: FxRobot) {
        robot.interact { button(robot, "Play").fire() }
        assertEquals(listOf("onPlay:0"), events)
        assertEquals(listOf("play"), playback.calls)
    }

    @Test
    fun `releasing the progress thumb seeks once`(robot: FxRobot) {
        val slider = robot.lookup(".media-progress").queryAs(Slider::class.java)
        robot.interact {
            slider.isValueChanging = true
            slider.value = 90.0
            slider.value = 95.0
            slider.isValueChanging = false
        }
        assertEquals(listOf("seek ${Duration.seconds(95.0)}"), playback.calls)
    }

    @Test
    fun `keys on the installed target reach the playback`(robot: FxRobot) {
        robot.interact { view.requestFocus() }
        key(robot, KeyCode.SPACE)
        key(robot, KeyCode.RIGHT)
        key(robot, KeyCode.M)
        assertEquals(listOf("play", "seek ${Duration.seconds(10.0)}"), playback.calls)
        assertTrue(playback.muted.get())
    }

    @Test
    fun `a slider that gets the focus hands it back, so the arrows still seek`(robot: FxRobot) {
        val volume = robot.lookup(".media-volume").queryAs(Slider::class.java)
        // Like a click on the slider, which requests the focus.
        robot.interact {
            playback.volume.set(0.5)
            volume.requestFocus()
        }
        key(robot, KeyCode.RIGHT)
        assertEquals(listOf("seek ${Duration.seconds(10.0)}"), playback.calls)
        assertEquals(0.5, playback.volume.get())
    }

    @Test
    fun `controls are disabled while loading`(robot: FxRobot) {
        robot.interact { playback.status.set(PlaybackStatus.LOADING) }
        assertTrue(disabled(robot))
        robot.interact { playback.status.set(PlaybackStatus.READY) }
        assertFalse(disabled(robot))
    }
}
