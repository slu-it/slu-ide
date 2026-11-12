package dev.sluide.codereview.window

import dev.sluide.codereview.list.ReviewDiffList
import dev.sluide.codereview.model.ExpandDirection
import dev.sluide.codereview.model.ReviewDocument
import dev.sluide.codereview.model.ReviewRow
import dev.sluide.codereview.model.modified
import dev.sluide.codereview.model.prepareFile
import dev.sluide.codereview.model.textDiff
import dev.sluide.fileviewers.LineChange
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.scene.shape.Path
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The find bar's queued re-search and its reveal while typing, with a real document and list: they need the cells
 * (what is visible, what is painted). The jobs the bar queues are collected and run by the test.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class ReviewFindBarUiTest {
    private val document = ReviewDocument()
    private lateinit var list: ReviewDiffList
    private lateinit var bar: ReviewFindBar
    private lateinit var root: VBox
    private val queued = mutableListOf<() -> Unit>()

    @Start
    fun start(stage: Stage) {
        list = ReviewDiffList(document.rows, document)
        val slot = VBox()
        root = VBox(slot, list).apply { VBox.setVgrow(list, Priority.ALWAYS) }
        bar = ReviewFindBar(document, list, slot) { queued += it }
        // Lines 10, 150 and 180 of 200 changed ("c10", "c150", "c180").
        val content =
            textDiff(
                200,
                200,
                LineChange(10..10, 10..10),
                LineChange(150..150, 150..150),
                LineChange(180..180, 180..180),
            )
        document.setFiles(listOf(modified("a.kt")))
        document.setContent("a.kt", prepareFile(content, null, false))
        stage.scene = Scene(root, 800.0, 200.0)
        stage.show()
    }

    private fun <T> fx(block: () -> T): T = WaitForAsyncUtils.asyncFx<T>(block).get()

    private fun settle() {
        repeat(2) {
            fx {
                root.applyCss()
                root.layout()
            }
            WaitForAsyncUtils.waitForFxEvents()
        }
    }

    private fun rowOf(current: Int) =
        fx { document.rows.indexOfFirst { it is ReviewRow.SplitLine && it.current == current } }

    private fun matchPaths(): List<Path> =
        fx {
            document.rows.indices
                .mapNotNull { list.rowCell(it) as? Parent }
                .flatMap { it.lookupAll(".review-find-match").filterIsInstance<Path>() }
        }

    private fun open(query: String) {
        fx {
            bar.open()
            bar.query.text = query
        }
        settle()
    }

    @Test
    fun `several changes of the rows queue one search`() {
        open("o")

        fx {
            document.expand("a.kt", 0, ExpandDirection.ALL)
            document.expand("a.kt", 1, ExpandDirection.ALL)
        }

        assertEquals(1, queued.size)
        // Once run, the next change queues again.
        fx { queued.removeFirst()() }
        fx { document.setCollapsed("a.kt", true) }
        assertEquals(1, queued.size)
    }

    @Test
    fun `a queued search that runs after the bar was closed does nothing`() {
        open("o")
        val counter = fx { bar.counter.text }
        assertTrue(matchPaths().isNotEmpty(), "the matches are painted")
        fx { document.expand("a.kt", 0, ExpandDirection.ALL) }
        assertEquals(1, queued.size)

        fx { bar.close() }
        fx { queued.removeFirst()() }
        settle()

        assertTrue(matchPaths().isEmpty(), "no marks come back")
        assertEquals(counter, fx { bar.counter.text })
    }

    @Test
    fun `typing reveals a match that is not shown`() {
        fx { bar.open() }
        val row = rowOf(180)
        assertFalse(fx { list.isFullyVisible(row) })

        fx { bar.query.text = "c18" }
        settle()

        assertTrue(fx { list.isFullyVisible(row) }, "the match is revealed")
    }

    @Test
    fun `typing reveals a match covered by the sticky header`() {
        fx { list.scrollToRow(rowOf(10)) }
        settle()
        assertFalse(fx { list.isFullyVisible(rowOf(10)) }, "the sticky header covers the row")
        fx { bar.open() }

        fx { bar.query.text = "c10" }
        settle()

        assertTrue(fx { list.isFullyVisible(rowOf(10)) })
    }

    @Test
    fun `typing reveals a match cut off at the bottom of the viewport`() {
        // Rows above, so that the row can be placed near the bottom edge.
        fx { document.expand("a.kt", 1, ExpandDirection.ALL) }
        settle()
        val row = rowOf(150)
        fx { list.scrollToRow(row, list.height - 6.0) }
        settle()
        assertFalse(fx { list.isFullyVisible(row) }, "the row is cut off at the bottom")
        fx { bar.open() }

        fx { bar.query.text = "c150" }
        settle()

        assertTrue(fx { list.isFullyVisible(row) })
    }

    @Test
    fun `typing does not move a match that is fully shown`() {
        fx { bar.open() }
        settle()
        val row = rowOf(10)
        assertTrue(fx { list.isFullyVisible(row) })
        val before = fx { list.firstVisible() }

        fx { bar.query.text = "c10" }
        settle()

        assertEquals(before, fx { list.firstVisible() })
        assertEquals("1 of 1", fx { bar.counter.text })
    }
}
