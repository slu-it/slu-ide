package dev.sluide.fileviewers.preview

import dev.sluide.fileviewers.markdown.MarkdownFileViewer
import dev.sluide.fileviewers.table.CsvFileViewer
import javafx.scene.Scene
import javafx.scene.control.MenuButton
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.jsoup.Jsoup
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
        WaitForAsyncUtils.waitFor(30, TimeUnit.SECONDS) {
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
        assertEquals(listOf("Exported notes.html, with problems:\nImage not embedded: missing.png"), reports)
        assertTrue(dir.resolve("notes.html").exists())
    }

    @Test
    fun `a diagram is drawn into the page in both variants, without libraries`(robot: FxRobot) {
        val view = open(robot, "notes.md", "# Flow\n\n```mermaid\nflowchart LR\n  A --> B\n```\n")
        assertTrue(exported(view).isEmpty())
        val html = dir.resolve("notes.html").readText()
        assertTrue(html.length < 200_000, "${html.length}")
        val doc = Jsoup.parse(html)
        assertTrue(
            doc
                .select("script")
                .single()
                .data()
                .contains("matchMedia("),
        )
        val diagram = doc.select("div.diagram").single()
        assertNotNull(diagram.selectFirst("> div.diagram-light > svg"))
        assertNotNull(diagram.selectFirst("> div.diagram-dark > svg"))
    }

    @Test
    fun `a diagram that cannot be drawn is reported`(robot: FxRobot) {
        val view = open(robot, "notes.md", "```mermaid\nflowchart LR\n  A -->\n```\n")
        val report = exported(view).single()
        assertTrue(report.startsWith("Exported notes.html, with problems:\nDiagram 1 could not be drawn: "), report)
        assertNotNull(Jsoup.parse(dir.resolve("notes.html").readText()).selectFirst("pre.mermaid.error"))
    }
}
