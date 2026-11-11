package dev.sluide.speech.ui

import javafx.scene.Scene
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.MenuButton
import javafx.scene.layout.HBox
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.util.concurrent.Callable
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The embedded toolbar in a row that is too short: only the status gives way. Needs a scene, as the squeezing is
 * layout (an HBox shrinks its children to their minimum width).
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class ReadAloudToolbarUiTest {
    private val host = FakeHost().also { it.onFx = javafx.application.Platform::runLater }
    private lateinit var toolbar: ReadAloudToolbar
    private val row = StackPane()

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(row, 700.0, 100.0)
        stage.show()
    }

    private fun <T> fx(block: () -> T): T = WaitForAsyncUtils.asyncFx(Callable { block() }).get()

    @Test
    fun `a long status in a short row is cut, the pickers keep their width`() {
        fx {
            toolbar = ReadAloudToolbar(ReadAloudModel(host), {}, embedded = true)
            row.children.setAll(HBox(toolbar.node).apply { maxWidth = 360.0 })
            val status = toolbar.node.lookup("#speech-status") as Label
            status.text = "No audio output: No line matching interface SourceDataLine supporting format PCM_FLOAT"
            row.applyCss()
            row.layout()
        }
        fx {
            val controls =
                listOf("#speech-accent", "#speech-gender", "#speech-speed").map { toolbar.node.lookup(it) }
            assertTrue(controls[0] is ComboBox<*> && controls[2] is MenuButton)
            for (control in controls) {
                val region = control as javafx.scene.layout.Region
                val pref = region.prefWidth(-1.0)
                assertTrue(region.width >= pref - 0.5, "${control.id} is ${region.width} wide, wants $pref")
            }
            val status = toolbar.node.lookup("#speech-status") as Label
            assertTrue(status.width < status.prefWidth(-1.0), "the status is cut")
        }
    }
}
