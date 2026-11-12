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
import javafx.geometry.Orientation
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.ScrollBar
import javafx.scene.shape.Path
import javafx.scene.text.Text
import javafx.stage.Stage
import org.fxmisc.flowless.VirtualFlow
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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
    ) {
        val highlighter =
            if (highlight) {
                dev.sluide.fileviewers.SyntaxHighlighter { listOf(HighlightSpan(0, 3, TokenStyle.KEYWORD)) }
            } else {
                null
            }
        document.layout.set(ReviewLayout.SPLIT)
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
        robot.interact { view.requestLayout() }
    }

    @ParameterizedTest
    @ValueSource(strings = ["word ", "x"])
    fun `a long line wraps, makes its row taller and adds no horizontal scroll`(
        piece: String,
        robot: FxRobot,
    ) {
        val long = piece.repeat(if (piece == "x") 2000 else 400)
        robot.interact {
            load("a.txt" to ReviewFileContent.Text("a\nb\nc\n", "a\n$long\nc\n", listOf(LineChange(1..1, 1..1))))
        }
        settle(robot)

        val short = cell(1).layoutBounds.height
        val wrapped = cell(2).layoutBounds.height
        assertTrue(wrapped > short * 2, "wrapped row $wrapped, short row $short")
        assertTrue(cell(2).layoutBounds.width <= scene.width, "the row is wider than the viewport")
        assertEquals(emptyList(), horizontalBars())
        assertTrue(cell(2).texts("review-plain").isNotEmpty())
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
    fun `no rows have no top file`(robot: FxRobot) {
        robot.interact { document.setFiles(emptyList()) }

        assertEquals(null, view.topFile.value)
        assertTrue((view as Parent).isFocusTraversable)
    }
}
