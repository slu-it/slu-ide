package dev.sluide.fileviewers.preview

import dev.sluide.fileviewers.markdown.MarkdownFileViewer
import dev.sluide.fileviewers.press
import dev.sluide.fileviewers.table.CsvFileViewer
import javafx.concurrent.Worker
import javafx.geometry.Orientation
import javafx.geometry.Point2D
import javafx.geometry.VerticalDirection
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ScrollBar
import javafx.scene.control.ToggleButton
import javafx.scene.input.KeyCode
import javafx.scene.layout.HBox
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.fxmisc.richtext.CodeArea
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
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The table of contents of a Markdown view, with real editor and web view. Needs a display, see `uiTest`. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class TableOfContentsUiTest {
    @TempDir
    lateinit var dir: Path

    private val container = StackPane()

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(container, 1000.0, 600.0)
        stage.show()
    }

    private val titles =
        listOf("Alpha" to 1, "Beta" to 2, "Gamma" to 3, "Delta" to 1, "Epsilon" to 2, "Zeta" to 3)

    /** Six sections of mixed levels, each with 40 lines of paragraphs. Returns the text and each heading's line. */
    private fun document(lastBody: Int = 40): Pair<String, List<Int>> {
        val lines = mutableListOf<String>()
        val headingLines = mutableListOf<Int>()
        for ((title, level) in titles) {
            headingLines += lines.size
            lines += "#".repeat(level) + " " + title
            lines += ""
            for (i in 1..(
                if (title ==
                    "Zeta"
                ) {
                    lastBody
                } else {
                    40
                }
            )) {
                lines +=
                    if (i % 2 == 0) "" else "Paragraph $i of section $title with some words in it."
            }
        }
        return lines.joinToString("\n", postfix = "\n") to headingLines
    }

    private fun show(
        robot: FxRobot,
        markdown: String = document().first,
    ): PreviewFileView {
        val file = dir.resolve("README.md").apply { writeText(markdown) }
        return open(robot, file)
    }

    private fun open(
        robot: FxRobot,
        file: Path,
    ): PreviewFileView {
        val view =
            WaitForAsyncUtils.asyncFx<PreviewFileView> { MarkdownFileViewer().open(file) as PreviewFileView }.get()
        robot.interact { container.children.setAll(view.node) }
        awaitLoaded(view)
        return view
    }

    private fun awaitLoaded(view: PreviewFileView) {
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) {
            WaitForAsyncUtils.asyncFx<Boolean> { view.webView.engine.loadWorker.state == Worker.State.SUCCEEDED }.get()
        }
        WaitForAsyncUtils.waitForFxEvents()
    }

    private fun PreviewFileView.script(code: String): Any? =
        WaitForAsyncUtils.asyncFx<Any?> { webView.engine.executeScript(code) }.get()

    private fun PreviewFileView.scrollY(): Double = (script("window.scrollY") as Number).toDouble()

    private fun PreviewFileView.maxScrollY(): Double =
        (script("document.documentElement.scrollHeight - window.innerHeight") as Number).toDouble()

    private fun PreviewFileView.headingTop(line: Int): Double =
        (
            script(
                "document.querySelector('[data-line=\"$line\"]').getBoundingClientRect().top + window.scrollY",
            ) as Number
        ).toDouble()

    /** Waits until the preview is scrolled to the heading at [line], or as far as the page goes. */
    private fun awaitPreviewAt(
        view: PreviewFileView,
        line: Int,
    ) {
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) {
            val y = view.scrollY()
            val top = view.headingTop(line)
            val max = view.maxScrollY()
            // At the end of the page only if the heading cannot reach the top.
            abs(y - top) <= 3.0 || (top > max && abs(y - max) <= 1.0)
        }
    }

    private fun PreviewFileView.setMode(
        robot: FxRobot,
        mode: PreviewMode,
    ) {
        robot.interact { this.mode.set(mode) }
        WaitForAsyncUtils.waitForFxEvents()
    }

    private fun FxRobot.modeButton(mode: PreviewMode) =
        lookup(".preview-mode").queryAllAs(ToggleButton::class.java).single { it.text == mode.label }

    private fun cell(
        view: PreviewFileView,
        text: String,
    ): ListCell<*> =
        view.toc!!
            .list
            .lookupAll(".list-cell")
            .filterIsInstance<ListCell<*>>()
            .single { (it.item as? Heading)?.text == text }

    private fun area(robot: FxRobot) = robot.lookup(".code-area").queryAs(CodeArea::class.java)

    private fun selectedTexts(view: PreviewFileView) =
        view.toc!!
            .list.selectionModel.selectedItem
            ?.text

    @Test
    fun `the button leads the toolbar and the overlay starts hidden, CSV has no table of contents`(robot: FxRobot) {
        val view = show(robot)
        val toc = assertNotNull(view.toc)

        val toolbar = robot.lookup(".preview-toolbar").queryAs(HBox::class.java)
        assertSame(toc.button, toolbar.children.first())
        assertFalse(toc.button.isSelected)
        assertFalse(toc.overlay.isVisible)
        assertFalse(toc.isOpen)

        val csv = dir.resolve("data.csv").apply { writeText("a,b\n1,2\n") }
        val csvView = WaitForAsyncUtils.asyncFx<PreviewFileView> { CsvFileViewer().open(csv) as PreviewFileView }.get()
        assertNull(csvView.toc)
    }

    @Test
    fun `opening selects the section of the caret and focuses the list`(robot: FxRobot) {
        val view = show(robot)
        val toc = view.toc!!
        val (_, lines) = document()
        val area = area(robot)

        robot.interact { area.moveTo(lines[2] + 10, 0) }
        robot.interact { toc.open() }
        WaitForAsyncUtils.waitForFxEvents()

        assertTrue(toc.isOpen)
        assertTrue(toc.overlay.isVisible)
        assertTrue(toc.button.isSelected)
        assertEquals(2, toc.list.selectionModel.selectedIndex)
        assertSame(toc.list, toc.list.scene.focusOwner)
        assertEquals(titles.map { it.first }, toc.list.items.map { it.text })
        assertEquals(titles.map { it.second }, toc.list.items.map { it.level })
        assertEquals(lines, toc.list.items.map { it.line })
        val depths = HeadingOutline.depths(toc.list.items)
        assertEquals(listOf(0, 1, 2, 0, 1, 2), depths)
        // The cells are indented by their depth.
        titles.forEachIndexed { i, (title, _) ->
            val indent = cell(view, title).padding.left
            assertEquals(8.0 + depths[i] * 14.0, indent, 0.5, "indent of $title")
        }
    }

    @Test
    fun `clicking an entry moves both sides to the heading and closes the overlay`(robot: FxRobot) {
        val view = show(robot)
        val toc = view.toc!!
        val (_, lines) = document()
        val area = area(robot)
        val target = lines[4]

        robot.interact { toc.open() }
        WaitForAsyncUtils.waitForFxEvents()
        robot.clickOn(cell(view, "Epsilon"))
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(target, area.currentParagraph)
        assertEquals(0, area.caretColumn)
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS) {
            val first = WaitForAsyncUtils.asyncFx<Int> { area.firstVisibleParToAllParIndex() }.get()
            val last = WaitForAsyncUtils.asyncFx<Int> { area.lastVisibleParToAllParIndex() }.get()
            // Or the editor's end, if the heading is within its last screen of text.
            first == target || (last == area.paragraphs.size - 1 && target >= first)
        }
        awaitPreviewAt(view, target)
        assertFalse(toc.overlay.isVisible)
        assertFalse(toc.button.isSelected)
    }

    @Test
    fun `in preview mode the top line selects, and Enter scrolls the preview to the entry`(robot: FxRobot) {
        val view = show(robot)
        val toc = view.toc!!
        val (_, lines) = document()
        view.setMode(robot, PreviewMode.PREVIEW)

        view.script("sluide.scrollToLine(${lines[3] + 2})")
        WaitForAsyncUtils.waitForFxEvents()
        robot.interact { toc.open() }
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(3, toc.list.selectionModel.selectedIndex)

        robot.interact { toc.list.selectionModel.select(1) }
        robot.type(KeyCode.ENTER)
        WaitForAsyncUtils.waitForFxEvents()

        awaitPreviewAt(view, lines[1])
        assertFalse(toc.isOpen)
        assertEquals(lines[1], view.source.area.currentParagraph)
        assertEquals(0, view.source.area.caretColumn)
    }

    @Test
    fun `Escape, a press outside and the button close the overlay`(robot: FxRobot) {
        val view = show(robot)
        val toc = view.toc!!
        val area = area(robot)

        robot.interact { toc.open() }
        WaitForAsyncUtils.waitForFxEvents()
        robot.type(KeyCode.ESCAPE)
        WaitForAsyncUtils.waitForFxEvents()
        assertFalse(toc.isOpen)
        assertFalse(toc.button.isSelected)
        assertTrue(view.source.area.isFocused, "Escape gives the focus back to the source")

        robot.interact { toc.open() }
        WaitForAsyncUtils.waitForFxEvents()
        val bounds = area.localToScreen(area.boundsInLocal)
        robot.clickOn(Point2D(bounds.maxX - 30, bounds.maxY - 30))
        WaitForAsyncUtils.waitForFxEvents()
        assertFalse(toc.isOpen)
        assertFalse(toc.button.isSelected)

        robot.interact { toc.open() }
        WaitForAsyncUtils.waitForFxEvents()
        robot.interact { toc.button.isSelected = false }
        WaitForAsyncUtils.waitForFxEvents()
        assertFalse(toc.isOpen)

        // The button itself opens it again, with a real click.
        robot.clickOn(toc.button)
        WaitForAsyncUtils.waitForFxEvents()
        assertTrue(toc.isOpen)
        robot.clickOn(toc.button)
        WaitForAsyncUtils.waitForFxEvents()
        assertFalse(toc.isOpen)
    }

    @Test
    fun `an open overlay follows the caret and edits`(robot: FxRobot) {
        val view = show(robot)
        val toc = view.toc!!
        val (_, lines) = document()
        val area = area(robot)

        robot.interact { area.moveTo(lines[0] + 5, 0) }
        robot.interact { toc.open() }
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(0, toc.list.selectionModel.selectedIndex)

        robot.interact { area.moveTo(lines[4] + 5, 0) }
        WaitForAsyncUtils.waitForFxEvents()
        assertTrue(toc.isOpen)
        assertEquals(4, toc.list.selectionModel.selectedIndex)

        robot.interact { area.insertText(0, 0, "# Intro\n\n") }
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(listOf("Intro") + titles.map { it.first }, toc.list.items.map { it.text })
        // The editor puts the caret at the end of the inserted text, in the section of Alpha, now at line 2.
        assertEquals(2, area.currentParagraph)
        assertEquals("Alpha", selectedTexts(view))
        assertEquals(2, toc.list.items[1].line)

        robot.interact { area.moveTo(lines[4] + 2 + 5, 0) }
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals("Epsilon", selectedTexts(view))
    }

    @Test
    fun `a document without headings shows an empty list`(robot: FxRobot) {
        val view = show(robot, "Just some text.\n\nAnd more of it.\n")
        val toc = view.toc!!

        robot.interact { toc.open() }
        WaitForAsyncUtils.waitForFxEvents()

        assertTrue(toc.isOpen)
        assertTrue(toc.list.items.isEmpty())
        assertEquals(-1, toc.list.selectionModel.selectedIndex)
        assertEquals("No headings", (toc.list.placeholder as Label).text)
        robot.type(KeyCode.ENTER)
        WaitForAsyncUtils.waitForFxEvents()
        assertTrue(toc.isOpen, "Enter without an entry does nothing")
        robot.type(KeyCode.ESCAPE)
        WaitForAsyncUtils.waitForFxEvents()
        assertFalse(toc.isOpen)
    }

    private fun reopen(
        robot: FxRobot,
        toc: TableOfContents,
    ) {
        robot.interact {
            toc.close()
            toc.open()
        }
        WaitForAsyncUtils.waitForFxEvents()
    }

    /** Opens the table in preview mode, selects [index] and jumps there with Enter. */
    private fun jump(
        robot: FxRobot,
        toc: TableOfContents,
        index: Int,
    ) {
        robot.interact { toc.open() }
        WaitForAsyncUtils.waitForFxEvents()
        robot.interact { toc.list.selectionModel.select(index) }
        robot.type(KeyCode.ENTER)
        WaitForAsyncUtils.waitForFxEvents()
    }

    @Test
    fun `in preview mode the last heading stays selected though it cannot reach the top`(robot: FxRobot) {
        val (text, lines) = document(lastBody = 2)
        val view = show(robot, text)
        val toc = view.toc!!
        view.setMode(robot, PreviewMode.PREVIEW)

        jump(robot, toc, 5)
        awaitPreviewAt(view, lines[5])
        assertTrue(view.headingTop(lines[5]) > view.maxScrollY(), "Zeta cannot reach the top")
        reopen(robot, toc)
        assertEquals("Zeta", selectedTexts(view))

        view.script("sluide.scrollToLine(${lines[1] + 2})")
        WaitForAsyncUtils.waitForFxEvents()
        reopen(robot, toc)
        assertEquals("Beta", selectedTexts(view))
    }

    @Test
    fun `a jump is forgotten when the file changes on disk`(robot: FxRobot) {
        val (text, lines) = document(lastBody = 2)
        val view = show(robot, text)
        val toc = view.toc!!
        view.setMode(robot, PreviewMode.PREVIEW)
        jump(robot, toc, 5)
        awaitPreviewAt(view, lines[5])

        val intro = "# Intro\n\n" + (1..40).joinToString("\n\n") { "Introductory paragraph $it." } + "\n\n"
        dir.resolve("README.md").writeText(intro + text)
        robot.interact { view.reload() }
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) {
            WaitForAsyncUtils
                .asyncFx<Boolean> {
                    view.webView.engine.loadWorker.state == Worker.State.SUCCEEDED &&
                        view.webView.engine.executeScript("document.querySelectorAll('h1').length") == 3
                }.get()
        }
        // Wait for the scroll position to settle.
        var last = -1.0
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) {
            Thread.sleep(300)
            val y = view.scrollY()
            (y == last).also { last = y }
        }

        robot.interact { toc.open() }
        WaitForAsyncUtils.waitForFxEvents()
        val headings = toc.list.items
        val top = (view.script("sluide.topLine()") as Number).toInt()
        assertEquals(7, headings.size)
        assertEquals(HeadingOutline.sectionAt(headings, top), toc.list.selectionModel.selectedIndex)
    }

    @Test
    fun `a heading in a closed details block does not count in preview mode`(robot: FxRobot) {
        val (text, lines) = document()
        val total = text.lines().size
        val view = show(robot, text + "\n<details>\n<summary>More</summary>\n\n## Hidden\n\nSecret.\n\n</details>\n")
        val toc = view.toc!!
        view.setMode(robot, PreviewMode.PREVIEW)

        view.script("sluide.scrollToLine(${lines[2] + 2})")
        WaitForAsyncUtils.waitForFxEvents()
        robot.interact { toc.open() }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(
            "Hidden",
            toc.list.items
                .last()
                .text,
        )
        assertTrue(
            toc.list.items
                .last()
                .line >= total,
        )
        assertEquals("Gamma", selectedTexts(view))
    }

    @Test
    fun `the first open without headings is tall and wide enough for the placeholder`(robot: FxRobot) {
        val view = show(robot, "Just some text.\n")
        val toc = view.toc!!

        robot.interact { toc.open() }
        WaitForAsyncUtils.waitForFxEvents()

        val placeholder = toc.list.placeholder as Label
        assertTrue(toc.overlay.height >= placeholder.prefHeight(-1.0), "overlay ${toc.overlay.height} is too low")
        assertTrue(toc.list.height >= placeholder.prefHeight(-1.0), "list ${toc.list.height} is too low")
        assertTrue(toc.list.width >= placeholder.prefWidth(-1.0), "list ${toc.list.width} is too narrow")
        assertTrue(placeholder.width >= placeholder.prefWidth(-1.0), "placeholder is clipped: ${placeholder.width}")
        assertTrue(placeholder.height >= placeholder.prefHeight(-1.0), "placeholder is clipped: ${placeholder.height}")
    }

    @Test
    fun `choosing a mode button with the overlay open closes it and the list loses the focus`(robot: FxRobot) {
        val view = show(robot)
        val toc = view.toc!!

        robot.interact { toc.open() }
        WaitForAsyncUtils.waitForFxEvents()
        robot.clickOn(robot.modeButton(PreviewMode.SOURCE))
        WaitForAsyncUtils.waitForFxEvents()

        assertFalse(toc.isOpen)
        assertTrue(toc.list.scene.focusOwner !== toc.list, "the focus is still on the list")
        assertEquals(PreviewMode.SOURCE, view.mode.get())
    }

    private fun visibleScrollBars(
        toc: TableOfContents,
        orientation: Orientation,
    ) = toc.list.lookupAll(".scroll-bar").filterIsInstance<ScrollBar>().filter {
        it.orientation == orientation && it.isVisible
    }

    @Test
    fun `a very long heading does not give the list a horizontal scroll bar`(robot: FxRobot) {
        val long = "A very long heading that goes on and on ".repeat(8).trim()
        val view = show(robot, "# Short\n\nText.\n\n## $long\n\nMore text.\n\n## Another\n")
        val toc = view.toc!!

        robot.interact { toc.open() }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(3, toc.list.items.size)
        assertTrue(visibleScrollBars(toc, Orientation.HORIZONTAL).isEmpty(), "horizontal scroll bar is shown")
    }

    @Test
    fun `five short headings do not give the list a vertical scroll bar`(robot: FxRobot) {
        val view = show(robot, (1..5).joinToString("\n\n") { "## Part $it\n\nText." } + "\n")
        val toc = view.toc!!

        robot.interact { toc.open() }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(5, toc.list.items.size)
        assertTrue(visibleScrollBars(toc, Orientation.VERTICAL).isEmpty(), "vertical scroll bar is shown")
        assertTrue(visibleScrollBars(toc, Orientation.HORIZONTAL).isEmpty(), "horizontal scroll bar is shown")
    }

    @Test
    fun `more headings than fit scroll inside the overlay, down to the last`(robot: FxRobot) {
        val view = show(robot, (1..80).joinToString("\n\n") { "## Part $it\n\nText." } + "\n")
        val toc = view.toc!!

        robot.interact { toc.open() }
        WaitForAsyncUtils.waitForFxEvents()

        val overlay = toc.overlay.localToScene(toc.overlay.layoutBounds)
        val list = toc.list.localToScene(toc.list.layoutBounds)
        assertTrue(overlay.maxY <= container.height, "overlay ends below the view: ${overlay.maxY}")
        assertTrue(list.maxY <= overlay.maxY + 0.5, "list ${list.maxY} ends below its overlay ${overlay.maxY}")
        assertFalse(visibleScrollBars(toc, Orientation.VERTICAL).isEmpty(), "no vertical scroll bar")

        robot.moveTo(toc.list).scroll(40, VerticalDirection.DOWN)
        WaitForAsyncUtils.waitForFxEvents()
        val last =
            robot
                .lookup(".toc-overlay .list-cell")
                .queryAllAs(ListCell::class.java)
                .single { it.isVisible && it.item == toc.list.items.last() }
        assertTrue(last.localToScene(last.layoutBounds).maxY <= list.maxY + 0.5, "the last heading is cut off")
    }
}
