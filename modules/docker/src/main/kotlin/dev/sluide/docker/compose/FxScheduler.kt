package dev.sluide.docker.compose

import dev.sluide.docker.events.Scheduler
import javafx.animation.PauseTransition
import kotlin.time.Duration

/** A [Scheduler] on the FX thread's animation timer: call it on the FX thread; the task runs there too. */
internal object FxScheduler : Scheduler {
    override fun schedule(
        delay: Duration,
        task: Runnable,
    ): AutoCloseable {
        val pause = PauseTransition(javafx.util.Duration.millis(delay.inWholeMilliseconds.toDouble()))
        pause.setOnFinished { task.run() }
        pause.play()
        return AutoCloseable { pause.stop() }
    }
}
