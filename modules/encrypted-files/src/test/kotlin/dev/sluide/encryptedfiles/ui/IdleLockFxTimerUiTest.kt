package dev.sluide.encryptedfiles.ui

import javafx.application.Platform
import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The real timer and clock of the idle lock (the FX animation timer), which [IdleLockTest] replaces by a fake. Needs a
 * display: a `PauseTransition` runs on the toolkit's pulse.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class IdleLockFxTimerUiTest {
    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(StackPane(), 100.0, 100.0)
        stage.show()
    }

    @Test
    fun `the timer runs its task once after the delay, on the FX thread`(robot: FxRobot) {
        val ran = CountDownLatch(1)
        val runs = AtomicInteger()
        val threads = mutableListOf<Boolean>()
        var started = 0L
        var elapsedMillis = 0L

        robot.interact {
            started = System.nanoTime()
            fxScheduler(Duration.ofMillis(500)) {
                elapsedMillis = (System.nanoTime() - started) / 1_000_000
                threads += Platform.isFxApplicationThread()
                runs.incrementAndGet()
                ran.countDown()
            }
        }

        assertTrue(ran.await(20, TimeUnit.SECONDS))
        assertEquals(listOf(true), threads)
        // the pulse that ran it is over; a task that ran again would have run on the next ones, so let a few pass
        robot.interact { }
        robot.interact { }
        assertEquals(1, runs.get())
        assertTrue(elapsedMillis >= 400, "ran after $elapsedMillis ms")
    }

    @Test
    fun `a cancelled timer does not run`(robot: FxRobot) {
        val ran = CountDownLatch(1)
        val longer = CountDownLatch(1)

        robot.interact {
            fxScheduler(Duration.ofMillis(200)) { ran.countDown() }.cancel()
            fxScheduler(Duration.ofMillis(600)) { longer.countDown() }
        }

        // the later timer ended, so the cancelled earlier one had its chance
        assertTrue(longer.await(20, TimeUnit.SECONDS))
        assertFalse(ran.await(0, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `a lock with the default timer and clock fires after its period`(robot: FxRobot) {
        val fired = CountDownLatch(1)
        val lock = IdleLock({ 1 }, { fired.countDown() }, unit = Duration.ofMillis(300))

        robot.interact { lock.start() }

        assertTrue(fired.await(20, TimeUnit.SECONDS))
        robot.interact { lock.stop() }
    }
}
