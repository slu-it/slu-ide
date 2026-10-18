package dev.sluide.gitlog

import dev.sluide.git.GitRefKind
import dev.sluide.git.GitRefs
import dev.sluide.gitlog.ui.GIT_LOG_CSS
import javafx.animation.Animation
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.TreeCell
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.text.Text
import javafx.stage.Stage
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

/** UI tests of the git log view: the branches pane. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class GitLogViewBranchesUiTest : GitLogViewUiFixture() {
    @Start
    fun start(stage: Stage) = startView(stage)

    @Test
    fun `the branches pane shows HEAD, local and remote branches and tags left of the commits, Tags collapsed`(
        robot: FxRobot,
    ) {
        openBranches(robot)

        assertEquals(
            listOf(
                "HEAD (Current Branch)",
                "Local/",
                "  feature/",
                "    a",
                "    b",
                "  team/fix/",
                "    x",
                "  main",
                "Remote/",
                "  origin/",
                "    main",
                "    old",
                "  upstream/",
                "    far",
                "Tags/",
            ),
            shownBranches(),
        )
        robot.interact {
            assertEquals(listOf<Node>(branches, view.inner), view.body.items)
            assertEquals(view.body.width * 0.2, branches.width, 10.0, "the left 20 %")
            assertEquals(view.inner.width * 0.3, details.width, 10.0, "the details keep their 30 %")
            assertTrue(branches.localToScene(0.0, 0.0).x < view.table.localToScene(0.0, 0.0).x)
            assertEquals(bar.height, branches.bar.height, 0.5, "the search lines up with the filter bar")
            assertEquals(bar.localToScene(0.0, 0.0).y, branches.bar.localToScene(0.0, 0.0).y, 0.5)
            assertTrue(bar.branchesToggle.isSelected)
            assertEquals("Branches", bar.branchesToggle.tooltip.text)
            assertSame<Node?>(bar.branchesToggle, bar.children.first())
            assertTrue(bar.branchesToggle.isFocusTraversable)
            assertEquals("Search branches", branches.search.promptText)
            assertTrue(GIT_LOG_CSS in view.stylesheets)
        }
        robot.interact {
            fun icon(cell: TreeCell<*>) = (cell.graphic as javafx.scene.Parent).childrenUnmodifiable[0]

            fun name(cell: TreeCell<*>) = (cell.graphic as javafx.scene.Parent).childrenUnmodifiable[1] as Label
            val main = branchCell("Local/main")
            assertTrue("git-branch-current" in name(main).styleClass, "the current branch in bold")
            assertTrue("git-ref-head" in icon(main).styleClass)
            assertEquals("refs/heads/main", main.tooltip.text)
            val feature = branchCell("Local/feature/a")
            assertFalse("git-branch-current" in name(feature).styleClass)
            assertTrue("git-ref-local" in icon(feature).styleClass)
            assertTrue("git-ref-remote" in icon(branchCell("Remote/origin/old")).styleClass)
            assertTrue("git-ref-head" in icon(branchCell("HEAD")).styleClass)
            assertEquals("HEAD", branchCell("HEAD").tooltip.text)
            assertNull(branchCell("Local").tooltip)
        }

        // Tags open on demand.
        robot.interact { branchItem("Tags").isExpanded = true }
        assertEquals(listOf("Tags/", "  v0.9", "  v1.0"), shownBranches().takeLast(3))
        robot.interact {
            branchItem("Local").isExpanded = false
            branchItem("Remote").isExpanded = false
        }
        eventually {
            val tag = branchCell("Tags/v1.0")
            assertTrue("git-ref-tag" in ((tag.graphic as javafx.scene.Parent).childrenUnmodifiable[0]).styleClass)
        }
    }

    @Test
    fun `the branches pane says while loading, without refs and for a detached HEAD`(robot: FxRobot) {
        gate = CountDownLatch(1)
        refs = GitRefs(emptyList(), null)
        robot.interact {
            view.open(repo)
            view.expanded.set(true)
        }
        assertEquals("Loading…", branches.placeholder.text)
        assertTrue(branches.placeholder.isVisible)
        gate?.countDown()
        gate = null
        eventually { assertEquals("No branches yet.", branches.placeholder.text) }
        assertFalse(branches.tree.isVisible)

        refs = GitRefs(listOf(ref("HEAD", GitRefKind.HEAD, "abcdef0123456789")), null)
        robot.interact { view.refresh() }
        eventually { assertEquals(listOf("HEAD (detached at abcdef01)"), shownBranches()) }
        assertTrue(branches.tree.isVisible)
        assertFalse(branches.placeholder.isVisible)
    }

    @Test
    fun `the toggle hides and shows the branches pane, keeping its share, and takes the focus out of it`(
        robot: FxRobot,
    ) {
        openBranches(robot)
        robot.interact { branches.tree.requestFocus() }

        robot.interact { bar.branchesToggle.isSelected = false }
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(listOf<Node>(view.inner), view.body.items)
        assertNull(branches.scene)
        assertSame<Node?>(view.table, view.scene.focusOwner)
        robot.interact { assertEquals(view.body.width, view.inner.width, 1.0) }

        robot.interact { bar.branchesToggle.isSelected = true }
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(listOf<Node>(branches, view.inner), view.body.items)
        robot.interact { assertEquals(view.body.width * 0.2, branches.width, 10.0) }
        assertSame<Node?>(view.table, view.scene.focusOwner)

        // Dragged, hidden and shown again, and resized: the share stays.
        robot.interact { view.body.setDividerPositions(0.35) }
        WaitForAsyncUtils.waitForFxEvents()
        robot.interact { bar.branchesToggle.isSelected = false }
        robot.interact { bar.branchesToggle.isSelected = true }
        WaitForAsyncUtils.waitForFxEvents()
        try {
            robot.interact { assertEquals(view.body.width * 0.35, branches.width, 10.0) }
            robot.interact { view.scene.window.width = 600.0 }
            WaitForAsyncUtils.waitForFxEvents()
            robot.interact { assertEquals(view.body.width * 0.35, branches.width, 10.0) }
        } finally {
            // The stage is shared by the tests.
            robot.interact { view.scene.window.width = 800.0 }
            WaitForAsyncUtils.waitForFxEvents()
        }

        // A click on the toggle leaves the focus where it was.
        robot.interact { bar.text.requestFocus() }
        click(robot, bar.branchesToggle)
        assertFalse(bar.branchesToggle.isSelected)
        assertSame<Node?>(bar.text, view.scene.focusOwner)
        click(robot, bar.branchesToggle)
        assertTrue(bar.branchesToggle.isSelected)
        assertSame<Node?>(bar.text, view.scene.focusOwner)
    }

    @Test
    fun `searching narrows the tree with every node expanded, Escape clears it, Down goes into the tree`(
        robot: FxRobot,
    ) {
        openBranches(robot)
        robot.interact { branchItem("Local/feature").isExpanded = false }

        // Applied after a pause, not on every keystroke.
        val all = shownBranches()
        var unchanged = false
        var waiting = false
        // In one step: checked right after typing, well within the pause.
        robot.interact {
            branches.search.text = "FEAT"
            unchanged = branchLines() == all
            waiting = branches.debounce.status == Animation.Status.RUNNING
        }
        assertTrue(unchanged, "not yet")
        assertTrue(waiting)
        eventually {
            assertEquals(listOf("Local/", "  feature/", "    a", "    b"), shownBranches(), "open while searching")
        }
        robot.interact { branches.search.text = "v1" }
        eventually { assertEquals(listOf("Tags/", "  v1.0"), shownBranches(), "Tags open while searching") }
        robot.interact { branches.search.text = "origin/m" }
        eventually { assertEquals(listOf("Remote/", "  origin/", "    main"), shownBranches()) }

        robot.interact { branches.search.text = "nothing" }
        eventually { assertTrue(branches.placeholder.isVisible) }
        assertEquals("No matching branches", branches.placeholder.text)
        assertFalse(branches.tree.isVisible)

        // Escape clears the text, and the collapsed nodes are collapsed again.
        val reached = mutableListOf<KeyCode>()
        robot.interact { view.scene.addEventHandler(KeyEvent.KEY_PRESSED) { reached += it.code } }
        press(robot, branches.search, KeyCode.ESCAPE)
        assertEquals("", branches.search.text)
        assertEquals(emptyList(), reached, "consumed")
        assertTrue(branches.tree.isVisible)
        assertEquals(listOf("HEAD (Current Branch)", "Local/", "  feature/", "  team/fix/"), shownBranches().take(4))
        assertEquals("Tags/", shownBranches().last())
        press(robot, branches.search, KeyCode.ESCAPE)
        assertEquals(listOf(KeyCode.ESCAPE), reached, "not consumed when empty")

        // Down moves into the tree and selects the first branch, which shows its commit.
        robot.interact {
            branches.search.requestFocus()
            branches.search.text = "old"
        }
        press(robot, branches.search, KeyCode.DOWN)
        assertSame<Node?>(branches.tree, view.scene.focusOwner)
        assertEquals("refs/remotes/origin/old", branches.selectedRef?.name)
        eventually {
            assertEquals(
                "h40",
                view.table.selectionModel.selectedItem
                    ?.commit
                    ?.hash,
            )
        }

        // Shortcut+F in the pane focuses the search, elsewhere the log's text filter.
        press(robot, branches.tree, KeyCode.F, shortcut = true)
        assertSame<Node?>(branches.search, view.scene.focusOwner)
        assertEquals("old", branches.search.selectedText)
        robot.interact { branches.search.positionCaret(1) }
        press(robot, branches.search, KeyCode.F, shortcut = true)
        assertSame<Node?>(branches.search, view.scene.focusOwner)
        assertEquals("old", branches.search.selectedText)
        press(robot, view.table, KeyCode.F, shortcut = true)
        assertSame<Node?>(bar.text, view.scene.focusOwner)

        // Enter too, without waiting for the pause.
        robot.interact {
            branches.search.requestFocus()
            branches.search.text = "fix"
        }
        press(robot, branches.search, KeyCode.ENTER)
        assertSame<Node?>(branches.tree, view.scene.focusOwner)
        assertEquals("refs/heads/team/fix/x", branches.selectedRef?.name)
    }

    @Test
    fun `selecting a branch selects its commit and scrolls to it, the focus staying in the tree`(robot: FxRobot) {
        openBranches(robot)
        robot.interact { branches.tree.requestFocus() }

        clickBranch(robot, branchCell("Local/feature/b"))
        assertEquals("refs/heads/feature/b", branches.selectedRef?.name)
        eventually {
            assertEquals(
                listOf(6),
                view.table.selectionModel.selectedIndices
                    .toList(),
            )
        }
        assertSame<Node?>(branches.tree, view.scene.focusOwner)
        eventually { assertTrue(showsDetailsOf("Subject 7")) }
        val top = WaitForAsyncUtils.asyncFx<Int> { visibleRows().first }.get()
        assertEquals(0, top, "in view already: not scrolled")

        clickBranch(robot, branchCell("Remote/origin/old"))
        eventually {
            assertEquals(
                listOf(39),
                view.table.selectionModel.selectedIndices
                    .toList(),
            )
        }
        eventually {
            val rows = visibleRows()
            assertTrue(39 > rows.first && 39 < rows.last, "$rows")
        }
        assertSame<Node?>(branches.tree, view.scene.focusOwner)

        // Groups, folders and several branches do not navigate.
        clickBranch(robot, branchCell("Local"))
        selectBranches(robot, "Local/team/fix")
        selectBranches(robot, "Local/main", "Local/feature/a")
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(
            listOf(39),
            view.table.selectionModel.selectedIndices
                .toList(),
        )

        // The keyboard moves through the branches.
        selectBranches(robot, "Local/feature/a")
        eventually {
            assertEquals(
                listOf(4),
                view.table.selectionModel.selectedIndices
                    .toList(),
            )
        }
        press(robot, branches.tree, KeyCode.DOWN)
        eventually {
            assertEquals(
                listOf(6),
                view.table.selectionModel.selectedIndices
                    .toList(),
            )
        }
        assertEquals(listOf(0 to 500), loads.toList())
    }

    @Test
    fun `a navigation cut off by a refresh goes on after it`(robot: FxRobot) {
        openBranches(robot)
        gate = CountDownLatch(1)

        // The second page waits; the refresh starts a new run, which drops it.
        selectBranches(robot, "Remote/upstream/far")
        robot.interact { view.refresh() }
        gate?.countDown()
        gate = null

        eventually {
            assertEquals(
                listOf(799),
                view.table.selectionModel.selectedIndices
                    .toList(),
            )
        }
        assertEquals("", status(robot))
    }

    @Test
    fun `a branch further down loads pages until its commit is there`(robot: FxRobot) {
        openBranches(robot)

        selectBranches(robot, "Remote/upstream/far")

        eventually {
            assertEquals(
                listOf(799),
                view.table.selectionModel.selectedIndices
                    .toList(),
            )
        }
        assertEquals(listOf(0 to 500, 500 to 500), loads.toList().take(2))
        eventually {
            val rows = visibleRows()
            assertTrue(799 in rows.first + 1..<rows.last, "$rows")
        }
        assertEquals("", status(robot))
    }

    @Test
    fun `a branch beyond 10,000 commits, out of the log or hidden by the filters is told in the header`(
        robot: FxRobot,
    ) {
        history = commits(10_200)
        refs =
            GitRefs(
                branchRefs.refs +
                    listOf(
                        ref("refs/heads/deep", GitRefKind.LOCAL_BRANCH, "h10100"),
                        ref("refs/heads/lost", GitRefKind.LOCAL_BRANCH, "nowhere"),
                    ),
                "refs/heads/main",
            )
        openAndExpand(robot)
        eventually {
            assertTrue(
                branches.tree.root.children
                    .isNotEmpty(),
            )
        }

        selectBranches(robot, "Local/deep")
        eventually { assertEquals("deep is more than 10,000 commits down.", status(robot)) }
        assertEquals(10_000, view.table.items.size)

        // Not further down: the log is complete without it.
        history = commits(1200)
        robot.interact { view.refresh() }
        eventually { assertEquals(500, view.table.items.size) }
        selectBranches(robot, "Local/lost")
        eventually { assertEquals("lost is not in the log.", status(robot)) }
        assertEquals(1200, view.table.items.size)

        selectBranches(robot, "Local/main")
        eventually {
            assertEquals(
                listOf(0),
                view.table.selectionModel.selectedIndices
                    .toList(),
            )
        }
        assertEquals("", status(robot), "cleared by the next selection")

        robot.interact { bar.text.text = "Subject 12" }
        press(robot, bar.text, KeyCode.ENTER)
        eventually { assertEquals("Subject 12", filters.last().text) }
        eventually { assertEquals(12, view.table.items.size) }
        selectBranches(robot, "Local/feature/a")
        eventually { assertEquals("feature/a is hidden by the filters.", status(robot)) }
    }

    @Test
    fun `double-click, Enter and Filter Log by Branch filter the log by the selected branches`(robot: FxRobot) {
        reachable =
            mapOf(
                "refs/heads/feature/a" to setOf("h5", "h6"),
                "refs/heads/feature/b" to setOf("h7"),
                "HEAD" to setOf("h1", "h2"),
            )
        openBranches(robot)
        robot.interact { branches.tree.requestFocus() }

        val cell = branchCell("Local/feature/a")
        clickBranch(robot, cell)
        clickBranch(robot, cell, 2)
        eventually { assertEquals(listOf("refs/heads/feature/a"), filters.last().revisions) }
        eventually { assertEquals(listOf("h5", "h6"), view.table.items.map { it.commit.hash }) }
        assertEquals("Branch: feature/a", bar.branch.label.text)
        assertTrue(bar.branch.clear.isVisible && bar.branch.clear.isManaged)
        robot.interact { bar.branch.button.fire() }
        assertEquals("feature/a", items(bar.branch.popup).first(), "checked first")
        robot.interact {
            val checked =
                bar.branch.popup.list
                    .lookupAll(".list-cell")
                    .filterIsInstance<ListCell<*>>()
                    .single { (it.item as? FilterItem)?.text == "feature/a" }
                    .lookup(".check-box") as javafx.scene.control.CheckBox
            assertTrue(checked.isSelected)
            bar.branch.popup.hide()
        }

        // Enter with several selected: all of them, replacing the filter.
        selectBranches(robot, "HEAD", "Local/feature/b")
        assertEquals("Filter Log by 2 Branches", branches.filterItem.text)
        press(robot, branches.tree, KeyCode.ENTER)
        eventually { assertEquals(listOf("HEAD", "refs/heads/feature/b"), filters.last().revisions) }
        assertEquals("Branch: HEAD +1", bar.branch.label.text)

        // The context menu's item, for one branch.
        selectBranches(robot, "Local/feature/a")
        assertEquals("Filter Log by Branch", branches.filterItem.text)
        assertFalse(branches.filterItem.isDisable)
        val loadsBefore = filters.size
        robot.interact { branches.filterItem.fire() }
        eventually { assertEquals(listOf("refs/heads/feature/a"), filters.last().revisions) }
        assertEquals(loadsBefore + 1, filters.size, "applied once")

        // Enter on a folder opens or closes it.
        selectBranches(robot, "Local/feature")
        assertTrue(branches.filterItem.isDisable)
        assertTrue(branches.copyNameItem.isDisable)
        press(robot, branches.tree, KeyCode.ENTER)
        assertFalse(branchItem("Local/feature").isExpanded)
        press(robot, branches.tree, KeyCode.ENTER)
        assertTrue(branchItem("Local/feature").isExpanded)
        assertEquals(listOf("refs/heads/feature/a"), filters.last().revisions)
    }

    @Test
    fun `Copy Name copies the short names of the selected branches`(robot: FxRobot) {
        openBranches(robot)
        assertEquals(
            listOf("Filter Log by Branch", null, "Copy Name"),
            branches.tree.contextMenu.items
                .take(3)
                .map { it.text },
        )

        selectBranches(robot, "Remote/origin/old", "HEAD", "Local/feature/b")
        assertFalse(branches.copyNameItem.isDisable)
        robot.interact { branches.copyNameItem.fire() }
        assertEquals("HEAD\nfeature/b\norigin/old", clipboard())
    }

    @Test
    fun `a refresh keeps the selected branch and collapsed folders, opening a folder clears search and selection`(
        robot: FxRobot,
    ) {
        openBranches(robot)
        selectBranches(robot, "Local/feature/b")
        eventually {
            assertEquals(
                listOf(6),
                view.table.selectionModel.selectedIndices
                    .toList(),
            )
        }
        robot.interact {
            branchItem("Local/team/fix").isExpanded = false
            branchItem("Tags").isExpanded = true
            // Another commit, chosen in the table: an unchanged branch does not take the selection back.
            view.table.selectionModel.clearAndSelect(2)
        }
        refs = GitRefs(branchRefs.refs + ref("refs/heads/new", GitRefKind.LOCAL_BRANCH, "h2"), "refs/heads/main")

        robot.interact { view.refresh() }

        eventually { assertTrue("  new" in shownBranches()) }
        assertEquals("refs/heads/feature/b", branches.selectedRef?.name)
        assertFalse(branchItem("Local/team/fix").isExpanded)
        assertTrue(branchItem("Tags").isExpanded)
        assertEquals(
            listOf(2),
            view.table.selectionModel.selectedIndices
                .toList(),
            "not navigated again",
        )

        // A moved branch: followed only while the user is still on it.
        fun moveFeatureB(to: String) {
            refs =
                GitRefs(
                    branchRefs.refs.map { if (it.name == "refs/heads/feature/b") it.copy(commit = to) else it },
                    "refs/heads/main",
                )
            robot.interact { view.refresh() }
            eventually { assertEquals(to, branches.selectedRef?.commit) }
            WaitForAsyncUtils.waitForFxEvents()
        }

        fun selectedRows() =
            WaitForAsyncUtils
                .asyncFx<List<Int>> {
                    view.table.selectionModel.selectedIndices
                        .toList()
                }.get()

        // Another commit selected in the table: it stays.
        moveFeatureB("h9")
        assertEquals(listOf(2), selectedRows())

        // Its old commit still selected: the new one is.
        robot.interact { view.table.selectionModel.clearAndSelect(8) }
        moveFeatureB("h11")
        eventually { assertEquals(listOf(10), selectedRows()) }

        // Not while the pane is hidden.
        robot.interact { bar.branchesToggle.isSelected = false }
        moveFeatureB("h13")
        assertEquals(listOf(10), selectedRows())
        robot.interact { bar.branchesToggle.isSelected = true }

        // Opening a folder while expanded says "Loading…" until its refs are there.
        gate = CountDownLatch(1)
        robot.interact { view.open(repo) }
        assertEquals("Loading…", branches.placeholder.text)
        assertTrue(branches.placeholder.isVisible)
        gate?.countDown()
        gate = null

        robot.interact {
            branches.search.text = "feat"
            bar.branchesToggle.isSelected = false
            view.open(repo)
            bar.branchesToggle.isSelected = true
        }
        eventually {
            assertTrue(
                branches.tree.root.children
                    .isNotEmpty(),
            )
        }
        assertEquals("", branches.search.text)
        assertTrue(branches.tree.selectionModel.isEmpty)
        assertFalse(branchItem("Tags").isExpanded, "collapsed again")

        robot.interact {
            bar.branchesToggle.isSelected = false
            view.open(repo)
        }
        assertFalse(bar.branchesToggle.isSelected, "open keeps the pane hidden")
        assertEquals(listOf<Node>(view.inner), view.body.items)
    }

    @Test
    fun `a narrow filter bar shrinks the text field, not the buttons, and ellipsizes a long branch`(robot: FxRobot) {
        openBranches(robot)

        fun shown(label: Label) = (label.lookup(".text") as Text).text

        robot.interact {
            assertTrue(bar.width < bar.prefWidth(-1.0), "narrow: ${bar.width} of ${bar.prefWidth(-1.0)}")
            assertTrue(bar.search.width < bar.search.prefWidth(-1.0) - 1, "${bar.search.width}")
            assertTrue(bar.search.width >= 100 - 0.5, "${bar.search.width}")
            for (button in listOf(bar.branch, bar.user, bar.path)) {
                assertEquals(button.prefWidth(-1.0), button.width, 0.5, button.label.text)
                assertEquals(button.label.text, shown(button.label))
            }
        }

        robot.interact { bar.setBranches(listOf("refs/heads/a-very-long-branch-name-that-does-not-fit")) }
        eventually {
            assertTrue(bar.branch.label.width <= 160.5, "${bar.branch.label.width}")
            assertTrue(shown(bar.branch.label).endsWith("..."), shown(bar.branch.label))
        }
        robot.interact {
            for (button in listOf(bar.user, bar.path)) {
                assertEquals(button.prefWidth(-1.0), button.width, 0.5, button.label.text)
                assertEquals(button.label.text, shown(button.label))
            }
        }
    }
}
