package dev.sluide.diagnostics

import javafx.animation.AnimationTimer
import javafx.scene.Scene
import javafx.scene.layout.Pane
import javafx.scene.shape.Rectangle
import javafx.stage.Stage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Needs a scene: pulses only run for shown windows. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class JavaFxProbeUiTest {
    private val testPulses = AtomicInteger()
    private lateinit var animation: AnimationTimer

    @Start
    fun start(stage: Stage) {
        val box = Rectangle(10.0, 10.0)
        animation =
            object : AnimationTimer() {
                override fun handle(now: Long) {
                    box.x = (now / 1_000_000 % 100).toDouble()
                }
            }
        stage.title = "Probe test"
        stage.scene =
            Scene(Pane(box), 200.0, 100.0).apply { addPostLayoutPulseListener { testPulses.incrementAndGet() } }
        stage.show()
        animation.start()
    }

    @AfterEach
    fun stopAnimation(robot: FxRobot) {
        robot.interact { animation.stop() }
    }

    @Test
    fun `counts the pulses of a shown window and answers pings`() {
        val probe = JavaFxProbe()

        probe.begin()
        WaitForAsyncUtils.waitForFxEvents()
        val counted = testPulses.get()
        val deadline = System.nanoTime() + 10_000_000_000
        while (testPulses.get() < counted + 5 && System.nanoTime() < deadline) {
            probe.ping()
            Thread.sleep(50)
        }
        WaitForAsyncUtils.waitForFxEvents()
        val result = probe.end()

        val window = result.windows.single { it.label == "Probe test" }
        assertTrue(window.pulses >= 5, "pulses: $result")
        assertTrue(result.latencies.isNotEmpty())
        assertEquals(0, result.unanswered)

        // The counters are gone once the JavaFX thread has run the removal: later pulses are not counted.
        WaitForAsyncUtils.waitForFxEvents()
        val afterEnd = probe.countedPulses()
        val later = testPulses.get()
        val until = System.nanoTime() + 10_000_000_000
        while (testPulses.get() < later + 5 && System.nanoTime() < until) Thread.sleep(20)
        assertTrue(testPulses.get() >= later + 5, "the animation goes on")
        assertEquals(afterEnd, probe.countedPulses())
    }
}
