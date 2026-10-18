package dev.sluide.fileviewers.preview

import dev.sluide.fileviewers.markdown.MarkdownFileViewer
import dev.sluide.fileviewers.markdown.MarkdownHtmlExport
import dev.sluide.fileviewers.markdown.MarkdownRenderer
import dev.sluide.fileviewers.table.CsvFileViewer
import javafx.concurrent.Worker
import javafx.scene.Scene
import javafx.scene.control.MenuButton
import javafx.scene.layout.StackPane
import javafx.scene.web.WebView
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
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Export menu of the Markdown preview. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class ExportUiTest {
    @TempDir
    lateinit var dir: Path

    private val container = StackPane()

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(container, 800.0, 400.0)
        stage.show()
    }

    private fun open(
        robot: FxRobot,
        name: String,
        text: String,
    ): PreviewFileView {
        val file = dir.resolve(name).apply { writeText(text) }
        val viewer = if (name.endsWith(".md")) MarkdownFileViewer() else CsvFileViewer()
        val view = WaitForAsyncUtils.asyncFx<PreviewFileView> { viewer.open(file) as PreviewFileView }.get()
        robot.interact { container.children.setAll(view.node) }
        return view
    }

    private fun exported(view: PreviewFileView): List<String> {
        val reports = mutableListOf<String>()
        val export = checkNotNull(view.export)
        export.report = { reports += it }
        WaitForAsyncUtils.asyncFx<Unit> { export.exportHtml() }.get()
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) {
            WaitForAsyncUtils.asyncFx<Boolean> { !export.button.isDisable }.get()
        }
        WaitForAsyncUtils.waitForFxEvents()
        return reports
    }

    @Test
    fun `only the Markdown preview has the Export button`(robot: FxRobot) {
        val markdown = open(robot, "notes.md", "# Hi")
        assertNotNull(robot.lookup(".preview-export").queryAs(MenuButton::class.java))
        assertEquals(
            listOf("HTML"),
            markdown.export
                ?.button
                ?.items
                ?.map { it.text },
        )
        val csv = open(robot, "data.csv", "a,b\n1,2\n")
        assertNull(csv.export)
        assertTrue(robot.lookup(".preview-export").queryAll<MenuButton>().isEmpty())
    }

    @Test
    fun `exporting writes the page beside the file`(robot: FxRobot) {
        val view = open(robot, "notes.md", "# Hello export\n")
        assertTrue(exported(view).isEmpty())
        assertTrue(dir.resolve("notes.html").readText().contains("Hello export"))
    }

    @Test
    fun `an existing page is only replaced if the user agrees`(robot: FxRobot) {
        val view = open(robot, "notes.md", "# New\n")
        val target = dir.resolve("notes.html").apply { writeText("old") }
        val asked = mutableListOf<Path>()
        val export = checkNotNull(view.export)

        export.confirmOverwrite = {
            asked.add(it)
            false
        }
        exported(view)
        assertEquals(listOf(target), asked)
        assertEquals("old", target.readText())

        export.confirmOverwrite = {
            asked.add(it)
            true
        }
        exported(view)
        assertEquals(2, asked.size)
        assertTrue(target.readText().contains("New"))
    }

    @Test
    fun `images that cannot be embedded are reported`(robot: FxRobot) {
        val view = open(robot, "notes.md", "![x](missing.png)\n")
        val reports = exported(view)
        assertEquals(1, reports.size)
        assertTrue(reports.single().contains("missing.png"))
        assertTrue(dir.resolve("notes.html").exists())
    }

    private fun clickResult(html: String): Any? {
        val view =
            WaitForAsyncUtils
                .asyncFx<WebView> {
                    WebView().apply {
                        container.children.setAll(this)
                        engine.loadContent(html)
                    }
                }.get()
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) {
            WaitForAsyncUtils.asyncFx<Boolean> { view.engine.loadWorker.state == Worker.State.SUCCEEDED }.get()
        }
        return WaitForAsyncUtils
            .asyncFx<Any?> {
                view.engine.executeScript(
                    "var a=document.querySelector('a');var e=new MouseEvent('click',{bubbles:true,cancelable:true});" +
                        "a.dispatchEvent(e);e.defaultPrevented",
                )
            }.get()
    }

    @Test
    fun `links of an exported page navigate, those of the preview do not`() {
        val text = "[x](https://example.invalid/)\n"
        val file = dir.resolve("notes.md")
        val exported = MarkdownHtmlExport().render(file, text).html
        assertEquals(false, clickResult(exported))
        assertEquals(true, clickResult(MarkdownRenderer.render(text, dir)))
    }
}
