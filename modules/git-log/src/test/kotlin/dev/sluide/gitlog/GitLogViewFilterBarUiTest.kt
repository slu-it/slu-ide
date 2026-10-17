package dev.sluide.gitlog

import dev.sluide.git.GitLogFilter
import dev.sluide.git.GitLogOrder
import dev.sluide.git.GitRefKind
import dev.sluide.git.GitRefs
import dev.sluide.git.GitTreePath
import javafx.animation.Animation
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.shape.Circle
import javafx.scene.text.Text
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * UI tests of the git log view: the filter bar: text, options, reset and keyboard. Needs a display, see the `uiTest`
 * Gradle task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class GitLogViewFilterBarUiTest : GitLogViewUiFixture() {
    @Start
    fun start(stage: Stage) = startView(stage)

    @Test
    fun `typing filters the commits after a pause, Enter at once, Escape clears, Refresh keeps it`(robot: FxRobot) {
        openAndExpand(robot)
        val prompt = bar.text.promptText

        val (status, loadsThen) =
            WaitForAsyncUtils
                .asyncFx<Pair<Animation.Status, Int>> {
                    bar.text.text = "Subject 12"
                    bar.debounce.status to filters.size
                }.get()
        assertEquals(Animation.Status.RUNNING, status, "waiting for the pause")
        assertEquals(1, loadsThen, "no reload yet")
        eventually { assertEquals(Animation.Status.STOPPED, bar.debounce.status) }
        eventually { assertEquals(GitLogFilter(text = "Subject 12"), filters.last()) }
        eventually { assertEquals(listOf("Subject 12", "Subject 120", "Subject 121"), shownSubjects(robot).take(3)) }
        assertEquals("Text or hash", prompt)

        robot.interact { bar.text.text = "  Subject 7 " }
        press(robot, bar.text, KeyCode.ENTER)
        eventually { assertEquals(GitLogFilter(text = "Subject 7"), filters.last()) }
        Thread.sleep(500)
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(1, filters.count { it.text == "Subject 7" }, "Enter applied it, the pause adds nothing")

        robot.interact { view.refresh() }
        eventually { assertEquals(2, filters.count { it.text == "Subject 7" }) }

        var escapes = 0
        robot.interact { view.addEventHandler(KeyEvent.KEY_PRESSED) { if (it.code == KeyCode.ESCAPE) escapes++ } }
        press(robot, bar.text, KeyCode.ESCAPE)
        assertEquals("", bar.text.text)
        eventually { assertEquals(GitLogFilter(), filters.last()) }
        assertEquals(0, escapes, "consumed while there is a text")
        press(robot, bar.text, KeyCode.ESCAPE)
        assertEquals(1, escapes, "an empty field lets it through")
        eventually { assertEquals(500, view.table.items.size) }
    }

    @Test
    fun `Regex and Match Case reload at once with a text, and an empty result says no commits match`(robot: FxRobot) {
        openAndExpand(robot)
        assertEquals("Regex", bar.regex.tooltip.text)
        assertEquals("Match Case", bar.matchCase.tooltip.text)

        robot.interact { bar.regex.isSelected = true }
        Thread.sleep(400)
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(1, filters.size, "nothing to match without a text")

        robot.interact { bar.text.text = "subject 5" }
        press(robot, bar.text, KeyCode.ENTER)
        eventually { assertEquals(GitLogFilter(text = "subject 5", regex = true), filters.last()) }
        eventually { assertEquals("Subject 5", shownSubjects(robot).first()) }

        robot.interact { bar.matchCase.isSelected = true }
        eventually { assertEquals(GitLogFilter(text = "subject 5", regex = true, matchCase = true), filters.last()) }
        eventually { assertEquals("No commits match the filters.", placeholder(robot)) }
    }

    @Test
    fun `rows have no graph under a text filter, but keep their ref labels`(robot: FxRobot) {
        history = listOf(commit("m", "b", "f"), commit("f", "a"), commit("b", "a"), commit("a"))
        refs = GitRefs(listOf(ref("refs/heads/main", GitRefKind.LOCAL_BRANCH, "m")), null)
        openAndExpand(robot)
        eventually { assertTrue(graphCells(robot).any { it.item?.graph?.width == 2 }) }

        robot.interact { bar.text.text = "Subject" }
        press(robot, bar.text, KeyCode.ENTER)

        eventually {
            val cells = graphCells(robot).filter { it.item != null }
            assertEquals(4, cells.size)
            assertTrue(cells.all { it.item.graph == GraphRow.NONE })
            assertTrue(cells.all { it.graph.width == 0.0 && drawn(it.graph).isEmpty() })
            val merge = cells.single { it.item.commit.hash == "m" }
            assertTrue(merge.labels.isVisible)
            assertTrue(merge.subject.boundsInParent.minX < LANE_WIDTH, "the subject starts at the left")
        }
        val dots =
            graphCells(robot).filter { it.item != null }.flatMap { cell ->
                cell.graph.childrenUnmodifiable
                    .filterIsInstance<Circle>()
                    .filter { "git-graph-dot" in it.styleClass }
            }
        assertTrue(dots.none { it.isVisible })
    }

    @Test
    fun `the graph options set the order, first parent only and no merges`(robot: FxRobot) {
        openAndExpand(robot)
        assertEquals("Graph Options", bar.options.tooltip.text)
        assertEquals(
            listOf("Sort by Date", "Sort Topologically", null, "First Parent Only", "No Merges"),
            bar.options.items.map { it.text },
        )
        assertTrue(bar.dateOrder.isSelected)

        // As the menu does when an item is clicked.
        robot.interact { bar.topoOrder.isSelected = true }
        eventually { assertEquals(GitLogFilter(order = GitLogOrder.TOPOLOGICAL), filters.last()) }
        assertFalse(bar.dateOrder.isSelected)

        robot.interact { bar.firstParent.isSelected = true }
        eventually { assertTrue(filters.last().firstParent) }
        robot.interact { bar.noMerges.isSelected = true }
        eventually { assertTrue(filters.last().noMerges && filters.last().firstParent) }
        eventually { assertTrue(view.table.items.all { it.graph == GraphRow.NONE }) }

        assertTrue(bar.options.pseudoClassStates.any { it.pseudoClassName == "on" })
        assertEquals("Graph Options: Sort Topologically, First Parent Only, No Merges", bar.options.tooltip.text)
        // The :on style applies with the next pulse.
        WaitForAsyncUtils.waitForFxEvents()
        val marked = (
            bar.options.background.fills
                .last()
                .fill
        )

        robot.interact {
            bar.dateOrder.isSelected = true
            bar.firstParent.isSelected = false
        }
        eventually { assertEquals(GitLogOrder.DATE, filters.last().order) }
        assertEquals("Graph Options: No Merges", bar.options.tooltip.text)
        robot.interact { bar.noMerges.isSelected = false }
        eventually { assertEquals(GitLogFilter(), filters.last()) }
        assertFalse(bar.options.pseudoClassStates.any { it.pseudoClassName == "on" })
        assertEquals("Graph Options", bar.options.tooltip.text)
        WaitForAsyncUtils.waitForFxEvents()
        assertNotEquals(
            marked,
            bar.options.background
                ?.fills
                ?.lastOrNull()
                ?.fill,
            "the marker shows",
        )
    }

    @Test
    fun `Shortcut+F in the log body focuses the text field, Down there goes to the table`(robot: FxRobot) {
        openAndExpand(robot)
        var accelerated = 0
        // Key presses that reach the scene; consumed ones do not.
        val reached = mutableListOf<KeyCode>()
        robot.interact {
            view.scene.addEventHandler(KeyEvent.KEY_PRESSED) { reached += it.code }
            view.scene.accelerators[KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN)] =
                Runnable { accelerated++ }
            bar.text.text = "Subject 9"
        }

        for (node in listOf(view.table, changes.tree, details)) {
            robot.interact { node.requestFocus() }
            press(robot, node, KeyCode.F, shortcut = true)
            assertSame<Node?>(bar.text, view.scene.focusOwner)
            assertEquals("Subject 9", bar.text.selectedText)

            press(robot, bar.text, KeyCode.DOWN)
            assertSame<Node?>(view.table, view.scene.focusOwner)
        }
        assertEquals(emptyList(), reached, "both consumed")
        assertEquals(0, accelerated, "no accelerator of the window gets it")
    }

    @Test
    fun `opening another folder resets the filters`(robot: FxRobot) {
        refs = someRefs
        treePaths = listOf(GitTreePath("src", folder = true))
        openAndExpand(robot)
        robot.interact {
            bar.text.text = "Subject 1"
            bar.regex.isSelected = true
            bar.matchCase.isSelected = true
            bar.topoOrder.isSelected = true
            bar.firstParent.isSelected = true
            bar.noMerges.isSelected = true
            // Within one step: popups close when another test JVM on the display takes the window's focus.
            bar.branch.button.fire()
            bar.branch.popup.toggle(
                bar.branch.popup.list.items
                    .single { it.text == "main" },
            )
        }
        robot.interact { bar.path.button.fire() }
        eventuallyLists(bar.path, listOf("src"))
        robot.interact {
            if (!bar.path.popup.popup.isShowing) bar.path.button.fire()
            bar.path.popup.toggle(
                bar.path.popup.list.items
                    .single { it.text == "src" },
            )
        }
        eventually { assertEquals(listOf("src"), filters.last().paths) }

        otherHistory = commits(3, "o")
        var showing = false
        robot.interact {
            if (!bar.path.popup.popup.isShowing) bar.path.button.fire()
            showing = bar.path.popup.popup.isShowing
            view.open(Path.of("/other"))
        }

        assertTrue(showing, "open while the folder is opened")
        assertFalse(bar.path.popup.popup.isShowing)
        assertEquals(GitLogFilter(), bar.filter)
        assertEquals("", bar.text.text)
        assertFalse(bar.regex.isSelected || bar.matchCase.isSelected)
        assertTrue(bar.dateOrder.isSelected)
        assertFalse(bar.firstParent.isSelected || bar.noMerges.isSelected)
        assertEquals(listOf("Branch", "User", "Path"), listOf(bar.branch, bar.user, bar.path).map { it.label.text })
        eventually { assertEquals(listOf("o1", "o2", "o3"), view.table.items.map { it.commit.hash }) }
        assertEquals(GitLogFilter(), filters.last())
        Thread.sleep(400)
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(GitLogFilter(), filters.last(), "no late text from before")
    }

    @Test
    fun `the filter controls are reached with Tab and open their popup or menu with the keyboard`(robot: FxRobot) {
        refs = someRefs
        openAndExpand(robot)
        robot.interact { bar.focusText() }

        fun tab() = press(robot, checkNotNull(view.scene.focusOwner), KeyCode.TAB)

        val order = mutableListOf<Node?>(view.scene.focusOwner)
        repeat(6) {
            tab()
            order += view.scene.focusOwner
        }
        val expected =
            listOf<Node>(bar.text, bar.regex, bar.matchCase, bar.branch.button, bar.user.button, bar.path.button)
        assertEquals<List<Node?>>(expected + bar.options, order)

        // Space on Branch opens its popup; checking a branch shows its clear button, which comes next.
        robot.interact { bar.branch.button.requestFocus() }
        press(robot, bar.branch.button, KeyCode.SPACE)
        robot.interact {
            val release = KeyEvent(KeyEvent.KEY_RELEASED, "", "", KeyCode.SPACE, false, false, false, false)
            Event.fireEvent(bar.branch.button, release)
        }
        WaitForAsyncUtils.waitForFxEvents()
        val popup = bar.branch.popup
        assertTrue(popup.popup.isShowing)
        robot.interact { popup.search.text = "HEAD" }
        press(robot, popup.search, KeyCode.ENTER)
        press(robot, popup.search, KeyCode.ESCAPE)
        assertFalse(popup.popup.isShowing)
        robot.interact { bar.branch.button.requestFocus() }
        tab()
        assertSame<Node?>(bar.branch.clear, view.scene.focusOwner)
        tab()
        assertSame<Node?>(bar.user.button, view.scene.focusOwner)

        robot.interact { bar.options.requestFocus() }
        press(robot, bar.options, KeyCode.DOWN)
        assertTrue(bar.options.isShowing, "the menu opens with the keyboard")
        robot.interact { bar.options.hide() }
    }

    @Test
    fun `a click on a button of the bar leaves the focus where it was`(robot: FxRobot) {
        refs = someRefs
        openAndExpand(robot)
        robot.interact {
            bar.text.requestFocus()
            bar.text.text = "fix"
            bar.text.positionCaret(2)
        }

        click(robot, bar.regex)

        assertTrue(bar.regex.isSelected)
        assertSame<Node?>(bar.text, view.scene.focusOwner)
        assertEquals(2, bar.text.caretPosition)
        assertEquals("", bar.text.selectedText, "typing goes on where it was")

        // Opened, checked and closed in one step: the popup closes by itself when other test JVMs on the display take
        // the window's focus.
        robot.interact { view.table.requestFocus() }
        var popupOpened = false
        var popupClosed = false
        robot.interact {
            fireClick(bar.branch.button)
            popupOpened = bar.branch.popup.popup.isShowing
            Event.fireEvent(
                bar.branch.popup.search,
                KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false),
            )
            popupClosed = !bar.branch.popup.popup.isShowing
        }
        WaitForAsyncUtils.waitForFxEvents()
        assertTrue(popupOpened)
        assertTrue(popupClosed, "Escape closes it")
        assertSame<Node?>(view.table, view.scene.focusOwner)

        // Only the press: a real mouse's release goes to the menu it opened, a fired one would close it again. Checked
        // and closed at once: the menu closes by itself when other test JVMs on the display take the window's focus.
        var opened = false
        robot.interact {
            Event.fireEvent(
                bar.options,
                MouseEvent(
                    MouseEvent.MOUSE_PRESSED,
                    1.0,
                    1.0,
                    1.0,
                    1.0,
                    MouseButton.PRIMARY,
                    1,
                    false,
                    false,
                    false,
                    false,
                    true,
                    false,
                    false,
                    true,
                    false,
                    false,
                    null,
                ),
            )
            opened = bar.options.isShowing
            bar.options.hide()
        }
        WaitForAsyncUtils.waitForFxEvents()
        assertTrue(opened)
        assertSame<Node?>(view.table, view.scene.focusOwner)

        // Focused with Tab, a button keeps the focus.
        robot.interact { bar.matchCase.requestFocus() }
        click(robot, bar.matchCase)
        assertSame<Node?>(bar.matchCase, view.scene.focusOwner)
    }
}
