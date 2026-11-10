package dev.sluide.previews.preview

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.fileviewers.WindowServices
import dev.sluide.fileviewers.WrappingToolbar
import dev.sluide.previews.image.CustomSvgPngExport
import dev.sluide.previews.image.SvgFileViewer
import dev.sluide.previews.image.SvgMeasurer
import dev.sluide.previews.markdown.MarkdownFileViewer
import dev.sluide.previews.table.CsvFileViewer
import javafx.scene.Node
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
import java.io.IOException
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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

    private fun <T> fx(block: () -> T): T = WaitForAsyncUtils.asyncFx<T> { block() }.get()

    private fun toolbarChildren(robot: FxRobot) =
        fx {
            robot
                .lookup(".preview-toolbar")
                .query<WrappingToolbar>()
                .let { it.leading + it.trailing }
        }

    @Test
    fun `Export is right of the TOC button and goes in Source`(robot: FxRobot) {
        val view = open(robot, "notes.md", "# Hi")
        val children = toolbarChildren(robot)
        val export = robot.lookup(".preview-export").query<MenuButton>()
        assertTrue(children.indexOf(robot.lookup(".toc-button").query<Node>()) < children.indexOf(export))
        robot.interact { view.mode.value = PreviewMode.SOURCE }
        assertTrue(fx { !export.isVisible && !export.isManaged }, "hidden in Source")
        robot.interact { view.mode.value = PreviewMode.PREVIEW }
        assertTrue(fx { export.isVisible && export.isManaged })
    }

    @Test
    fun `an SVG's Export follows its zoom controls, directly before the mode toggles`(robot: FxRobot) {
        open(robot, "logo.svg", LOGO)
        val children = toolbarChildren(robot)
        val export = children.indexOf(robot.lookup(".preview-export").query<Node>())
        assertTrue(children.indexOf(robot.lookup(".zoom-controls").query<Node>()) < export)
        assertEquals(children.indexOf(robot.lookup(".preview-mode").query<Node>()) - 1, export)
    }

    @Test
    fun `the Export button is as high as its neighbours, with a gap to the control after it`(robot: FxRobot) {
        open(robot, "notes.md", "# Hi")
        assertFits(robot, robot.lookup(".toc-button").query(), robot.lookup(".zoom-controls").query())
        open(robot, "logo.svg", LOGO)
        assertFits(robot, robot.lookup(".zoom-button").query(), robot.lookup(".preview-mode").query())
    }

    /** Asserts that the Export button is as high as [sameHeight] and has a gap before [after]. */
    private fun assertFits(
        robot: FxRobot,
        sameHeight: Node,
        after: Node,
    ) {
        WaitForAsyncUtils.waitForFxEvents()
        val export = robot.lookup(".preview-export").query<MenuButton>()
        fx {
            assertEquals(sameHeight.boundsInParent.height, export.boundsInParent.height, 0.5)
            assertTrue(after.boundsInParent.minX - export.boundsInParent.maxX >= 6.0, "a gap after Export")
        }
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
    fun `images that cannot be embedded and diagrams that cannot be drawn are reported`(robot: FxRobot) {
        val view = open(robot, "notes.md", "![x](missing.png)\n\n```mermaid\nflowchart LR\n  A -->\n```\n")
        val report = exported(view).single()
        assertTrue(report.startsWith("Exported notes.html, with problems:\n"), report)
        assertTrue("Image not embedded: missing.png" in report, report)
        assertTrue("Diagram 1 could not be drawn: " in report, report)
        // Mermaid's message has several lines; each problem starts its own, once.
        assertEquals(1, Regex("Image not embedded").findAll(report).count(), report)
        assertEquals(1, Regex("could not be drawn").findAll(report).count(), report)
        assertEquals(
            listOf("Exported notes.html, with problems:", "Image not embedded: missing.png"),
            report.lines().take(2),
        )
        assertTrue(dir.resolve("notes.html").exists())
        assertNotNull(Jsoup.parse(dir.resolve("notes.html").readText()).selectFirst("pre.mermaid.error"))
    }

    @Test
    fun `an export is a background task of the window`(robot: FxRobot) {
        val lines = CopyOnWriteArrayList<String>()
        val tasks = BackgroundTasks.forTests { lines += it.replace(Regex("task#\\d+"), "task") }
        val view = open(robot, "logo.svg", """<svg xmlns="http://www.w3.org/2000/svg" width="4" height="4"/>""")
        val host =
            object : FileViewHost {
                override fun openFile(file: Path) = false

                override fun openWebPage(uri: URI) {}

                private val services = WindowServices().apply { register(BackgroundTasks::class.java, tasks) }

                override fun <T : Any> windowService(type: Class<T>): T? = services.get(type)
            }
        robot.interact { view.host = host }

        assertTrue(exported(view, "PNG 200%").isEmpty())

        assertEquals("task started: Exporting logo.svg as PNG 200%", lines.first())
        assertTrue(lines[1].startsWith("task finished in "), lines[1])
        assertEquals(2, lines.size, lines.toString())
    }

    /** An export that fails with [failure], beside the file. */
    private class FailingExport(
        private val failure: Exception,
    ) : FileExport {
        override val label = "Broken"

        override fun target(file: Path): Path = file.resolveSibling("out.txt")

        override fun write(
            file: Path,
            text: String,
            target: Path,
        ): List<String> = throw failure
    }

    /** Runs [fileExport] through an [ExportControls] with a window's tasks; returns the task lines and the report. */
    private fun failing(fileExport: FileExport): Triple<List<String>, List<String>, ExportControls> {
        val lines = CopyOnWriteArrayList<String>()
        val reports = CopyOnWriteArrayList<String>()
        val tasks = BackgroundTasks.forTests { lines += it.replace(Regex("task#\\d+"), "task") }
        val file = dir.resolve("a.txt").apply { writeText("a") }
        val controls =
            WaitForAsyncUtils
                .asyncFx<ExportControls> {
                    ExportControls(listOf(fileExport), { file }, { "a" }, { null }, { tasks }).apply {
                        report =
                            { reports += it }
                    }
                }.get()
        WaitForAsyncUtils.asyncFx<Unit> { controls.export(fileExport) }.get()
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) {
            WaitForAsyncUtils.asyncFx<Boolean> { !controls.button.isDisable && reports.isNotEmpty() }.get()
        }
        WaitForAsyncUtils.waitForFxEvents()
        return Triple(lines.toList(), reports.toList(), controls)
    }

    @Test
    fun `a file problem fails the task with its reason, tells the user and enables the button again`() {
        val (lines, reports, controls) = failing(FailingExport(IOException("disk full")))

        assertEquals("task started: Exporting a.txt as Broken", lines.first())
        assertTrue(lines.last().startsWith("task failed after "), lines.toString())
        assertTrue(lines.last().endsWith(": Exporting a.txt as Broken: disk full"), lines.toString())
        assertEquals(listOf("Could not export a.txt: disk full"), reports)
        assertFalse(WaitForAsyncUtils.asyncFx<Boolean> { controls.button.isDisable }.get())
    }

    @Test
    fun `any other exception fails the task with the exception, tells the user and enables the button again`() {
        val (lines, reports, controls) = failing(FailingExport(IllegalStateException("bug")))

        assertTrue(lines.last().startsWith("task failed after "), lines.toString())
        assertTrue(lines.last().endsWith(": Exporting a.txt as Broken"), lines.toString())
        assertEquals(listOf("Could not export a.txt: bug"), reports)
        assertFalse(WaitForAsyncUtils.asyncFx<Boolean> { controls.button.isDisable }.get())
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

    /** Runs [custom] through an [ExportControls]; returns the reports and whether the button is enabled again. */
    private fun chosen(
        custom: CustomSvgPngExport,
        file: Path,
    ): Pair<List<String>, ExportControls> {
        val reports = CopyOnWriteArrayList<String>()
        val controls =
            fx {
                ExportControls(listOf(custom), { file }, { "" }, { null }, { null }).apply {
                    report = { reports += it }
                    confirmOverwrite = { true }
                }
            }
        fx { controls.export(custom) }
        // The button is disabled from the call on, so that this does not return before the choice is made.
        WaitForAsyncUtils.waitFor(30, TimeUnit.SECONDS) { !fx { controls.button.isDisable } }
        WaitForAsyncUtils.waitForFxEvents()
        return reports.toList() to controls
    }

    @Test
    fun `a custom size is asked for after measuring and written as name-WxH`(robot: FxRobot) {
        open(robot, "logo.svg", LOGO)
        val file = dir.resolve("logo.svg")
        val asked = CopyOnWriteArrayList<Pair<Int, Int>>()
        val custom = CustomSvgPngExport()
        custom.ask = { _, natural ->
            asked += natural
            32 to 16
        }
        assertEquals(emptyList(), chosen(custom, file).first)
        assertEquals(listOf(40 to 20), asked)
        val image = assertNotNull(ImageIO.read(dir.resolve("logo-32x16.png").toFile()))
        assertEquals(32 to 16, image.width to image.height)

        // Cancel: nothing is written or reported.
        val before = dir.listDirectoryEntries().toSet()
        custom.ask = { _, _ -> null }
        assertEquals(emptyList(), chosen(custom, file).first)
        assertEquals(before, dir.listDirectoryEntries().toSet())
    }

    @Test
    fun `a failing measurement is reported and the button is enabled again`(robot: FxRobot) {
        open(robot, "logo.svg", LOGO)
        val custom = CustomSvgPngExport(measurer = SvgMeasurer { throw IOException("Not an SVG image") })
        custom.ask = { _, _ -> error("not asked") }
        val (reports, controls) = chosen(custom, dir.resolve("logo.svg"))
        assertEquals(listOf("Could not export logo.svg: Not an SVG image"), reports)
        assertFalse(fx { controls.button.isDisable })
    }

    private companion object {
        /** 40 × 20, the left half red, the right half transparent. */
        const val LOGO =
            "<svg xmlns='http://www.w3.org/2000/svg' width='40' height='20'><rect width='20' height='20' fill='red'/></svg>"
    }
}
