package dev.sluide.ide.logging

import dev.sluide.ide.SluIdeApplication
import javafx.application.Platform
import javafx.scene.control.Button
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/** The default handler [SluIdeApplication] installs ([logUncaught]) relies on JavaFX handing it event handler exceptions. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class UncaughtExceptionsUiTest {
    @Start
    fun start(stage: Stage) {
        stage.show()
    }

    @Test
    fun `exceptions in event handlers reach the default uncaught exception handler`() {
        val caught = CompletableFuture<Pair<Thread, Throwable>>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error -> caught.complete(thread to error) }
        try {
            Platform.runLater {
                Button().apply { setOnAction { throw IllegalStateException("from the handler") } }.fire()
            }
            val (thread, error) = caught.get(10, TimeUnit.SECONDS)
            assertEquals("JavaFX Application Thread", thread.name)
            assertEquals("from the handler", error.message)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }
}
