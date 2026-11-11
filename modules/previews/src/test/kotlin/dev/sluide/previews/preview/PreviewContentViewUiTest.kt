package dev.sluide.previews.preview

import dev.sluide.fileviewers.FileContent
import dev.sluide.previews.markdown.MarkdownFileViewer
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
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The preview view of content that is not on disk (a [FileContent]). Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class PreviewContentViewUiTest {
    @TempDir
    lateinit var dir: Path

    private val container = StackPane()

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(container, 800.0, 400.0)
        stage.show()
    }

    private fun <T> fx(block: () -> T): T = WaitForAsyncUtils.asyncFx<T> { block() }.get()

    private fun awaitLoaded(view: PreviewFileView) {
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) {
            fx { view.webView.engine.loadWorker.state == Worker.State.SUCCEEDED }
        }
        WaitForAsyncUtils.waitForFxEvents()
    }

    private fun PreviewFileView.script(code: String): Any? = fx { webView.engine.executeScript(code) }

    /** Content in memory that counts writes, as a decrypted file would be. */
    private class MemoryContent(
        var text: String,
        override val confidential: Boolean = false,
    ) : FileContent {
        var writes = 0
        var reads = 0

        override fun read(): ByteArray {
            reads++
            return text.toByteArray()
        }

        override fun write(bytes: ByteArray) {
            writes++
            text = String(bytes)
        }
    }

    private fun showContent(
        robot: FxRobot,
        content: MemoryContent,
    ): Pair<Path, PreviewFileView> {
        // The virtual path does not exist.
        val file = dir.resolve("vault/notes.md")
        val viewer = MarkdownFileViewer()
        assertTrue(viewer.opensContent(file.fileName.toString()))
        val view = WaitForAsyncUtils.asyncFx<PreviewFileView> { viewer.open(file, content) as PreviewFileView }.get()
        robot.interact { container.children.setAll(view.node) }
        awaitLoaded(view)
        return file to view
    }

    @Test
    fun `a preview of content shows and saves the content, never the virtual file`(robot: FxRobot) {
        val content = MemoryContent("# Secret *title*\n")
        val (file, view) = showContent(robot, content)

        assertEquals("Secret title", view.script("document.querySelector('h1').textContent"))
        assertNotNull(view.export, "not confidential: Export is offered")

        robot.interact { view.sourceArea.replaceText(2, 8, "Other") }
        robot.interact { view.save() }
        assertEquals(1, content.writes)
        assertEquals("# Other *title*\n", content.text)
        assertFalse(file.exists(), "the virtual file is never created")
        assertFalse(file.parent.exists(), "nor its folder")
    }

    @Test
    fun `confidential content has no Export control`(robot: FxRobot) {
        val (_, view) = showContent(robot, MemoryContent("# Secret\n", confidential = true))

        assertNull(view.export)
    }

    @Test
    fun `wipe empties the source, the table of contents and the rendered preview, also before and after dispose`(
        robot: FxRobot,
    ) {
        val content = MemoryContent("# Secret\n\nSECRET-BODY pin 4711\n", confidential = true)
        val (_, view) = showContent(robot, content)
        val toc = checkNotNull(view.toc)
        assertEquals("Secret", view.script("document.querySelector('h1').textContent"))
        // The pools of the FX thread, where the view rendered.
        assertTrue(fx { parserCacheContents() }.any { "SECRET-BODY" in it }, "the parser's pools hold the text")
        robot.interact { toc.open() }
        assertEquals(1, fx { toc.list.items.size })

        robot.interact { view.wipe() }
        robot.interact { view.wipe() }
        assertEquals("", fx { view.source.text })
        assertEquals("", fx { view.source.savedText })
        assertEquals(0, fx { toc.list.items.size })
        assertFalse(fx { toc.isOpen })
        assertTrue(fx { parserCacheContents() }.none { "SECRET-BODY" in it || "4711" in it }, "the pools are scrubbed")
        awaitNoHeading(view)

        // A wiped view neither reads the content again nor saves.
        val reads = content.reads
        robot.interact { view.reload() }
        robot.interact { view.save() }
        assertEquals(reads, content.reads)
        assertEquals(0, content.writes)
        assertEquals("", fx { view.source.text })
        awaitNoHeading(view)

        robot.interact { view.dispose() }
        robot.interact { view.wipe() }
    }

    private fun awaitNoHeading(view: PreviewFileView) {
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) {
            fx {
                view.webView.engine.document
                    ?.getElementsByTagName("h1")
                    ?.length ?: 0
            } == 0
        }
    }
}
