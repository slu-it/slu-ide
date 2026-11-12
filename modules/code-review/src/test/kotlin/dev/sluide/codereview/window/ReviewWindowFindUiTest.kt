package dev.sluide.codereview.window

import dev.sluide.codereview.ReviewScope
import dev.sluide.codereview.model.ExpandDirection
import dev.sluide.codereview.model.ReviewLayout
import dev.sluide.codereview.model.ReviewRow
import dev.sluide.codereview.model.modified
import dev.sluide.codereview.model.prepareFile
import dev.sluide.codereview.model.textDiff
import dev.sluide.fileviewers.LineChange
import dev.sluide.projectdata.ProjectDataStore
import dev.sluide.settings.FontSettings
import dev.sluide.settings.SettingsStore
import javafx.event.Event
import javafx.geometry.Rectangle2D
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.Region
import javafx.scene.paint.Color
import javafx.scene.shape.Path
import javafx.stage.Stage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.nio.file.Path as FilePath

/**
 * The find bar of the review window with the real diff list: the keys, the counter, the painted matches. A scene is
 * needed for focus, the accelerators and the painting; the search itself is `ReviewFindTest`.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class ReviewWindowFindUiTest {
    @TempDir
    lateinit var dir: FilePath

    private lateinit var ide: Stage
    private lateinit var window: ReviewWindow
    private val mac = System.getProperty("os.name").contains("Mac", ignoreCase = true)

    @Start
    fun start(stage: Stage) {
        ide = stage
        stage.scene = Scene(Region(), 300.0, 200.0)
        stage.show()
    }

    @AfterEach
    fun close() {
        if (this::window.isInitialized) fx { window.close() }
    }

    /** A window with one 200-line file whose lines 10, 150 and 180 changed ("c10", "c150", "c180"). */
    private fun open(robot: FxRobot) {
        val store = ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())
        val fonts = FontSettings(SettingsStore(dir.resolve("settings.properties")), FontSettings.systemDefaults())
        robot.interact {
            window =
                ReviewWindow(
                    dir.resolve("project"),
                    ReviewScope.Branch("HEAD"),
                    ide,
                    Rectangle2D(0.0, 0.0, 800.0, 200.0),
                    FakeSource(listOf(file("a.kt"))),
                    fonts,
                    ReviewPrefs(store),
                    { _, _ -> null },
                    ReviewActions({ _, _ -> }, {}),
                )
            val content =
                textDiff(
                    200,
                    200,
                    LineChange(10..10, 10..10),
                    LineChange(150..150, 150..150),
                    LineChange(180..180, 180..180),
                )
            window.document.layout.set(ReviewLayout.SPLIT)
            window.document.setFiles(listOf(modified("a.kt")))
            window.document.setContent("a.kt", prepareFile(content, null, false))
            window.stage.show()
        }
        settle()
    }

    private fun <T> fx(block: () -> T): T = WaitForAsyncUtils.asyncFx<T>(block).get()

    private fun settle() {
        fx {
            window.stage.scene.root
                .applyCss()
            window.stage.scene.root
                .layout()
        }
        WaitForAsyncUtils.waitForFxEvents()
        fx {
            window.stage.scene.root
                .layout()
        }
        WaitForAsyncUtils.waitForFxEvents()
    }

    private val bar get() = window.findBar

    private fun press(
        robot: FxRobot,
        code: KeyCode,
        shift: Boolean = false,
        shortcut: Boolean = false,
        target: () -> Node,
    ) = robot.interact {
        Event.fireEvent(
            target(),
            KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, shortcut && !mac, false, shortcut && mac),
        )
    }

    private fun rowOf(current: Int) =
        fx { window.document.rows.indexOfFirst { it is ReviewRow.SplitLine && it.current == current } }

    private fun paths(
        row: Int,
        styleClass: String,
    ): List<Path> =
        fx {
            val cell = window.diffView.rowCell(row) as? Parent ?: return@fx emptyList()
            cell.lookupAll(".$styleClass").filterIsInstance<Path>()
        }

    private fun type(query: String) = fx { bar.query.text = query }

    @Test
    fun `Shortcut+F opens the bar, focuses its field and selects the text`(robot: FxRobot) {
        open(robot)
        assertFalse(fx { bar.isOpen })
        fx { bar.open() }
        type("c1")
        fx { window.diffView.node.requestFocus() }

        press(robot, KeyCode.F, shortcut = true) { window.diffView.node }

        fx {
            assertTrue(bar.isOpen)
            assertTrue(bar.node in window.findBarSlot.children)
            assertEquals(bar.query, window.stage.scene.focusOwner)
            assertEquals("c1", bar.query.selectedText)
        }
    }

    @Test
    fun `Shortcut+F opens a closed bar`(robot: FxRobot) {
        open(robot)

        press(robot, KeyCode.F, shortcut = true) { window.diffView.node }

        fx {
            assertTrue(bar.node in window.findBarSlot.children)
            assertEquals(bar.query, window.stage.scene.focusOwner)
        }
    }

    @Test
    fun `typing shows the counter and paints the matches, the current one stronger`(robot: FxRobot) {
        open(robot)
        fx { bar.open() }

        type("c1")
        settle()

        assertEquals("1 of 3", fx { bar.counter.text })
        val row = rowOf(10)
        val match = paths(row, "review-find-match")
        val current = paths(row, "review-find-current")
        assertEquals(1, match.size)
        assertEquals(1, current.size)
        fx {
            assertTrue(match.single().boundsInLocal.width > 0 && match.single().boundsInLocal.height > 0)
            assertTrue(current.single().boundsInLocal.width > 0)
            val matchFill = match.single().fill
            val currentFill = current.single().fill
            assertTrue(matchFill is Color && currentFill is Color)
            assertNotEquals(matchFill, currentFill)
        }
    }

    @Test
    fun `a match in a cell created by scrolling is painted, and is not the current one`(robot: FxRobot) {
        open(robot)
        fx { bar.open() }
        type("c1")
        settle()
        val row = rowOf(180)
        assertNull(fx { window.diffView.rowCell(row) })

        fx { window.diffView.scrollToRow(row) }
        settle()

        assertEquals(1, paths(row, "review-find-match").size)
        assertTrue(paths(row, "review-find-current").isEmpty())
    }

    @Test
    fun `Enter goes to the next match and reveals it, Shift+Enter to the previous`(robot: FxRobot) {
        open(robot)
        fx { bar.open() }
        type("c1")
        settle()
        val second = rowOf(150)
        assertNull(fx { window.diffView.rowCell(second) })

        press(robot, KeyCode.ENTER) { bar.query }
        settle()

        assertEquals("2 of 3", fx { bar.counter.text })
        assertNotNull(fx { window.diffView.rowCell(second) }, "the match is shown")
        assertEquals(1, paths(second, "review-find-current").size)
        assertTrue(paths(rowOf(10), "review-find-current").isEmpty())

        press(robot, KeyCode.ENTER, shift = true) { bar.query }
        settle()

        assertEquals("1 of 3", fx { bar.counter.text })
        assertNotNull(fx { window.diffView.rowCell(rowOf(10)) })
    }

    @Test
    fun `F3 and Shift+F3 go through the matches wherever the focus is while the bar is open`(robot: FxRobot) {
        open(robot)
        fx { bar.open() }
        type("c1")
        settle()
        fx { window.diffView.node.requestFocus() }

        press(robot, KeyCode.F3) { window.diffView.node }
        assertEquals("2 of 3", fx { bar.counter.text })
        press(robot, KeyCode.F3) { window.diffView.node }
        assertEquals("3 of 3", fx { bar.counter.text })
        press(robot, KeyCode.F3, shift = true) { window.diffView.node }
        assertEquals("2 of 3", fx { bar.counter.text })
    }

    @Test
    fun `F3 does nothing while the bar is closed`(robot: FxRobot) {
        open(robot)
        fx { bar.open() }
        type("c1")
        fx { bar.close() }

        press(robot, KeyCode.F3) { window.diffView.node }

        assertEquals("1 of 3", fx { bar.counter.text })
    }

    @Test
    fun `Escape in the field closes the bar, clears the marks and focuses the diff`(robot: FxRobot) {
        open(robot)
        fx { bar.open() }
        type("c1")
        settle()
        val row = rowOf(10)
        assertEquals(1, paths(row, "review-find-match").size)

        press(robot, KeyCode.ESCAPE) { bar.query }
        settle()

        fx {
            assertFalse(bar.isOpen)
            assertTrue(window.findBarSlot.children.isEmpty())
            assertEquals(window.diffView.node, window.stage.scene.focusOwner)
        }
        assertTrue(paths(row, "review-find-match").isEmpty())
        assertTrue(paths(row, "review-find-current").isEmpty())
    }

    @Test
    fun `rows that arrive are searched`(robot: FxRobot) {
        open(robot)
        fx { bar.open() }
        type("o14")
        assertEquals("1 of 6", fx { bar.counter.text })

        // Lines 14 and 140..146 are hidden behind the gap, on both sides: 16 more matches before the current one.
        fx { window.document.expand("a.kt", 1, ExpandDirection.ALL) }
        settle()

        assertEquals("17 of 22", fx { bar.counter.text })
    }
}
