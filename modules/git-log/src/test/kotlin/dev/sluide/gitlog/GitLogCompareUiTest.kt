package dev.sluide.gitlog

import dev.sluide.git.GitChangedFile
import dev.sluide.git.GitCommit
import dev.sluide.git.GitCommitDetails
import dev.sluide.git.GitComparisonCounts
import dev.sluide.git.GitFileDiff
import dev.sluide.git.GitLogFilter
import dev.sluide.git.GitLogPage
import dev.sluide.git.GitRef
import dev.sluide.git.GitRefKind
import dev.sluide.git.GitRefs
import dev.sluide.git.GitTreePath
import javafx.application.Platform
import javafx.event.Event
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.control.TableRow
import javafx.scene.control.TreeItem
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.stage.Stage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Compare with Current in the real git log view with a fake source (ADR 0044). Needs a display. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class GitLogCompareUiTest {
    private val repo: Path = Path.of("/repo")

    private val main = GitRef("refs/heads/main", GitRefKind.LOCAL_BRANCH, "m2")
    private val feature = GitRef("refs/heads/feature", GitRefKind.LOCAL_BRANCH, "f2")
    private val tag = GitRef("refs/tags/v1", GitRefKind.TAG, "b1")

    /** The refs of the repository: `HEAD` on main. */
    @Volatile
    private var refs: GitRefs? = GitRefs(listOf(GitRef("HEAD", GitRefKind.HEAD, "m2"), main, feature, tag), main.name)

    private val featureOnly = listOf(commit("f2", "Feature 2", "f1"), commit("f1", "Feature 1", "b1"))
    private val mainOnly = listOf(commit("m2", "Main 2", "m1"), commit("m1", "Main 1", "b1"))
    private val base = listOf(commit("b1", "Base"))

    /** The counts of comparisons by refname. */
    @Volatile
    private var counts: Map<String, GitComparisonCounts> = mapOf(feature.name to GitComparisonCounts(2, 2))

    /** The filter of every load. */
    private val filters = CopyOnWriteArrayList<GitLogFilter>()

    private lateinit var view: GitLogView

    /** The commits of the log: the range of a comparison, else all by date; the text matches the subject. */
    private inner class FakeSource : GitLogSource {
        override fun page(
            root: Path,
            filter: GitLogFilter,
            skip: Int,
            limit: Int,
        ): GitLogPage {
            filters += filter
            val listed =
                when {
                    filter.excluded == listOf("HEAD") -> {
                        if (filter.revisions ==
                            listOf(feature.name)
                        ) {
                            featureOnly
                        } else {
                            emptyList()
                        }
                    }

                    filter.excluded.isNotEmpty() -> {
                        mainOnly
                    }

                    else -> {
                        (featureOnly + mainOnly).sortedByDescending { it.hash.last() } + base
                    }
                }.filter { it.subject.contains(filter.text.trim(), ignoreCase = true) }
            return GitLogPage(listed.drop(skip).take(limit), complete = true)
        }

        override fun hashMatch(
            root: Path,
            filter: GitLogFilter,
        ): GitCommit? = null

        override fun userName(root: Path): String? = null

        override fun paths(root: Path): List<GitTreePath> = emptyList()

        override fun refs(root: Path) = refs

        override fun details(
            root: Path,
            hash: String,
        ): GitCommitDetails? = null

        override fun changedFiles(
            root: Path,
            commit: String,
            firstParent: String?,
        ): List<GitChangedFile> = emptyList()

        override fun fileDiff(
            root: Path,
            commit: String,
            firstParent: String?,
            file: GitChangedFile,
        ): GitFileDiff? = null

        override fun comparisonCounts(
            root: Path,
            revision: String,
            filter: GitLogFilter,
        ) = counts[revision]
    }

    private fun commit(
        hash: String,
        subject: String,
        vararg parents: String,
    ) = GitCommit(hash, parents.toList(), "A", "a@example.com", Instant.EPOCH, subject)

    @Start
    fun start(stage: Stage) {
        view = GitLogView(FakeSource(), Platform::runLater, FakeWrites())
        stage.scene = Scene(view, 1000.0, 400.0)
        stage.show()
    }

    @AfterEach
    fun close() {
        view.close()
    }

    private fun eventually(assertion: () -> Unit) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (true) {
            WaitForAsyncUtils.waitForFxEvents()
            val failure = WaitForAsyncUtils.asyncFx<Throwable?> { runCatching(assertion).exceptionOrNull() }.get()
            if (failure == null) return
            if (System.nanoTime() > deadline) throw failure
            Thread.sleep(20)
        }
    }

    private fun openAndExpand(robot: FxRobot) {
        robot.interact {
            view.open(repo)
            view.expanded.set(true)
        }
        eventually { assertTrue(view.table.items.isNotEmpty()) }
        eventually {
            assertTrue(
                view.branchesPane.tree.root.children
                    .isNotEmpty(),
            )
        }
    }

    private fun subjects() = view.table.items.map { it.commit.subject }

    private fun shownSubjects(robot: FxRobot): List<String> =
        robot
            .lookup(".git-log-table .table-row-cell")
            .queryAllAs(TableRow::class.java)
            .filter { !it.isEmpty && it.item != null }
            .sortedBy { it.index }
            .map { (it.item as GitLogRow).commit.subject }

    private fun placeholder(robot: FxRobot) =
        robot.lookup(".git-log-table .placeholder .label").queryAs(Label::class.java).text

    private fun status(robot: FxRobot) = robot.lookup(".git-log-status").queryAs(Label::class.java).text

    private fun branchItem(path: String): TreeItem<BranchNode> {
        fun find(item: TreeItem<BranchNode>): TreeItem<BranchNode>? =
            if (item.value?.path == path) item else item.children.firstNotNullOfOrNull(::find)
        return checkNotNull(find(view.branchesPane.tree.root)) { path }
    }

    /** Selects the nodes at [paths] in the branches tree, as with the mouse and Shift/Ctrl. */
    private fun selectBranches(
        robot: FxRobot,
        vararg paths: String,
    ) {
        robot.interact {
            val tree = view.branchesPane.tree
            branchItem(TAGS).isExpanded = true
            val rows = paths.map { tree.getRow(branchItem(it)) }
            tree.selectionModel.clearSelection()
            tree.selectionModel.selectIndices(rows.first(), *rows.drop(1).toIntArray())
        }
        WaitForAsyncUtils.waitForFxEvents()
    }

    private val pane get() = view.branchesPane

    @Test
    fun `Compare with Current and Show Diff with Working Tree are offered for single refs`(robot: FxRobot) {
        openAndExpand(robot)

        fun offered(vararg paths: String): Pair<Boolean, Boolean> {
            selectBranches(robot, *paths)
            return WaitForAsyncUtils
                .asyncFx<Pair<Boolean, Boolean>> { !pane.compareItem.isDisable to !pane.workingTreeItem.isDisable }
                .get()
        }

        assertEquals(true to true, offered("$LOCAL/feature"))
        assertEquals(true to true, offered("$TAGS/v1"))
        assertEquals(false to true, offered("$LOCAL/main"), "the current branch")
        assertEquals(false to true, offered("HEAD"))
        assertEquals(false to false, offered(LOCAL), "a group")
        assertEquals(false to false, offered("$LOCAL/feature", "$TAGS/v1"), "several")
    }

    @Test
    fun `comparing lists one side with its counts, the other filters on top, closing restores them`(robot: FxRobot) {
        openAndExpand(robot)
        val bar = view.filterBar
        robot.interact {
            bar.setBranches(listOf(main.name))
            bar.text.text = "1"
        }
        robot.interact { bar.debounce.jumpTo(bar.debounce.totalDuration) }
        eventually { assertEquals(GitLogFilter(text = "1", revisions = listOf(main.name)), filters.last()) }
        assertFalse(view.compareBar.isVisible)

        selectBranches(robot, "$LOCAL/feature")
        robot.interact { pane.compareItem.fire() }

        eventually {
            assertEquals(
                GitLogFilter(text = "1", revisions = listOf(feature.name), excluded = listOf("HEAD")),
                filters.last(),
            )
            assertEquals(listOf("Feature 1"), shownSubjects(robot))
            assertEquals("In feature, not in main (2)", view.refOnlyToggle.text)
        }
        robot.interact {
            assertTrue(view.compareBar.isVisible)
            assertEquals("Comparing feature with main", view.compareLabel.text)
            assertEquals("In main, not in feature (2)", view.headOnlyToggle.text)
            assertTrue(view.refOnlyToggle.isSelected)
            assertFalse(view.headOnlyToggle.isSelected)
            assertTrue(bar.branch.isDisabled, "the branch filter is set aside")
            assertEquals("Stop Comparing", view.stopCompareButton.tooltip.text)
        }

        robot.interact { view.headOnlyToggle.fire() }
        eventually { assertEquals(listOf("Main 1"), shownSubjects(robot)) }
        // A click on the listed side keeps it listed.
        robot.interact { view.headOnlyToggle.fire() }
        robot.interact {
            assertTrue(view.headOnlyToggle.isSelected)
            assertFalse(view.refOnlyToggle.isSelected)
        }

        robot.interact { bar.text.text = "" }
        robot.interact { bar.debounce.jumpTo(bar.debounce.totalDuration) }
        eventually { assertEquals(listOf("Main 2", "Main 1"), subjects()) }

        robot.interact { view.stopCompareButton.fire() }
        eventually { assertEquals(GitLogFilter(revisions = listOf(main.name)), filters.last()) }
        robot.interact {
            assertFalse(view.compareBar.isVisible)
            assertFalse(bar.branch.isDisabled)
            assertEquals("Branch: main", bar.branch.label.text)
        }
    }

    @Test
    fun `Escape in the table stops comparing, filtering by branch too`(robot: FxRobot) {
        openAndExpand(robot)
        selectBranches(robot, "$LOCAL/feature")
        robot.interact { pane.compareItem.fire() }
        eventually { assertEquals(listOf("Feature 2", "Feature 1"), subjects()) }

        robot.interact {
            Event.fireEvent(
                view.table,
                KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false),
            )
        }
        eventually { assertEquals(GitLogFilter(), filters.last()) }
        robot.interact { assertFalse(view.compareBar.isVisible) }

        robot.interact { pane.compareItem.fire() }
        eventually { assertTrue(view.compareBar.isVisible) }
        robot.interact { pane.filterItem.fire() }
        eventually { assertEquals(GitLogFilter(revisions = listOf(feature.name)), filters.last()) }
        robot.interact { assertFalse(view.compareBar.isVisible) }
    }

    @Test
    fun `an empty side says so, and a refresh without the compared ref stops comparing`(robot: FxRobot) {
        openAndExpand(robot)
        selectBranches(robot, "$TAGS/v1")
        counts = mapOf(tag.name to GitComparisonCounts(0, 5))
        robot.interact { pane.compareItem.fire() }
        eventually {
            assertEquals(emptyList(), subjects())
            assertEquals("In v1, not in main (0)", view.refOnlyToggle.text)
            assertEquals("No commits in v1 that are not in main.", placeholder(robot))
        }
        robot.interact { view.headOnlyToggle.fire() }
        eventually { assertEquals(listOf("Main 2", "Main 1"), subjects()) }
        // A selected commit is selected again by the reload, which must not take the message away.
        robot.interact { view.table.selectionModel.clearAndSelect(1) }

        refs = refs?.let { GitRefs(it.refs.filter { ref -> ref != tag }, it.currentBranch) }
        robot.interact { view.refresh() }
        eventually {
            assertFalse(view.compareBar.isVisible)
            assertEquals(GitLogFilter(), filters.last())
            assertEquals(5, subjects().size)
            assertEquals(
                listOf("Main 1"),
                view.table.selectionModel.selectedItems
                    .map { it.commit.subject },
            )
        }
        WaitForAsyncUtils.waitForFxEvents()
        robot.interact { assertEquals("v1 no longer exists.", status(robot)) }
    }

    @Test
    fun `a branch outside the range is not in this comparison`(robot: FxRobot) {
        openAndExpand(robot)
        selectBranches(robot, "$LOCAL/feature")
        robot.interact { pane.compareItem.fire() }
        eventually { assertEquals(listOf("Feature 2", "Feature 1"), subjects()) }

        selectBranches(robot, "$LOCAL/main")

        eventually { assertEquals("main is not in this comparison.", status(robot)) }
    }

    @Test
    fun `with other filters an empty side says that nothing matches, without counts the sides have none`(
        robot: FxRobot,
    ) {
        openAndExpand(robot)
        selectBranches(robot, "$LOCAL/feature")
        robot.interact { pane.compareItem.fire() }
        robot.interact { view.filterBar.text.text = "nothing" }
        robot.interact { view.filterBar.debounce.jumpTo(view.filterBar.debounce.totalDuration) }
        eventually { assertEquals("No commits match the filters.", placeholder(robot)) }
        robot.interact { view.filterBar.text.text = "" }
        robot.interact { view.filterBar.debounce.jumpTo(view.filterBar.debounce.totalDuration) }
        eventually { assertEquals(listOf("Feature 2", "Feature 1"), subjects()) }
        counts = emptyMap()
        robot.interact { view.headOnlyToggle.fire() }
        eventually { assertEquals("In main, not in feature", view.headOnlyToggle.text, "no counts") }
    }
}
