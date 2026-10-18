package dev.sluide.fileviewers.pem

import dev.sluide.fileviewers.preview.PreviewFileView
import dev.sluide.fileviewers.preview.PreviewMode
import javafx.concurrent.Worker
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.SplitPane
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

/** A certificate chain in a real [PreviewFileView] of [PemFileViewer]. Needs a display (`uiTest`). */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class PemPreviewUiTest {
    @TempDir
    lateinit var dir: Path

    private val container = StackPane()

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(container, 800.0, 400.0)
        stage.show()
    }

    @Test
    fun `a PEM file opens on the details of its certificates`(robot: FxRobot) {
        val chain = checkNotNull(PemPreviewUiTest::class.java.getResource("test-only-chain.pem")).readText()
        val file = dir.resolve("chain.crt").apply { writeText(chain) }
        val view = WaitForAsyncUtils.asyncFx<PreviewFileView> { PemFileViewer().open(file) as PreviewFileView }.get()
        robot.interact { container.children.setAll(view.node) }
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) {
            WaitForAsyncUtils.asyncFx<Boolean> { view.webView.engine.loadWorker.state == Worker.State.SUCCEEDED }.get()
        }

        assertEquals(PreviewMode.PREVIEW, view.mode.get())
        assertEquals<List<Node>>(
            listOf(view.previewPane),
            robot.lookup(".split-pane").queryAs(SplitPane::class.java).items,
        )
        val titles =
            WaitForAsyncUtils
                .asyncFx<Any?> {
                    view.webView.engine.executeScript(
                        // Without the badge, which depends on today's date.
                        "Array.from(document.querySelectorAll('h2')).map(h => h.firstChild.textContent).join('|')",
                    )
                }.get()
        assertEquals("Certificate 1 of 2: leaf.sluide.test |Certificate 2 of 2: SluIde Test CA ", titles)
        assertTrue("keyword" in view.source.area.getStyleOfChar(1), "BEGIN line highlighted")
    }
}
