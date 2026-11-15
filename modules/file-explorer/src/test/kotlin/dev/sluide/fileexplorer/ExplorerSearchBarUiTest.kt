package dev.sluide.fileexplorer

import javafx.event.Event
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.control.TreeCell
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createFile
import kotlin.io.path.createParentDirectories
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The explorer's search row wired to the tree: typing, the keys, the highlight, the re-runs and a root change. The
 * logic is in `ExplorerSearchTest`; these need a scene for the focus, the key routing and the painted cells. State is
 * read on the FX thread.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class ExplorerSearchBarUiTest : FileExplorerViewUiFixture() {
    private val opened = CopyOnWriteArrayList<Path>()
    private lateinit var field: TextField

    /** The keys that reached the scene's root, which only the unconsumed ones do. */
    private val unconsumed = CopyOnWriteArrayList<KeyCode>()

    @Start
    fun start(stage: Stage) {
        startView(stage, configure = {
            it.searchDelay = Duration.ZERO // the debounce is the controller's test; here the text searches at once
            it.onOpenFile = { file -> opened.add(file) }
        }, wrap = { VBox(it.searchBar, it) })
        stage.scene.root.addEventHandler(KeyEvent.KEY_PRESSED) { unconsumed.add(it.code) }
        field = view.searchBar.lookup("#explorer-search") as TextField
    }

    private fun p(relative: String): Path = project.resolve(relative)

    private fun open(
        robot: FxRobot,
        vararg more: String,
    ) {
        (
            listOf("src/main/App.kt", "src/main/Other.kt", "src/test/AppTest.kt", "docs/guide/intro.md", "README.md") +
                more
        ).forEach { p(it).createParentDirectories().createFile() }
        robot.interact { view.open(project) }
    }

    private fun type(
        robot: FxRobot,
        text: String,
    ) = robot.interact { field.text = text }

    /** Runs [read] on the FX thread and returns what it read. */
    private fun <T> fx(
        robot: FxRobot,
        read: () -> T,
    ): T {
        var value: Result<T>? = null
        robot.interact { value = runCatching(read) }
        return checkNotNull(value).getOrThrow()
    }

    /** Fires a key press on [node] like the keyboard does; returns whether something consumed it. */
    private fun press(
        robot: FxRobot,
        node: Node,
        code: KeyCode,
        shortcut: Boolean = false,
    ): Boolean {
        val mac = System.getProperty("os.name").lowercase().contains("mac")
        val event = KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, shortcut && !mac, false, shortcut && mac)
        unconsumed.clear()
        robot.interact { Event.fireEvent(node, event) }
        return code !in unconsumed
    }

    private fun selected(robot: FxRobot): List<Path> =
        fx(robot) {
            view.selectionModel.selectedItems
                .filterNotNull()
                .map { it.value }
        }

    private fun status(): Label = view.searchBar.lookup("#explorer-search-status") as Label

    private fun hits(robot: FxRobot): List<Label> =
        robot.lookup(".explorer-search-hit").queryAllAs(Label::class.java).toList()

    /** The hit in the selected row (the one of `App.kt`); the lookup's order says nothing about the rows. */
    private fun selectedHit(robot: FxRobot): Label =
        hits(robot).single { hit ->
            generateSequence<Node>(hit) { it.parent }.filterIsInstance<TreeCell<*>>().first().isSelected
        }

    @Test
    fun `typing three characters filters the tree and shows the count`(robot: FxRobot) {
        open(robot)
        type(robot, "ap")
        eventually(robot) { assertEquals("Type at least 3 characters", status().text) }
        assertFalse(fx(robot) { view.searchActive })

        type(robot, "app")

        eventually(robot) {
            assertEquals(listOf(project.name, "src", "main", "App.kt", "test", "AppTest.kt"), renderedNames(robot))
            assertEquals("2 matches", status().text)
        }
        // The row is outside the tree, so it brings its stylesheet: the status line is padded and muted.
        assertEquals(8.0, fx(robot) { status().padding.left })
        assertEquals(0.7, fx(robot) { status().opacity })
    }

    @Test
    fun `files changing on disk and revealed entries reach the results, a new query drops the revealed ones`(
        robot: FxRobot,
    ) {
        open(robot)
        type(robot, "app")
        eventually(robot) { assertEquals("2 matches", status().text) }

        // The watcher reports it, the tree's invalidation searches again.
        p("src/main/AppMore.kt").createFile()
        eventually(robot) {
            assertEquals("3 matches", status().text)
            assertTrue("AppMore.kt" in renderedNames(robot))
        }

        robot.interact { view.reveal(p("README.md")) }
        eventually(robot) { assertTrue("README.md" in renderedNames(robot)) }

        type(robot, "more")

        eventually(robot) {
            assertEquals(listOf(project.name, "src", "main", "AppMore.kt"), renderedNames(robot))
        }
    }

    @Test
    fun `Shortcut+F in the tree focuses the field with its text selected and is consumed`(robot: FxRobot) {
        open(robot)
        type(robot, "ab")
        robot.interact { view.requestFocus() }

        val consumed = press(robot, view, KeyCode.F, shortcut = true)

        assertTrue(consumed)
        assertTrue(fx(robot) { field.isFocused })
        assertEquals("ab", fx(robot) { field.selectedText })
    }

    @Test
    fun `Escape clears the text, then moves the focus to the tree`(robot: FxRobot) {
        open(robot)
        type(robot, "app")
        eventually(robot) { assertTrue(view.searchActive) }
        robot.interact { field.requestFocus() }

        val first = press(robot, field, KeyCode.ESCAPE)

        assertTrue(first)
        assertEquals("", fx(robot) { field.text })
        eventually(robot) { assertFalse(view.searchActive) }
        assertTrue(fx(robot) { field.isFocused })

        val second = press(robot, field, KeyCode.ESCAPE)

        assertTrue(second)
        assertTrue(fx(robot) { view.isFocused })
    }

    @Test
    fun `Down selects the first match in row order and focuses the tree`(robot: FxRobot) {
        open(robot)
        type(robot, "app")
        eventually(robot) { assertEquals("2 matches", status().text) }
        robot.interact { field.requestFocus() }

        assertTrue(press(robot, field, KeyCode.DOWN))

        assertEquals(listOf(p("src/main/App.kt")), selected(robot))
        assertTrue(fx(robot) { view.isFocused })
    }

    @Test
    fun `Enter opens the first matching file, and with only folders matching selects the first`(robot: FxRobot) {
        open(robot)
        type(robot, "app")
        eventually(robot) { assertEquals("2 matches", status().text) }

        assertTrue(press(robot, field, KeyCode.ENTER))

        assertEquals(listOf(p("src/main/App.kt")), opened.toList())

        type(robot, "guide")
        eventually(robot) { assertEquals("1 match", status().text) }
        robot.interact { field.requestFocus() }

        press(robot, field, KeyCode.ENTER)

        assertEquals(listOf(p("docs/guide")), selected(robot))
        assertTrue(fx(robot) { view.isFocused })
        assertEquals(1, opened.size)
    }

    @Test
    fun `Enter opens the first matching file even when a folder hit comes first in the rows`(robot: FxRobot) {
        open(robot, "apps/notes.txt")
        type(robot, "app")
        eventually(robot) {
            assertEquals(
                listOf(project.name, "apps", "src", "main", "App.kt", "test", "AppTest.kt"),
                renderedNames(robot),
            )
        }

        press(robot, field, KeyCode.ENTER)

        assertEquals(listOf(p("src/main/App.kt")), opened.toList())
    }

    @Test
    fun `Enter right after typing waits for the results of the typed text`(robot: FxRobot) {
        open(robot)
        type(robot, "app")
        eventually(robot) { assertEquals("2 matches", status().text) }
        // The next text would wait ten seconds; the key must not act on the results of "app" meanwhile.
        robot.interact { view.searchDelay = 10.seconds }
        type(robot, "appt")

        press(robot, field, KeyCode.ENTER)

        eventually(robot) { assertEquals(listOf(p("src/test/AppTest.kt")), opened.toList()) }
    }

    @Test
    fun `the matching part of a name is painted with a background, and only while searching`(robot: FxRobot) {
        open(robot)
        type(robot, "app")

        eventually(robot) {
            assertEquals(listOf("App", "App"), hits(robot).map { it.text })
            hits(robot).forEach { hit ->
                assertTrue(
                    hit.background.fills.any { (it.fill as? Color)?.let { c -> c.opacity > 0.0 } == true },
                    "the hit has a painted background",
                )
            }
        }

        // The App.kt row stays, so its cell is only refreshed, not rebuilt.
        type(robot, "pp.k")

        eventually(robot) { assertEquals(listOf("pp.k"), hits(robot).map { it.text }) }

        type(robot, "")

        eventually(robot) {
            assertTrue(hits(robot).isEmpty())
            assertEquals(listOf(project.name, "docs", "src", "README.md"), renderedNames(robot))
        }
    }

    @Test
    fun `a new query with the same matches moves the highlight of the shown rows`(robot: FxRobot) {
        open(robot)
        type(robot, "read")
        eventually(robot) { assertEquals(listOf("READ"), hits(robot).map { it.text }) }

        type(robot, "ead")

        eventually(robot) { assertEquals(listOf("EAD"), hits(robot).map { it.text }) }
    }

    @Test
    fun `the three parts of a highlighted name touch, so the name reads as one word`(robot: FxRobot) {
        open(robot)
        type(robot, "ead")

        eventually(robot) {
            val hit = hits(robot).single()
            val parts = (hit.parent as HBox).children.map { it as Label }
            val bounds = parts.map { it.localToScene(it.layoutBounds) }
            assertEquals(listOf("R", "EAD", "ME.md"), parts.map { it.text })
            assertEquals(0.0, bounds[1].minX - bounds[0].maxX, 0.5, "before and hit")
            assertEquals(0.0, bounds[2].minX - bounds[1].maxX, 0.5, "hit and after")
            // Nothing but the text inside the hit and the rest: no padding that would show as a gap.
            listOf(1, 2).forEach {
                val text =
                    javafx.scene.text
                        .Text(parts[it].text)
                        .apply { font = parts[it].font }
                assertEquals(text.layoutBounds.width, parts[it].width, 1.0, "width of ${parts[it].text}")
            }
        }
    }

    @Test
    fun `the hit on a selected row is washed dark only while the tree has the focus`(robot: FxRobot) {
        open(robot)
        type(robot, "app")
        eventually(robot) { assertEquals("2 matches", status().text) }
        robot.interact {
            val row = (0 until view.expandedItemCount).first { view.getTreeItem(it).value.name == "App.kt" }
            view.selectionModel.select(row)
            view.requestFocus()
        }

        eventually(robot) {
            val wash =
                selectedHit(robot)
                    .background.fills
                    .first()
                    .fill as Color
            assertTrue(wash.red < 0.1, "the focused selection has the dark wash: $wash")
        }

        // The unfocused selection is light grey in the light theme: the amber stays.
        robot.interact { field.requestFocus() }

        eventually(robot) {
            val wash =
                selectedHit(robot)
                    .background.fills
                    .first()
                    .fill as Color
            assertTrue(wash.red > 0.9, "the unfocused selection keeps the amber wash: $wash")
        }
    }

    @Test
    fun `the status line wraps in a narrow row instead of being cut off`(robot: FxRobot) {
        open(robot)
        robot.interact {
            (view.searchBar as VBox).apply {
                prefWidth = 90.0
                maxWidth = 90.0
            }
        }
        type(robot, "ab")

        eventually(robot) {
            view.scene.root.layout()
            assertEquals("Type at least 3 characters", status().text)
            assertTrue(status().width <= 90.0, "the label stays within the row: ${status().width}")
            assertTrue(status().height > 30.0, "two lines or more: ${status().height}")
        }
    }

    @Test
    fun `opening other roots clears the field without touching the new tree`(robot: FxRobot) {
        open(robot)
        type(robot, "app")
        eventually(robot) { assertTrue(view.searchActive) }

        robot.interact { view.open(settingsDir) }

        assertEquals("", fx(robot) { field.text })
        assertFalse(fx(robot) { view.searchActive })
        eventually(robot) { assertEquals("", status().text) }

        robot.interact { view.open(null) }
        assertTrue(fx(robot) { field.isDisabled })
    }
}
