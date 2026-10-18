package dev.sluide.fileviewers.preview

import dev.sluide.fileviewers.image.SvgFileViewer
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
import javax.imageio.ImageIO
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Export menus of the Markdown and SVG previews. Needs a display, see the `uiTest` Gradle task. */
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
        val viewer =
            when (name.substringAfterLast('.')) {
                "md" -> MarkdownFileViewer()
                "svg" -> SvgFileViewer()
                else -> CsvFileViewer()
            }
        val view = WaitForAsyncUtils.asyncFx<PreviewFileView> { viewer.open(file) as PreviewFileView }.get()
        robot.interact { container.children.setAll(view.node) }
        return view
    }

    private fun exported(
        view: PreviewFileView,
        label: String = "HTML",
    ): List<String> {
        val reports = mutableListOf<String>()
        val export = checkNotNull(view.export)
        export.report = { reports += it }
        val item = export.button.items.single { it.text == label }
        WaitForAsyncUtils.asyncFx<Unit> { item.fire() }.get()
        WaitForAsyncUtils.waitFor(30, TimeUnit.SECONDS) {
            WaitForAsyncUtils.asyncFx<Boolean> { !export.button.isDisable }.get()
        }
        WaitForAsyncUtils.waitForFxEvents()
        return reports
    }

    @Test
    fun `only the Markdown and SVG previews have the Export button`(robot: FxRobot) {
        val markdown = open(robot, "notes.md", "# Hi")
        assertNotNull(robot.lookup(".preview-export").queryAs(MenuButton::class.java))
        assertEquals(
            listOf("HTML"),
            markdown.export
                ?.button
                ?.items
                ?.map { it.text },
        )
        val svg = open(robot, "logo.svg", LOGO)
        assertNotNull(robot.lookup(".preview-export").queryAs(MenuButton::class.java))
        assertEquals(
            listOf("PNG 100%", "PNG 200%", "PNG 300%"),
            svg.export
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

    @Test
    fun `exporting an SVG as PNG writes it beside the file at the chosen size`(robot: FxRobot) {
        val view = open(robot, "logo.svg", LOGO)
        assertTrue(exported(view, "PNG 200%").isEmpty())
        val image = assertNotNull(ImageIO.read(dir.resolve("logo@2x.png").toFile()))
        assertEquals(80 to 40, image.width to image.height)
        assertEquals(0xffff0000.toInt(), image.getRGB(10, 10))
        assertEquals(0, image.getRGB(70, 10) ushr 24)
    }

    @Test
    fun `an existing PNG is only replaced if the user agrees`(robot: FxRobot) {
        val view = open(robot, "logo.svg", LOGO)
        val target = dir.resolve("logo.png").apply { writeText("old") }
        val export = checkNotNull(view.export)
        export.confirmOverwrite = { false }
        exported(view, "PNG 100%")
        assertEquals("old", target.readText())

        export.confirmOverwrite = { true }
        assertTrue(exported(view, "PNG 100%").isEmpty())
        val image = assertNotNull(ImageIO.read(target.toFile()))
        assertEquals(40 to 20, image.width to image.height)
    }

    @Test
    fun `an SVG that cannot be drawn is reported`(robot: FxRobot) {
        val view = open(robot, "broken.svg", "not an image")
        assertEquals(listOf("Could not export broken.svg: Not an SVG image"), exported(view, "PNG 300%"))
        assertFalse(dir.resolve("broken@3x.png").exists())
    }

    private companion object {
        /** 40 × 20, the left half red, the right half transparent. */
        const val LOGO =
            "<svg xmlns='http://www.w3.org/2000/svg' width='40' height='20'><rect width='20' height='20' fill='red'/></svg>"
    }
}
