package dev.sluide.docker.compose

import javafx.stage.Stage
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.time.Duration.ofSeconds
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

/**
 * [FxScheduler] runs its task on the FX thread after the delay, in the order of the due times, and closing the result
 * cancels it. A UI test because [javafx.animation.PauseTransition] needs the toolkit and its pulse.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class FxSchedulerUiTest {
    @Start
    fun start(stage: Stage) {
        // The extension only has to start the toolkit.
    }

    @Test
    fun `tasks run on the FX thread in the order of their delays`(robot: FxRobot) {
        val order = CopyOnWriteArrayList<String>()
        val threads = CopyOnWriteArrayList<Boolean>()
        val done = CountDownLatch(2)

        robot.interact {
            FxScheduler.schedule(300.milliseconds) {
                order += "late"
                threads += javafx.application.Platform.isFxApplicationThread()
                done.countDown()
            }
            FxScheduler.schedule(20.milliseconds) {
                order += "early"
                threads += javafx.application.Platform.isFxApplicationThread()
                done.countDown()
            }
        }

        assertTimeoutPreemptively(ofSeconds(30)) { done.await() }
        assertEquals(listOf("early", "late"), order)
        assertEquals(listOf(true, true), threads)
    }

    @Test
    fun `a closed task does not run`(robot: FxRobot) {
        val order = CopyOnWriteArrayList<String>()
        val sentinel = CountDownLatch(1)

        robot.interact {
            FxScheduler.schedule(20.milliseconds) { order += "closed" }.close()
            FxScheduler.schedule(200.milliseconds) {
                order += "sentinel"
                sentinel.countDown()
            }
        }

        assertTimeoutPreemptively(ofSeconds(30)) { sentinel.await() }
        assertEquals(listOf("sentinel"), order, "the sentinel is due after the closed task would have been")
    }
}
