package dev.sluide.fileviewers

import javafx.application.ColorScheme
import javafx.concurrent.Worker
import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Mermaid files with a real WebView and the bundled Mermaid. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class MermaidPreviewUiTest {
    @TempDir
    lateinit var dir: Path

    private val container = StackPane()

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(container, 800.0, 400.0)
        stage.show()
    }

    private fun show(
        robot: FxRobot,
        source: String,
    ): Pair<Path, PreviewFileView> {
        val file = dir.resolve("flow.mmd").apply { writeText(source) }
        val view =
            WaitForAsyncUtils
                .asyncFx<PreviewFileView> {
                    MermaidFileViewer().open(
                        file,
                    ) as PreviewFileView
                }.get()
        robot.interact { container.children.setAll(view.node) }
        awaitLoaded(view)
        return file to view
    }

    private fun awaitLoaded(view: PreviewFileView) {
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) {
            WaitForAsyncUtils.asyncFx<Boolean> { view.webView.engine.loadWorker.state == Worker.State.SUCCEEDED }.get()
        }
        WaitForAsyncUtils.waitForFxEvents()
    }

    private fun PreviewFileView.script(code: String): Any? =
        WaitForAsyncUtils.asyncFx<Any?> { webView.engine.executeScript(code) }.get()

    private fun PreviewFileView.awaitTrue(condition: String) =
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) { script(condition) == true }

    private val drawn = "document.querySelectorAll('pre.mermaid svg').length === 1"
    private val failed = "document.querySelectorAll('pre.mermaid.error').length === 1"

    @Test
    fun `a Mermaid file opens side by side and draws its diagram, again in the other colour scheme`(robot: FxRobot) {
        val (_, view) = show(robot, "flowchart LR\n  Start --> Stop\n")

        assertEquals(PreviewMode.SPLIT, view.mode.get())
        view.awaitTrue(drawn)
        assertTrue((view.script("document.querySelector('pre.mermaid svg').textContent") as String).contains("Start"))

        robot.interact { container.scene.preferences.colorScheme = ColorScheme.DARK }
        view.awaitTrue("document.body.classList.contains('dark')")
        view.awaitTrue(drawn)
        assertTrue((view.script("document.querySelector('pre.mermaid svg').textContent") as String).contains("Stop"))
    }

    @Test
    fun `a diagram that does not parse shows Mermaid's error, and the diagram once it is fixed`(robot: FxRobot) {
        val (file, view) = show(robot, "flowchart LR\n  Start -->\n")

        view.awaitTrue(failed)
        val message = view.script("document.querySelector('pre.mermaid').textContent") as String
        assertTrue("Parse error on line" in message, message)
        assertEquals(0, (view.script("document.querySelectorAll('pre.mermaid svg').length") as Number).toInt())

        // A theme change draws it again, with the error again.
        robot.interact { container.scene.preferences.colorScheme = ColorScheme.DARK }
        view.awaitTrue("document.body.classList.contains('dark')")
        view.awaitTrue(failed)

        file.writeText("flowchart LR\n  Start --> Stop\n")
        robot.interact { view.reload() }
        awaitLoaded(view)
        view.awaitTrue(drawn)
        assertEquals(false, view.script("document.querySelector('pre.mermaid').classList.contains('error')"))
    }

    @Test
    fun `an unknown diagram type is an error too, an empty file is none`(robot: FxRobot) {
        val (file, view) = show(robot, "flowchat LR\n  A --> B\n")

        view.awaitTrue(failed)
        assertTrue((view.script("document.querySelector('pre.mermaid').textContent") as String).isNotBlank())

        file.writeText("")
        robot.interact { view.reload() }
        awaitLoaded(view)
        WaitForAsyncUtils.sleep(500, TimeUnit.MILLISECONDS)
        assertEquals(false, view.script("document.querySelector('pre.mermaid').classList.contains('error')"))
    }
}
