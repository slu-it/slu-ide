package dev.sluide.codereview.list

import dev.sluide.codereview.ReviewFileContent
import dev.sluide.codereview.model.ExpandDirection
import dev.sluide.codereview.model.NoticeKind
import dev.sluide.codereview.model.ReviewDocument
import dev.sluide.codereview.model.ReviewLayout
import dev.sluide.codereview.model.ReviewRow
import dev.sluide.codereview.model.modified
import dev.sluide.codereview.model.prepareFile
import dev.sluide.codereview.model.textDiff
import dev.sluide.fileviewers.HighlightSpan
import dev.sluide.fileviewers.LineChange
import dev.sluide.fileviewers.TokenStyle
import javafx.event.Event
import javafx.geometry.Orientation
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.ScrollBar
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.shape.Path
import javafx.scene.text.Text
import javafx.stage.Stage
import org.fxmisc.flowless.VirtualFlow
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The diff list needs a scene: cells are laid out by Flowless, wrap by width and are styled by stylesheets. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class ReviewDiffListUiTest {
    private val document = ReviewDocument()
    private lateinit var view: ReviewDiffList
    private lateinit var scene: Scene

    private val expands = mutableListOf<Triple<String, Int, ExpandDirection>>()
    private val viewedCalls = mutableListOf<Pair<String, Boolean>>()
    private val collapsedCalls = mutableListOf<Pair<String, Boolean>>()
    private val loadDiffs = mutableListOf<String>()

    @Start
    fun start(stage: Stage) {
        // The rows are filled in the tests; the list sees them through the document's own list.
        view = ReviewDiffList(document.rows, document)
        view.onExpand = { path, gap, direction -> expands += Triple(path, gap, direction) }
        view.onViewedChanged = { path, viewed -> viewedCalls += path to viewed }
        view.onCollapsedChanged = { path, collapsed -> collapsedCalls += path to collapsed }
        view.onLoadDiff = { loadDiffs += it }
        scene = Scene(view, 600.0, 400.0)
        stage.scene = scene
        stage.show()
    }

    private fun load(
        vararg files: Pair<String, ReviewFileContent>,
        highlight: Boolean = false,
        layout: ReviewLayout = ReviewLayout.SPLIT,
    ) {
        val highlighter =
            if (highlight) {
                dev.sluide.fileviewers.SyntaxHighlighter { listOf(HighlightSpan(0, 3, TokenStyle.KEYWORD)) }
            } else {
                null
            }
        document.layout.set(layout)
        document.setFiles(files.map { modified(it.first) })
        for ((path, content) in files) {
            document.setContent(
                path,
                prepareFile(modified(path), content, highlighter, false),
            )
        }
    }

    /** A file of about 400 rows: 50 changed lines with 33 hidden lines between them. */
    private fun bigFile() =
        textDiff(
            2000,
            2000,
            *Array(50) {
                LineChange(
                    (5 + 40 * it)..(5 + 40 * it),
                    (5 + 40 * it)..(5 + 40 * it),
                )
            },
        )

    private fun cell(index: Int): Node = assertNotNull(view.rowCell(index), "row $index is not shown")

    private fun flow(): VirtualFlow<*, *> = view.lookup(".virtual-flow") as VirtualFlow<*, *>

    private fun sticky(): Node = view.lookup(".review-sticky")

    private fun horizontalBars() =
        view.lookupAll(".scroll-bar").filterIsInstance<ScrollBar>().filter {
            it.orientation == Orientation.HORIZONTAL && it.isVisible
        }

    private fun Node.buttons(): List<Button> = lookupAll(".button").filterIsInstance<Button>()

    private fun Node.texts(styleClass: String): List<Text> = lookupAll(".$styleClass").filterIsInstance<Text>()

    private fun settle(robot: FxRobot) {
        robot.interact {
            view.applyCss()
            view.layout()
        }
    }

    @ParameterizedTest
    @CsvSource("words,SPLIT", "unbroken,SPLIT", "words,UNIFIED", "unbroken,UNIFIED")
    internal fun `a long line wraps, makes its row taller and adds no horizontal scroll`(
        kind: String,
        layout: ReviewLayout,
        robot: FxRobot,
    ) {
        val long = if (kind == "words") "word ".repeat(400) else "x".repeat(2000)
        robot.interact {
            load(
                "a.txt" to ReviewFileContent.Text("a\nb\nc\n", "a\n$long\nc\n", listOf(LineChange(1..1, 1..1))),
                layout = layout,
            )
        }
        settle(robot)
        val longRow =
            document.rows.indexOfFirst {
                (it is ReviewRow.SplitLine && it.current == 1) ||
                    (it is ReviewRow.UnifiedLine && it.current == 1 && it.original == null)
            }

        val short = cell(1).layoutBounds.height
        val wrapped = cell(longRow).layoutBounds.height
        assertTrue(wrapped > short * 2, "wrapped row $wrapped, short row $short")
        assertTrue(cell(longRow).layoutBounds.width <= scene.width, "the row is wider than the viewport")
        assertEquals(emptyList(), horizontalBars())
        assertTrue(cell(longRow).texts("review-plain").isNotEmpty())
    }

    @Test
    fun `tokens get their style class and a changed word its highlight behind the text`(robot: FxRobot) {
        robot.interact {
            load(
                "a.kt" to ReviewFileContent.Text("foo bar\n", "foo baz\n", listOf(LineChange(0..0, 0..0))),
                highlight = true,
            )
        }
        settle(robot)

        val row = cell(1)
        assertTrue(row.texts("keyword").size >= 2, "one keyword per side")
        val highlights = row.lookupAll(".review-changed-words").filterIsInstance<Path>()
        assertTrue(highlights.isNotEmpty(), "no changed-words layer")
        assertTrue(highlights.all { it.layoutBounds.width > 0.0 && it.layoutBounds.height > 0.0 })
    }

    @Test
    fun `the gap buttons expand in their direction`(robot: FxRobot) {
        robot.interact { load("a.txt" to bigFile()) }
        settle(robot)
        val gapIndex = document.rows.indexOfFirst { it is ReviewRow.Gap && it.hidden > 20 }
        val gap = document.rows[gapIndex] as ReviewRow.Gap

        for (button in cell(gapIndex).buttons()) robot.interact { button.fire() }

        assertEquals(
            listOf(ExpandDirection.UP, ExpandDirection.DOWN, ExpandDirection.ALL),
            expands.map { it.third },
        )
        assertTrue(expands.all { it.first == "a.txt" && it.second == gap.gap })
    }

    @Test
    fun `the chevron and Viewed call their callbacks`(robot: FxRobot) {
        robot.interact { load("a.txt" to bigFile()) }
        settle(robot)

        robot.interact {
            cell(0).lookup(".review-chevron").let { (it as Button).fire() }
            (cell(0).lookup(".review-viewed") as CheckBox).fire()
        }

        assertEquals(listOf("a.txt" to true), collapsedCalls)
        assertEquals(listOf("a.txt" to true), viewedCalls)
    }

    @Test
    fun `Load diff calls onLoadDiff`(robot: FxRobot) {
        robot.interact {
            load("big.txt" to textDiff(600, 600, LineChange(0..599, 0..599)))
        }
        settle(robot)
        val index = document.rows.indexOfFirst { it is ReviewRow.Notice && it.kind == NoticeKind.LOAD_DIFF }

        robot.interact { cell(index).buttons().single().fire() }

        assertEquals(listOf("big.txt"), loadDiffs)
    }

    @Test
    fun `scrollToRow puts the row at the top and firstVisible reports it`(robot: FxRobot) {
        robot.interact { load("a.txt" to bigFile()) }
        settle(robot)

        robot.interact { view.scrollToRow(30) }
        assertEquals(30 to 0.0, view.firstVisible())

        robot.interact { view.scrollToRow(12, -7.0) }
        val (index, offset) = view.firstVisible()
        robot.interact { view.scrollToRow(index, offset) }
        assertEquals(12 to -7.0, index to offset)
        assertEquals(index to offset, view.firstVisible())
    }

    @Test
    fun `the top file follows the scroll and the sticky header shows the file of the first row`(robot: FxRobot) {
        robot.interact { load("a.txt" to bigFile(), "b.txt" to bigFile()) }
        settle(robot)
        assertEquals("a.txt", view.topFile.value)
        assertFalse(sticky().isVisible, "the real header is at the top")

        robot.interact { view.scrollToRow(10) }
        assertEquals("a.txt", view.topFile.value)
        assertTrue(sticky().isVisible)
        assertEquals("a.txt", (sticky().lookup(".review-header-path") as javafx.scene.control.Label).text)

        val second = document.headerRow("b.txt")
        robot.interact { flow().scrollYBy(10_000.0) }
        assertEquals("b.txt", view.topFile.value)
        assertEquals("b.txt", (sticky().lookup(".review-header-path") as javafx.scene.control.Label).text)

        robot.interact { view.scrollToRow(second) }
        assertEquals("b.txt", view.topFile.value)
        assertFalse(sticky().isVisible, "the real header is at the top again")
    }

    @Test
    fun `a change of the rows of a file below keeps the viewport`(robot: FxRobot) {
        robot.interact { load("a.txt" to bigFile(), "b.txt" to bigFile()) }
        settle(robot)
        robot.interact { view.scrollToRow(14, 5.0) }
        val before = view.firstVisible()
        val gap =
            document.rows
                .filterIsInstance<ReviewRow.Gap>()
                .first { it.path == "b.txt" && it.hidden > 20 }
                .gap

        robot.interact { document.expand("b.txt", gap, ExpandDirection.ALL) }

        assertEquals(before, view.firstVisible())
    }

    @Test
    fun `an update of the file with the first visible row keeps the viewport`(robot: FxRobot) {
        robot.interact { load("a.txt" to bigFile(), "b.txt" to bigFile()) }
        settle(robot)
        robot.interact { view.scrollToRow(60, -5.0) }
        val before = view.firstVisible()
        val anchor = document.anchorAt(before.first)
        val gap =
            document.rows
                .filterIndexed { index, row -> index > 70 && row is ReviewRow.Gap && row.hidden > 20 }
                .first() as ReviewRow.Gap

        robot.interact { document.expand(gap.path, gap.gap, ExpandDirection.ALL) }

        assertEquals(before, view.firstVisible())
        assertEquals(anchor, document.anchorAt(view.firstVisible().first))
    }

    @ParameterizedTest
    @EnumSource(ExpandDirection::class, names = ["DOWN", "ALL"])
    internal fun `expanding the gap below the first visible line keeps its row and offset`(
        direction: ExpandDirection,
        robot: FxRobot,
    ) {
        robot.interact { load("a.txt" to bigFile(), "b.txt" to bigFile()) }
        settle(robot)
        robot.interact { view.scrollToRow(66) }
        val key = rowKey(document.rows[66])
        // The hunk header of the gap before the line grows with the gap below: both rows change.
        val gap =
            document.rows
                .filterIndexed { index, row -> index > 66 && row is ReviewRow.Gap && row.hidden > 20 }
                .first() as ReviewRow.Gap

        robot.interact { document.expand(gap.path, gap.gap, direction) }

        val (index, offset) = view.firstVisible()
        assertEquals(key, rowKey(document.rows[index]))
        assertEquals(0.0, offset)
    }

    @Test
    fun `rows arriving while the list is at the top keep the first row at offset 0`(robot: FxRobot) {
        robot.interact { document.setFiles(listOf(modified("a.txt"), modified("b.txt"))) }
        settle(robot)
        assertEquals(0 to 0.0, view.firstVisible())

        robot.interact {
            for (path in listOf("a.txt", "b.txt")) {
                document.setContent(path, prepareFile(modified(path), bigFile(), null, false))
            }
        }

        assertEquals(0 to 0.0, view.firstVisible())
        assertEquals("a.txt", view.topFile.value)
    }

    @Test
    fun `a scroll of the app followed by a change of the rows before the next layout is kept`(robot: FxRobot) {
        robot.interact { load("a.txt" to bigFile(), "b.txt" to bigFile()) }
        settle(robot)
        // Laid out further down, so that the change is above the position the list last saw.
        robot.interact { view.scrollToRow(60) }
        val key = rowKey(document.rows[40])

        robot.interact {
            view.scrollToRow(40)
            document.expand("a.txt", 0, ExpandDirection.ALL)
        }

        val (index, offset) = view.firstVisible()
        assertEquals(key, rowKey(document.rows[index]))
        assertEquals(0.0, offset)
    }

    @Test
    fun `a scroll of the user followed by a change of the rows before the next layout is kept`(robot: FxRobot) {
        robot.interact { load("a.txt" to bigFile(), "b.txt" to bigFile()) }
        settle(robot)
        robot.interact { flow().scrollYBy(508.0) }
        val middle = view.firstVisible().first
        val key = rowKey(document.rows[middle])
        robot.interact { view.scrollToRow(0) }

        robot.interact {
            flow().scrollYBy(508.0)
            document.expand("a.txt", 0, ExpandDirection.ALL)
        }

        val index = view.firstVisible().first
        assertEquals(key, rowKey(document.rows[index]))
    }

    @Test
    fun `firstVisible reports the requested position right after scrollToRow and reveal`(robot: FxRobot) {
        robot.interact { load("a.txt" to bigFile(), "b.txt" to bigFile()) }
        settle(robot)
        var scrolled: Pair<Int, Double>? = null
        var revealed: Pair<Int, Double>? = null

        robot.interact {
            view.scrollToRow(45, 6.0)
            scrolled = view.firstVisible()
        }
        robot.interact {
            view.reveal(30)
            revealed = view.firstVisible()
        }

        assertEquals(45 to 6.0, scrolled)
        assertEquals(30, revealed?.first)
        assertEquals(sticky().layoutBounds.height, revealed?.second ?: -1.0, 0.5)
    }

    @Test
    fun `a row too near the end to reach its offset reports the real position once laid out`(robot: FxRobot) {
        robot.interact { load("a.txt" to bigFile()) }
        settle(robot)
        val last = document.rows.lastIndex

        robot.interact { view.scrollToRow(last, 0.0) }

        assertTrue(view.firstVisible().first < last, "the last row cannot be at the top: ${view.firstVisible()}")
        assertNotNull(view.rowCell(last))
    }

    @Test
    fun `an update above does not move the top file`(robot: FxRobot) {
        robot.interact { load("a.txt" to bigFile(), "b.txt" to bigFile()) }
        settle(robot)
        robot.interact { view.scrollToRow(document.headerRow("b.txt") + 10) }
        val seen = mutableListOf<String?>()
        view.topFile.addListener { _, _, value -> seen += value }
        val gap = document.rows.filterIsInstance<ReviewRow.Gap>().first { it.path == "a.txt" && it.hidden > 20 }

        robot.interact { document.expand("a.txt", gap.gap, ExpandDirection.ALL) }

        assertEquals("b.txt", view.topFile.value)
        assertEquals(emptyList(), seen)
    }

    @Test
    fun `reveal leaves a row fully below the sticky header`(robot: FxRobot) {
        robot.interact { load("a.txt" to bigFile(), "b.txt" to bigFile()) }
        settle(robot)
        val row = 30

        robot.interact { view.reveal(row) }

        assertTrue(sticky().isVisible)
        val stickyBottom = sticky().localToScene(sticky().layoutBounds).maxY
        val rowTop = cell(row).localToScene(cell(row).layoutBounds).minY
        assertTrue(rowTop >= stickyBottom - 0.01, "row top $rowTop is under the sticky header ending at $stickyBottom")

        val header = document.headerRow("b.txt")
        robot.interact { view.reveal(header) }
        assertEquals(header to 0.0, view.firstVisible())
    }

    @Test
    fun `the sticky header is as wide as the list`(robot: FxRobot) {
        robot.interact { load("a.txt" to bigFile()) }
        settle(robot)
        robot.interact { view.scrollToRow(10) }

        assertEquals(flow().width, sticky().layoutBounds.width, 0.5)
    }

    @Test
    fun `a click in the list focuses it`(robot: FxRobot) {
        robot.interact { load("a.txt" to bigFile()) }
        settle(robot)
        assertFalse(scene.focusOwner === view)

        robot.interact {
            Event.fireEvent(
                view,
                MouseEvent(
                    MouseEvent.MOUSE_PRESSED,
                    5.0,
                    5.0,
                    5.0,
                    5.0,
                    MouseButton.PRIMARY,
                    1,
                    false,
                    false,
                    false,
                    false,
                    true,
                    false,
                    false,
                    false,
                    false,
                    true,
                    null,
                ),
            )
        }

        assertTrue(scene.focusOwner === view)
    }

    @Test
    fun `no rows have no top file`(robot: FxRobot) {
        robot.interact { document.setFiles(emptyList()) }

        assertEquals(null, view.topFile.value)
        assertTrue((view as Parent).isFocusTraversable)
    }
}
