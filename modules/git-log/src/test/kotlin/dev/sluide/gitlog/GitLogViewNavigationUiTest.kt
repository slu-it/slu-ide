package dev.sluide.gitlog

import dev.sluide.git.GitCommitDetails
import dev.sluide.git.GitRefKind
import dev.sluide.git.GitRefs
import dev.sluide.git.GitRevisionResolution
import javafx.animation.Animation
import javafx.event.Event
import javafx.event.EventType
import javafx.geometry.Point2D
import javafx.scene.Cursor
import javafx.scene.Node
import javafx.scene.control.ListCell
import javafx.scene.input.KeyCode
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.text.Text
import javafx.scene.text.TextFlow
import javafx.stage.Stage
import javafx.util.Duration
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * UI tests of the git log view: the branches containing a commit, links in commit messages and Go to (ADR 0045). Needs
 * a display, see the `uiTest` Gradle task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class GitLogViewNavigationUiTest : GitLogViewUiFixture() {
    @Start
    fun start(stage: Stage) = startView(stage)

    /** The branches line as shown, `null` while hidden. */
    private fun branchesLine(): String? =
        WaitForAsyncUtils
            .asyncFx<String?> {
                details.branchesLine.takeIf { it.isVisible && details.scroll.isVisible }?.text
            }.get()

    @Test
    fun `lists the branches containing the selected commit below its refs, the current branch first`(robot: FxRobot) {
        refs = someRefs
        branchesOf = { hash ->
            when (hash) {
                "h1" -> listOf("refs/remotes/origin/main", "refs/heads/feature/x", "refs/heads/main")
                "h2" -> listOf("refs/heads/main")
                else -> emptyList()
            }
        }
        openAndExpand(robot)
        select(robot, 0)
        eventually { assertEquals("In 3 branches: main, feature/x, origin/main", branchesLine()) }
        robot.interact {
            val content = details.branchesLine.parent
            assertTrue(
                content.childrenUnmodifiable.indexOf(details.branchesLine) >
                    content.childrenUnmodifiable.indexOf(details.refs),
                "below the refs",
            )
        }

        select(robot, 1)
        eventually { assertEquals("In 1 branch: main", branchesLine()) }
        select(robot, 2)
        eventually { assertEquals("In no branch", branchesLine()) }

        // Read once per commit while the refs stay the same.
        select(robot, 0)
        eventually { assertEquals("In 3 branches: main, feature/x, origin/main", branchesLine()) }
        assertEquals(listOf("h1", "h2", "h3"), branchLoads)
        assertEquals(listOf("h1", "h2", "h3", "h1"), detailLoads, "the details are read again")
    }

    @Test
    fun `more than six branches are cut short until Show all is clicked, for that commit`(robot: FxRobot) {
        val many = (1..9).map { "refs/heads/b$it" }
        branchesOf = { many }
        openAndExpand(robot)
        select(robot, 0)
        eventually { assertEquals("In 9 branches: b1, b2, b3, b4, b5, b6, … Show all (9)", branchesLine()) }
        eventually { assertWrappedAndWhole(details.branchesLine) }

        clickText(robot, details.branchesLine, "Show all (9)")
        eventually { assertEquals("In 9 branches: ${(1..9).joinToString(", ") { "b$it" }}", branchesLine()) }
        eventually { assertWrappedAndWhole(details.branchesLine) }

        select(robot, 1)
        eventually { assertEquals("In 9 branches: b1, b2, b3, b4, b5, b6, … Show all (9)", branchesLine()) }
    }

    /** Asserts that [area] is wrapped in the narrow pane, and as high as all its lines. */
    private fun assertWrappedAndWhole(area: LinkText) {
        val text = area.lookupAll(".paragraph-text").filterIsInstance<TextFlow>().single()
        val oneLine = Text("X").apply { font = (area.lookup(".text") as Text).font }.layoutBounds.height
        assertTrue(text.height > oneLine * 1.5, "wrapped: ${text.height}, a line $oneLine")
        assertTrue(text.height <= area.height, "${text.height} in ${area.height}")
    }

    @Test
    fun `says when the branches cannot be read, and looks for them only after a while`(robot: FxRobot) {
        branchesOf = { hash -> if (hash == "h1") null else listOf("refs/heads/main") }
        openAndExpand(robot)
        select(robot, 0)
        eventually { assertEquals("Branches could not be read.", branchesLine()) }

        branchGates["h2"] = CountDownLatch(1)
        // Longer than the 300 ms, which a slow CI runner spent before the first look; then jumped to its end.
        robot.interact { details.lookingForBranches.duration = Duration.minutes(1.0) }
        select(robot, 1)
        eventually { assertTrue(showsDetailsOf("Subject 2")) }
        robot.interact {
            assertFalse(details.branchesLine.isVisible, "nothing at first")
            assertEquals(Animation.Status.RUNNING, details.lookingForBranches.status)
            details.lookingForBranches.jumpTo(details.lookingForBranches.duration)
        }
        eventually { assertEquals("Looking for branches…", branchesLine()) }
        branchGates.getValue("h2").countDown()
        eventually { assertEquals("In 1 branch: main", branchesLine()) }
    }

    @Test
    fun `new refs read the branches of the shown commit again, a result for another commit is dropped`(
        robot: FxRobot,
    ) {
        refs = someRefs
        branchesOf = { listOf("refs/heads/main") }
        openAndExpand(robot)
        select(robot, 0)
        eventually { assertEquals("In 1 branch: main", branchesLine()) }

        // The same refs: nothing read again.
        robot.interact { view.refresh() }
        eventually { assertEquals(listOf("h1", "h1"), detailLoads) }
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(listOf("h1"), branchLoads)

        branchesOf = { listOf("refs/heads/main", "refs/heads/new") }
        refs = GitRefs(someRefs.refs + ref("refs/heads/new", GitRefKind.LOCAL_BRANCH, "h1"), "refs/heads/main")
        robot.interact { view.refresh() }
        eventually { assertEquals("In 2 branches: main, new", branchesLine()) }
        assertEquals(listOf("h1", "h1"), branchLoads)

        branchGates["h2"] = CountDownLatch(1)
        select(robot, 1)
        eventually { assertTrue(showsDetailsOf("Subject 2")) }
        select(robot, 2)
        eventually { assertTrue(showsDetailsOf("Subject 3")) }
        branchesOf = { hash -> if (hash == "h2") listOf("refs/heads/h2-only") else listOf("refs/heads/main") }
        branchGates.getValue("h2").countDown()
        eventually { assertEquals("In 1 branch: main", branchesLine()) }
        assertEquals(listOf("h1", "h1", "h2", "h3"), branchLoads)
    }

    /** A history whose hashes are hexadecimal, as words of messages must be to be links: `abcdef01`, `abcdef02`… */
    private fun hexHistory() = commits(1200, "abcdef0")

    /** The details of [history]'s commits, with [body] as the body of [hash]'s message. */
    private fun withBody(
        hash: String,
        body: String,
    ): (String) -> GitCommitDetails? =
        { candidate ->
            val details = defaultDetails(candidate)
            if (candidate == hash) details?.copy(message = "${details.commit.subject}\n\n$body\n") else details
        }

    /** The texts of [area] styled as links. */
    private fun links(area: LinkText): List<String> =
        WaitForAsyncUtils
            .asyncFx<List<String>> {
                val text = area.text
                hashCandidates(text)
                    .filter { range -> "git-details-link" in area.getStyleOfChar(range.first + 1) }
                    .map { text.substring(it) }
            }.get()

    @Test
    fun `hashes of commits in the message are links that select their commit`(robot: FxRobot) {
        history = hexHistory()
        detailsOf = withBody("abcdef01", "Reverts abcdef05, not ABCDEF9999 nor abcdef0 (ambiguous) nor xabcdef05.")
        openAndExpand(robot)
        select(robot, 0)
        eventually { assertEquals(listOf("abcdef05"), links(details.body)) }
        assertEquals(listOf(listOf("abcdef05", "abcdef9999", "abcdef0")), resolveLoads, "one call for all")

        // Hovering shows the hand.
        val point = textPoint(details.body, "abcdef05")
        robot.interact {
            fireMouse(details.body, MouseEvent.MOUSE_MOVED, point)
            details.body.applyCss()
            assertEquals(Cursor.HAND, details.body.cursor)
        }

        clickText(robot, details.body, "abcdef05")
        eventually {
            assertEquals(
                listOf(4),
                view.table.selectionModel.selectedIndices
                    .toList(),
            )
        }
        assertSame<Node?>(view.table, view.scene.focusOwner, "from the clicked text")
        eventually { assertTrue(showsDetailsOf("Subject 5")) }
        assertEquals("", status(robot))
    }

    @Test
    fun `a hash link loads pages until its commit is there, or tells why it is not`(robot: FxRobot) {
        history = hexHistory()
        detailsOf = withBody("abcdef01", "See abcdef0900 and abcdef01100.")
        openAndExpand(robot)
        select(robot, 0)
        eventually { assertEquals(listOf("abcdef0900", "abcdef01100"), links(details.body)) }

        clickText(robot, details.body, "abcdef0900")
        eventually {
            assertEquals(
                listOf(899),
                view.table.selectionModel.selectedIndices
                    .toList(),
            )
        }
        assertTrue(899 in visibleRows(), "scrolled to it")

        // Hidden by a filter.
        robot.interact { bar.text.text = "Subject 1" }
        robot.interact { bar.text.fireEvent(javafx.event.ActionEvent()) }
        eventually { assertTrue(view.table.items.all { "Subject 1" in it.commit.subject }) }
        select(robot, 0)
        eventually { assertEquals(listOf("abcdef0900", "abcdef01100"), links(details.body)) }
        clickText(robot, details.body, "abcdef0900")
        eventually { assertEquals("abcdef09 is hidden by the filters.", status(robot)) }
    }

    @Test
    fun `a click that selects text does not follow a link, a double click follows it once`(robot: FxRobot) {
        history = hexHistory()
        detailsOf = withBody("abcdef01", "Reverts abcdef05.")
        openAndExpand(robot)
        select(robot, 0)
        eventually { assertEquals(listOf("abcdef05"), links(details.body)) }

        val point = textPoint(details.body, "abcdef05")
        robot.interact {
            fireMouse(details.body, MouseEvent.MOUSE_PRESSED, point)
            details.body.selectRange(0, 7)
            fireMouse(details.body, MouseEvent.MOUSE_RELEASED, point)
            fireMouse(details.body, MouseEvent.MOUSE_CLICKED, point)
        }
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(
            listOf(0),
            view.table.selectionModel.selectedIndices
                .toList(),
            "text selected: not followed",
        )
        assertEquals(1, resolveLoads.size)

        // A real double click: a first click (followed, as in IntelliJ), then one with a click count of 2 (ignored).
        robot.interact {
            for (count in 1..2) {
                for (type in listOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED)) {
                    fireMouse(details.body, type, point, count)
                }
            }
        }
        eventually {
            assertEquals(
                listOf(4),
                view.table.selectionModel.selectedIndices
                    .toList(),
            )
        }
        eventually { assertTrue(showsDetailsOf("Subject 5")) }
        assertEquals(
            listOf(4),
            view.table.selectionModel.selectedIndices
                .toList(),
            "followed once",
        )
    }

    private val goTo get() = view.navigation.goToPopup

    /** Opens Go to with Shortcut+G in the table and types [text]. */
    private fun openGoTo(
        robot: FxRobot,
        text: String = "",
    ) {
        press(robot, view.table, KeyCode.G, shortcut = true)
        eventually { assertTrue(goTo.isShowing) }
        robot.interact { goTo.field.text = text }
    }

    /** The completions shown, `null` while the list is hidden. */
    private fun completions(): List<String>? =
        WaitForAsyncUtils
            .asyncFx<List<String>?> {
                goTo.list
                    .takeIf { it.isVisible }
                    ?.items
                    ?.map { it.text }
            }.get()

    /** What the popup says, `null` while it says nothing. */
    private fun goToMessage(): String? =
        WaitForAsyncUtils.asyncFx<String?> { goTo.message.takeIf { it.parent.isVisible }?.text }.get()

    @Test
    fun `Shortcut+G opens Go to at the top of the table, completing branches and tags as typed`(robot: FxRobot) {
        refs = branchRefs
        openAndExpand(robot)
        press(robot, view.table, KeyCode.G, shortcut = true)
        eventually { assertTrue(goTo.isShowing) }
        robot.interact {
            assertSame<Node?>(goTo.field, goTo.field.scene.focusOwner)
            assertEquals("Hash, branch or tag", goTo.field.promptText)
            val table = view.table.localToScreen(view.table.boundsInLocal)
            assertEquals(table.minY, goTo.popup.y, 5.0, "at the top of the table")
        }
        assertNull(completions(), "nothing typed")

        robot.interact { goTo.field.text = "MAIN" }
        assertEquals(listOf("main", "origin/main"), completions())
        robot.interact { goTo.field.text = "v" }
        assertEquals(listOf("v0.9", "v1.0"), completions())
        robot.interact {
            assertEquals(
                listOf("git-ref-tag"),
                goTo.list
                    .lookupAll(".git-ref-icon")
                    .filter { it.isVisible }
                    .map { icon -> icon.styleClass.single { it.startsWith("git-ref-") && it != "git-ref-icon" } }
                    .distinct(),
            )
        }
        robot.interact { goTo.field.text = "nothing" }
        assertNull(completions())

        // Escape closes without moving.
        press(robot, goTo.field, KeyCode.ESCAPE)
        assertFalse(goTo.isShowing)
        assertEquals(
            emptyList(),
            view.table.selectionModel.selectedIndices
                .toList(),
        )

        // Also from the details, and the table's context menu.
        select(robot, 0)
        eventually { assertTrue(showsDetailsOf("Subject 1")) }
        press(robot, details.body, KeyCode.G, shortcut = true)
        eventually { assertTrue(goTo.isShowing) }
        robot.interact { goTo.hide() }
        assertEquals("Go to Hash/Branch/Tag\u2026", view.table.goToItem.text)
        robot.interact { view.table.goToItem.fire() }
        eventually { assertTrue(goTo.isShowing) }
    }

    @Test
    fun `Enter or a click in the list goes to the branch, Up goes back to the field`(robot: FxRobot) {
        refs = branchRefs
        resolutionOf = { revision ->
            when (revision) {
                "refs/heads/feature/a" -> GitRevisionResolution.Commit("h5")
                "refs/heads/feature/b" -> GitRevisionResolution.Commit("h7")
                "refs/remotes/upstream/far" -> GitRevisionResolution.Commit("h800")
                else -> defaultResolution(revision)
            }
        }
        openAndExpand(robot)
        openGoTo(robot, "feat")
        assertEquals(listOf("feature/a", "feature/b"), completions())
        press(robot, goTo.field, KeyCode.DOWN)
        robot.interact {
            assertSame<Node?>(goTo.list, goTo.list.scene.focusOwner)
            assertEquals(0, goTo.list.selectionModel.selectedIndex)
        }
        press(robot, goTo.list, KeyCode.UP)
        robot.interact { assertSame<Node?>(goTo.field, goTo.field.scene.focusOwner) }
        press(robot, goTo.field, KeyCode.DOWN)
        press(robot, goTo.list, KeyCode.ENTER)
        eventually {
            assertEquals(
                listOf(4),
                view.table.selectionModel.selectedIndices
                    .toList(),
            )
        }
        assertFalse(goTo.isShowing)
        assertSame<Node?>(view.table, view.scene.focusOwner)
        assertEquals(listOf("refs/heads/feature/a"), resolveLoads.last())

        // Typed exactly: the item. Clicked: the item, loading pages until it is there.
        openGoTo(robot, "feature/b")
        press(robot, goTo.field, KeyCode.ENTER)
        eventually {
            assertEquals(
                listOf(6),
                view.table.selectionModel.selectedIndices
                    .toList(),
            )
        }
        assertEquals(listOf("refs/heads/feature/b"), resolveLoads.last())
        openGoTo(robot, "far")
        WaitForAsyncUtils.waitForFxEvents()
        val cell =
            goTo.list
                .lookupAll(".list-cell")
                .filterIsInstance<ListCell<*>>()
                .single { (it.item as? FilterItem)?.text == "upstream/far" }
        click(robot, cell)
        eventually {
            assertEquals(
                listOf(799),
                view.table.selectionModel.selectedIndices
                    .toList(),
            )
        }
        assertFalse(goTo.isShowing)
    }

    @Test
    fun `a typed hash or revision is resolved, unknown and ambiguous ones are told in the popup`(robot: FxRobot) {
        refs = branchRefs
        openAndExpand(robot)
        openGoTo(robot, " h7 ")
        press(robot, goTo.field, KeyCode.ENTER)
        eventually {
            assertEquals(
                listOf(6),
                view.table.selectionModel.selectedIndices
                    .toList(),
            )
        }
        assertEquals(listOf("h7"), resolveLoads.last(), "trimmed")

        openGoTo(robot, "nope")
        press(robot, goTo.field, KeyCode.ENTER)
        eventually { assertEquals("Unknown revision 'nope'.", goToMessage()) }
        robot.interact {
            assertTrue(goTo.isShowing, "stays open")
            assertEquals("nope", goTo.field.selectedText)
            assertFalse(goTo.clearFiltersButton.isVisible)
        }

        robot.interact { goTo.field.text = "h" }
        assertNull(goToMessage(), "typing clears it")
        press(robot, goTo.field, KeyCode.ENTER)
        eventually { assertEquals("'h' is ambiguous.", goToMessage()) }

        resolveFails = true
        robot.interact { goTo.field.text = "@{upstream}" }
        press(robot, goTo.field, KeyCode.ENTER)
        eventually { assertEquals("Unknown revision '@{upstream}'.", goToMessage()) }
        assertEquals(
            listOf(6),
            view.table.selectionModel.selectedIndices
                .toList(),
            "not moved",
        )

        // Nothing typed: nothing asked.
        val asked = resolveLoads.size
        robot.interact { goTo.field.text = "  " }
        press(robot, goTo.field, KeyCode.ENTER)
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(asked, resolveLoads.size)
    }

    @Test
    fun `Go to says when the log cannot be read`(robot: FxRobot) {
        refs = branchRefs
        openAndExpand(robot)
        history = null
        robot.interact { view.refresh() }
        eventually { assertEquals("The git log could not be read.", status(robot), "the rows kept") }
        resolutionOf = { GitRevisionResolution.Commit("h900") }

        openGoTo(robot, "h900")
        press(robot, goTo.field, KeyCode.ENTER)
        eventually { assertEquals("The git log could not be read.", goToMessage()) }
        robot.interact {
            assertTrue(goTo.isShowing)
            assertFalse(goTo.clearFiltersButton.isVisible)
        }
    }

    @Test
    fun `a commit hidden by the filters can be gone to after Clear Filters`(robot: FxRobot) {
        refs = branchRefs
        openAndExpand(robot)
        robot.interact {
            bar.matchCase.isSelected = true
            bar.text.text = "Subject 1"
            bar.text.fireEvent(javafx.event.ActionEvent())
            bar.firstParent.isSelected = true
        }
        eventually { assertTrue(view.table.items.all { "Subject 1" in it.commit.subject }) }

        openGoTo(robot, "h7")
        press(robot, goTo.field, KeyCode.ENTER)
        eventually { assertEquals("h7 is hidden by the filters.", goToMessage()) }
        robot.interact { assertTrue(goTo.clearFiltersButton.isVisible) }

        robot.interact { goTo.clearFiltersButton.fire() }
        eventually {
            assertEquals(
                listOf(6),
                view.table.selectionModel.selectedIndices
                    .toList(),
            )
        }
        robot.interact {
            assertFalse(goTo.isShowing)
            assertEquals("", bar.text.text)
            assertTrue(bar.matchCase.isSelected, "Match Case stays as it was")
            assertFalse(bar.firstParent.isSelected)
            assertTrue(bar.filter.isDefault)
        }
    }

    /** The scene coordinates of the second character of [text] in [area]. */
    private fun textPoint(
        area: LinkText,
        text: String,
    ): Point2D =
        WaitForAsyncUtils
            .asyncFx<Point2D> {
                val start = area.text.indexOf(text)
                check(start >= 0) { "'$text' not in '${area.text}'" }
                val bounds = checkNotNull(area.getCharacterBoundsOnScreen(start + 1, start + 2).orElse(null))
                area.localToScene(area.screenToLocal(Point2D(bounds.centerX, bounds.centerY)))
            }.get()

    /** Clicks [text] in [area] with the primary button. */
    private fun clickText(
        robot: FxRobot,
        area: LinkText,
        text: String,
    ) {
        val point = textPoint(area, text)
        click(robot, area, 1, point.x, point.y)
    }

    /** Fires a mouse event of [type] with the primary button at [point] (scene coordinates) of [node]. */
    private fun fireMouse(
        node: Node,
        type: EventType<MouseEvent>,
        point: Point2D,
        clickCount: Int = 1,
    ) {
        Event.fireEvent(
            node,
            MouseEvent(
                type,
                point.x,
                point.y,
                point.x,
                point.y,
                MouseButton.PRIMARY,
                clickCount,
                false,
                false,
                false,
                false,
                type == MouseEvent.MOUSE_PRESSED,
                false,
                false,
                true,
                false,
                false,
                null,
            ),
        )
    }
}
