package dev.sluide.previews.table

import dev.sluide.previews.preview.Preview
import dev.sluide.previews.preview.PreviewFileView
import dev.sluide.previews.preview.PreviewFileViewer
import dev.sluide.previews.preview.sourceArea
import javafx.concurrent.Worker
import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.scene.web.WebEngine
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The CSV preview table in a real WebView: header guess, sorting, column search, kept state. Needs a display. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class CsvTableUiTest {
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
        viewer: () -> PreviewFileViewer = { CsvFileViewer() },
    ): PreviewFileView {
        val file = dir.resolve(name).apply { writeText(text) }
        val view = WaitForAsyncUtils.asyncFx<PreviewFileView> { viewer().open(file) as PreviewFileView }.get()
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

    /** The first cells of the rows that are shown as data rows, in table order. */
    private fun PreviewFileView.visible(): String =
        script(
            "(function () { var t = document.querySelector('table.csv-table');" +
                " var h = t.classList.contains('has-header'); var out = [];" +
                " Array.prototype.forEach.call(t.tBodies[0].rows, function (r) {" +
                " if (!r.hidden && !(h && r.classList.contains('csv-first'))) out.push(r.cells[0].textContent); });" +
                " return out.join(); })()",
        ) as String

    /** Whether each shown data row is striped (`csv-odd`), in table order. */
    private fun PreviewFileView.stripes(): String =
        script(
            "(function () { var t = document.querySelector('table.csv-table');" +
                " var h = t.classList.contains('has-header'); var out = [];" +
                " Array.prototype.forEach.call(t.tBodies[0].rows, function (r) {" +
                " if (!r.hidden && !(h && r.classList.contains('csv-first')))" +
                " out.push(r.classList.contains('csv-odd')); });" +
                " return out.join(); })()",
        ) as String

    private fun PreviewFileView.labels() =
        script(
            "Array.prototype.map.call(document.querySelectorAll('tr.csv-labels th'), " +
                "function (h) { return h.textContent; }).join()",
        ) as String

    private fun PreviewFileView.headerChecked() =
        script("document.querySelector('input.csv-header-toggle').checked") as Boolean

    private fun PreviewFileView.count() = script("document.querySelector('.csv-count').textContent") as String

    private fun PreviewFileView.clickLabel(column: Int) {
        script("document.querySelectorAll('tr.csv-labels th')[$column].click()")
    }

    private fun PreviewFileView.sortClasses() =
        script(
            "Array.prototype.map.call(document.querySelectorAll('tr.csv-labels th'), " +
                "function (h) { return h.className; }).join()",
        ) as String

    /** Types [text] into the search field of [column] and waits for the debounced filtering to have run. */
    private fun PreviewFileView.filter(
        column: Int,
        text: String,
    ) = filters(column to text)

    /** Sets several search fields at once, with one input event, and waits for the debounced filtering to have run. */
    private fun PreviewFileView.filters(vararg changes: Pair<Int, String>) {
        val assignments =
            changes.joinToString(" ") { (column, text) ->
                val quoted = text.replace("\\", "\\\\").replace("'", "\\'")
                "inputs[$column].value = '$quoted';"
            }
        script(
            "(function () { var inputs = document.querySelectorAll('input.csv-filter'); $assignments" +
                " inputs[${changes.first().first}].dispatchEvent(new Event('input', { bubbles: true })); })()",
        )
        // The count is set by every run of the filters and is empty without any filter text.
        val anyText =
            script(
                "Array.prototype.some.call(document.querySelectorAll('input.csv-filter'), function (i) { return i.value.trim() !== ''; })",
            ) as Boolean
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS) { count().isNotEmpty() == anyText }
    }

    /** Waits for the debounced filtering to show exactly [expected], then asserts it. */
    private fun PreviewFileView.awaitVisible(expected: String) {
        runCatching { WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS) { visible() == expected } }
        assertEquals(expected, visible())
    }

    private val people = "name,age,city\nBob,7,Oslo\nAda,36,Rome\nCy,150,oslo\nDi,15,Paris\n"

    @Test
    fun `a text header over a number column is guessed and can be switched off`(robot: FxRobot) {
        val view = open(robot, "data.csv", people)

        assertTrue(view.headerChecked())
        assertEquals("name,age,city", view.labels())
        assertEquals("Bob,Ada,Cy,Di", view.visible())

        view.script("document.querySelector('input.csv-header-toggle').click()")

        assertFalse(view.headerChecked())
        assertEquals("A,B,C", view.labels())
        assertEquals("name,Bob,Ada,Cy,Di", view.visible())
    }

    /** The CSV preview with a row limit of three, so a cut-off file needs no thousands of rows. */
    private object SmallLimitPreview : Preview {
        override fun load(
            engine: WebEngine,
            file: Path,
            text: String,
            dark: Boolean,
            root: Path,
        ) = engine.loadContent(CsvRenderer.render(text, ',', dark, maxRows = 3))
    }

    @Test
    fun `the note on a cut-off file counts data rows for the header choice`(robot: FxRobot) {
        val file =
            dir
                .resolve(
                    "big.csv",
                ).apply { writeText((1..6).joinToString("\n", postfix = "\n") { "$it,${it * 2}" }) }
        val view =
            WaitForAsyncUtils
                .asyncFx<PreviewFileView> { PreviewFileView(file, CsvFileViewer().highlighter, SmallLimitPreview) }
                .get()
        robot.interact { container.children.setAll(view.node) }
        awaitLoaded(view)
        val note = { view.script("document.querySelector('.csv-limit').textContent") as String }

        assertFalse(view.headerChecked())
        assertTrue(note().startsWith("Showing the first 4 of 6 rows."), note())

        view.script("document.querySelector('input.csv-header-toggle').click()")

        assertTrue(note().startsWith("Showing the first 3 of 5 rows."), note())
    }

    @Test
    fun `clicking a label sorts ascending, then descending, then back to file order`(robot: FxRobot) {
        val view = open(robot, "data.csv", people)

        view.clickLabel(1)
        assertEquals("Bob,Di,Ada,Cy", view.visible())
        assertEquals(",ascending,", view.sortClasses())
        view.clickLabel(1)
        assertEquals("Cy,Ada,Di,Bob", view.visible())
        assertEquals(",descending,", view.sortClasses())
        view.clickLabel(1)
        assertEquals("Bob,Ada,Cy,Di", view.visible())
        assertEquals(",,", view.sortClasses())
    }

    @Test
    fun `the search fields filter rows by text, comparison and range, all of them together`(robot: FxRobot) {
        val view = open(robot, "data.csv", people)

        assertEquals("false,true,false,true", view.stripes())
        view.filter(2, "OSLO")
        view.awaitVisible("Bob,Cy")
        assertEquals("2 of 4 rows", view.count())
        // Stripes follow the shown rows, not the hidden ones between them.
        assertEquals("false,true", view.stripes())

        view.filters(2 to "", 1 to ">100")
        view.awaitVisible("Cy")
        assertEquals("1 of 4 rows", view.count())

        view.filter(1, "10..20")
        view.awaitVisible("Di")

        view.filters(1 to ">=7", 2 to "oslo")
        view.awaitVisible("Bob,Cy")
        view.filter(1, "<=7")
        view.awaitVisible("Bob")
        assertEquals("1 of 4 rows", view.count())

        view.filters(1 to "", 2 to "")
        assertEquals("", view.count())
        assertEquals("Bob,Ada,Cy,Di", view.visible())
    }

    @Test
    fun `a semicolon file compares numbers with decimal commas`(robot: FxRobot) {
        val view = open(robot, "data.csv", "item;price\na;1,5\nb;2,5\nc;10,25\nd;2,49\n")

        view.filter(1, ">=2,5")

        view.awaitVisible("b,c")
        assertEquals("2 of 4 rows", view.count())
    }

    @Test
    fun `dates in a semicolon file are not numbers to a comparison`(robot: FxRobot) {
        val view = open(robot, "data.csv", "item;when\na;01.02.2024\nb;150\nc;2024-01-02\nd;1.000,5\n")

        view.filter(1, ">100")

        view.awaitVisible("b,d")
        assertEquals("2 of 4 rows", view.count())
    }

    @Test
    fun `header choice, sort and search survive a save`(robot: FxRobot) {
        val view = open(robot, "data.csv", people)
        view.script("document.querySelector('input.csv-header-toggle').click()")
        view.clickLabel(1)
        view.clickLabel(1)
        view.filter(2, "oslo")
        view.awaitVisible("Cy,Bob")
        view.script("window.beforeReload = true")

        robot.interact {
            view.sourceArea.replaceText(0, view.sourceArea.length, people + "Eve,20,Oslo\n")
            view.save()
        }
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) {
            WaitForAsyncUtils
                .asyncFx<Boolean> {
                    view.webView.engine.loadWorker.state == Worker.State.SUCCEEDED &&
                        view.webView.engine.executeScript("window.beforeReload === undefined") == true
                }.get()
        }
        WaitForAsyncUtils.waitForFxEvents()

        assertFalse(view.headerChecked())
        assertEquals(",descending,", view.sortClasses())
        assertEquals("oslo", view.script("document.querySelectorAll('input.csv-filter')[2].value"))
        assertEquals("Cy,Eve,Bob", view.visible())
        assertEquals("3 of 6 rows", view.count())
    }

    @Test
    fun `a cookie jar has no header checkbox and is searchable`(robot: FxRobot) {
        val jar = "# domain\tpath\tname\tvalue\tdate\nlocalhost\t/\tid\tabc\t-1\nexample.com\t/api\ttheme\tdark\t-1\n"
        val view = open(robot, "http-client.cookies", jar, { CookiesFileViewer() })

        assertEquals(false, view.script("!!document.querySelector('input.csv-header-toggle')"))
        assertEquals("domain,path,name,value,date", view.labels())

        view.filter(0, "EXAMPLE")

        view.awaitVisible("example.com")
        assertEquals("1 of 2 rows", view.count())
    }

    @Test
    fun `side by side, a sorted table still scrolls with the source`(robot: FxRobot) {
        // Row i is on source line i.
        val view = open(robot, "data.csv", "name,n\n" + (1..200).joinToString("") { "r$it,$it\n" })

        // Descending by n: the rows are in reverse order.
        view.clickLabel(1)
        view.clickLabel(1)
        assertEquals("r200", view.visible().substringBefore(','))
        robot.interact { view.sourceArea.showParagraphAtTop(150) }
        WaitForAsyncUtils.waitForFxEvents()

        val top = view.script("document.querySelector('[data-line=\"150\"]').getBoundingClientRect().top") as Number
        assertEquals(0.0, top.toDouble(), 5.0)
    }
}
