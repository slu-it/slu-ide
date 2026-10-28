package dev.sluide.gitlog

import dev.sluide.git.GitCancellation
import dev.sluide.git.GitChange
import dev.sluide.git.GitChangedFile
import dev.sluide.git.GitCommit
import dev.sluide.git.GitCommitChanges
import dev.sluide.git.GitCommitDetails
import dev.sluide.git.GitComparisonCounts
import dev.sluide.git.GitFileDiff
import dev.sluide.git.GitHunk
import dev.sluide.git.GitLogFilter
import dev.sluide.git.GitLogPage
import dev.sluide.git.GitRef
import dev.sluide.git.GitRefKind
import dev.sluide.git.GitRefs
import dev.sluide.git.GitTreePath
import dev.sluide.git.GitWorkingTreeChanges
import dev.sluide.gitlog.data.ChangeFile
import dev.sluide.gitlog.data.ChangeFolder
import dev.sluide.gitlog.data.ChangeNode
import dev.sluide.gitlog.data.GitLogSource
import dev.sluide.gitlog.data.LOCAL
import dev.sluide.gitlog.data.TAGS
import dev.sluide.gitlog.workingtree.WorkingTreeWindow
import javafx.event.Event
import javafx.scene.control.TreeItem
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.stage.Screen
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Compare with Current in the real git log view with a fake source (ADR 0044). Needs a display. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class GitLogCompareUiTest : GitLogWritesUiFixture() {
    private val main = GitRef("refs/heads/main", GitRefKind.LOCAL_BRANCH, "m2")
    private val feature = GitRef("refs/heads/feature", GitRefKind.LOCAL_BRANCH, "f2")
    private val tag = GitRef("refs/tags/v1", GitRefKind.TAG, "b1")

    /** The refs of the repository: `HEAD` on main. */
    @Volatile
    private var compareRefs: GitRefs? =
        GitRefs(listOf(GitRef("HEAD", GitRefKind.HEAD, "m2"), main, feature, tag), main.name)

    private val featureOnly = listOf(commit("f2", "Feature 2", "f1"), commit("f1", "Feature 1", "b1"))
    private val mainOnly = listOf(commit("m2", "Main 2", "m1"), commit("m1", "Main 1", "b1"))
    private val base = listOf(commit("b1", "Base"))

    /** The counts of comparisons by refname. */
    @Volatile
    private var counts: Map<String, GitComparisonCounts> = mapOf(feature.name to GitComparisonCounts(2, 2))

    /** The filter of every load. */
    private val filters = CopyOnWriteArrayList<GitLogFilter>()

    private val someFiles =
        listOf(
            GitChangedFile(GitChange.MODIFIED, "src/One.kt"),
            GitChangedFile(GitChange.ADDED, "src/Two.kt"),
            GitChangedFile(GitChange.DELETED, "README.md"),
            GitChangedFile(GitChange.RENAMED, "docs/new.md", "docs/old.md"),
        )

    /** What the files that differ from the working tree are, by revision. */
    @Volatile
    private var workingTree: (String) -> GitWorkingTreeChanges? = { revision ->
        GitWorkingTreeChanges.Files("commit-of-$revision", someFiles)
    }

    /** The revision of every read of the files that differ from the working tree. */
    private val workingTreeLoads = CopyOnWriteArrayList<String>()

    /** How a file differs from the working tree. */
    @Volatile
    private var diffWithWorkingTree: (GitChangedFile) -> GitFileDiff? = { file ->
        GitFileDiff.Text("old ${file.path}\n", "new ${file.path}\n", listOf(GitHunk(0..0, 0..0)))
    }

    /** The (commit, path) of every read of a diff with the working tree. */
    private val diffLoadsWithWorkingTree = CopyOnWriteArrayList<Pair<String, String>>()

    /** The commits of the log: the range of a comparison, else all by date; the text matches the subject. */
    private inner class FakeSource : GitLogSource {
        override fun page(
            root: Path,
            filter: GitLogFilter,
            skip: Int,
            limit: Int,
            cancellation: GitCancellation?,
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
            cancellation: GitCancellation?,
        ): GitCommit? = null

        override fun userName(root: Path): String? = null

        override fun paths(root: Path): List<GitTreePath> = emptyList()

        override fun refs(
            root: Path,
            cancellation: GitCancellation?,
        ) = compareRefs

        override fun details(
            root: Path,
            hash: String,
            cancellation: GitCancellation?,
        ): GitCommitDetails? = null

        override fun changedFiles(
            root: Path,
            commit: String,
            firstParent: String?,
            cancellation: GitCancellation?,
        ): List<GitChangedFile> = emptyList()

        override fun fileDiff(
            root: Path,
            commit: String,
            firstParent: String?,
            file: GitChangedFile,
            cancellation: GitCancellation?,
        ): GitFileDiff? = null

        override fun comparisonCounts(
            root: Path,
            revision: String,
            filter: GitLogFilter,
            cancellation: GitCancellation?,
        ) = counts[revision]

        override fun workingTreeChanges(
            root: Path,
            revision: String,
        ): GitWorkingTreeChanges? {
            workingTreeLoads += revision
            return workingTree(revision)
        }

        override fun workingTreeFileDiff(
            root: Path,
            commit: String,
            file: GitChangedFile,
        ): GitFileDiff? {
            diffLoadsWithWorkingTree += commit to file.path
            return diffWithWorkingTree(file)
        }

        override fun commitChanges(root: Path): GitCommitChanges? = null

        override fun headCommit(
            root: Path,
            cancellation: GitCancellation?,
        ): String? = null

        override fun headMessage(root: Path): String? = null
    }

    private fun commit(
        hash: String,
        subject: String,
        vararg parents: String,
    ) = GitCommit(hash, parents.toList(), "A", "a@example.com", Instant.EPOCH, subject)

    @Start
    fun start(stage: Stage) = startView(stage)

    override fun ownSource(): GitLogSource = FakeSource()

    private fun subjects() = view.table.items.map { it.commit.subject }

    private val pane get() = view.branchesPane

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
        robot.interact { pane.menu.compareItem.fire() }

        eventually {
            assertEquals(
                GitLogFilter(text = "1", revisions = listOf(feature.name), excluded = listOf("HEAD")),
                filters.last(),
            )
            assertEquals(listOf("Feature 1"), shownSubjects(robot))
            assertEquals("In feature, not in main (2)", view.compareBar.refOnlyToggle.text)
        }
        robot.interact {
            assertTrue(view.compareBar.isVisible)
            assertEquals("Comparing feature with main", view.compareBar.compareLabel.text)
            assertEquals("In main, not in feature (2)", view.compareBar.headOnlyToggle.text)
            assertTrue(view.compareBar.refOnlyToggle.isSelected)
            assertFalse(view.compareBar.headOnlyToggle.isSelected)
            assertTrue(bar.branch.isDisabled, "the branch filter is set aside")
            assertEquals("Stop Comparing", view.compareBar.stopCompareButton.tooltip.text)
        }

        robot.interact { view.compareBar.headOnlyToggle.fire() }
        eventually { assertEquals(listOf("Main 1"), shownSubjects(robot)) }
        // A click on the listed side keeps it listed.
        robot.interact { view.compareBar.headOnlyToggle.fire() }
        robot.interact {
            assertTrue(view.compareBar.headOnlyToggle.isSelected)
            assertFalse(view.compareBar.refOnlyToggle.isSelected)
        }

        robot.interact { bar.text.text = "" }
        robot.interact { bar.debounce.jumpTo(bar.debounce.totalDuration) }
        eventually { assertEquals(listOf("Main 2", "Main 1"), subjects()) }

        robot.interact { view.compareBar.stopCompareButton.fire() }
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
        robot.interact { pane.menu.compareItem.fire() }
        eventually { assertEquals(listOf("Feature 2", "Feature 1"), subjects()) }

        robot.interact {
            Event.fireEvent(
                view.table,
                KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false),
            )
        }
        eventually { assertEquals(GitLogFilter(), filters.last()) }
        robot.interact { assertFalse(view.compareBar.isVisible) }

        robot.interact { pane.menu.compareItem.fire() }
        eventually { assertTrue(view.compareBar.isVisible) }
        robot.interact { pane.menu.filterItem.fire() }
        eventually { assertEquals(GitLogFilter(revisions = listOf(feature.name)), filters.last()) }
        robot.interact { assertFalse(view.compareBar.isVisible) }
    }

    @Test
    fun `an empty side says so, and a refresh without the compared ref stops comparing`(robot: FxRobot) {
        openAndExpand(robot)
        selectBranches(robot, "$TAGS/v1")
        counts = mapOf(tag.name to GitComparisonCounts(0, 5))
        robot.interact { pane.menu.compareItem.fire() }
        eventually {
            assertEquals(emptyList(), subjects())
            assertEquals("In v1, not in main (0)", view.compareBar.refOnlyToggle.text)
            assertEquals("No commits in v1 that are not in main.", placeholder(robot))
        }
        robot.interact { view.compareBar.headOnlyToggle.fire() }
        eventually { assertEquals(listOf("Main 2", "Main 1"), subjects()) }
        // A selected commit is selected again by the reload, which must not take the message away.
        robot.interact { view.table.selectionModel.clearAndSelect(1) }

        compareRefs = compareRefs?.let { GitRefs(it.refs.filter { ref -> ref != tag }, it.currentBranch) }
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
        robot.interact { pane.menu.compareItem.fire() }
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
        robot.interact { pane.menu.compareItem.fire() }
        robot.interact { view.filterBar.text.text = "nothing" }
        robot.interact { view.filterBar.debounce.jumpTo(view.filterBar.debounce.totalDuration) }
        eventually { assertEquals("No commits match the filters.", placeholder(robot)) }
        robot.interact { view.filterBar.text.text = "" }
        robot.interact { view.filterBar.debounce.jumpTo(view.filterBar.debounce.totalDuration) }
        eventually { assertEquals(listOf("Feature 2", "Feature 1"), subjects()) }
        counts = emptyMap()
        robot.interact { view.compareBar.headOnlyToggle.fire() }
        eventually { assertEquals("In main, not in feature", view.compareBar.headOnlyToggle.text, "no counts") }
    }

    /** The working-tree window open for [revision], once it is there. */
    private fun windowOf(revision: String): WorkingTreeWindow {
        var window: WorkingTreeWindow? = null
        eventually { window = checkNotNull(view.diffs.workingTreeWindows[revision]) }
        return checkNotNull(window)
    }

    /** The expanded tree of [window] as names, folders ending with `/`. */
    private fun treeOf(window: WorkingTreeWindow): List<String> {
        fun lines(item: TreeItem<ChangeNode>): List<String> =
            item.children.flatMap { child ->
                when (val node = child.value) {
                    is ChangeFolder -> listOf("${node.name}/") + if (child.isExpanded) lines(child) else emptyList()
                    is ChangeFile -> listOf(node.name)
                }
            }
        return if (window.tree.isVisible) lines(window.tree.root) else emptyList()
    }

    private fun selectFile(
        window: WorkingTreeWindow,
        name: String,
    ) {
        val tree = window.tree
        val index = (0 until tree.expandedItemCount).single { tree.getTreeItem(it).value.name == name }
        tree.selectionModel.clearAndSelect(index)
    }

    private fun press(
        window: WorkingTreeWindow,
        code: KeyCode,
    ) {
        Event.fireEvent(window.tree, KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false))
    }

    @Test
    fun `Show Diff with Working Tree opens a window with the files that differ, once per ref`(robot: FxRobot) {
        openAndExpand(robot)
        selectBranches(robot, "$LOCAL/feature")
        robot.interact { pane.menu.workingTreeItem.fire() }

        val window = windowOf(feature.name)
        eventually { assertEquals(listOf("docs/", "new.md", "src/", "One.kt", "Two.kt", "README.md"), treeOf(window)) }
        robot.interact {
            assertTrue(window.stage.isShowing)
            assertEquals("feature ↔ Working Tree", window.stage.title)
            // Not owned, so that the IDE window can come in front of it; at the IDE window's right edge.
            assertEquals(null, window.stage.owner)
            val ide = view.scene.window
            val area = Screen.getScreensForRectangle(ide.x, ide.y, ide.width, ide.height).first().visualBounds
            val right = minOf(ide.x + ide.width, area.maxX)
            assertEquals(right, window.stage.x + window.stage.width, 1.0)
            assertTrue(window.stage.y >= area.minY && window.stage.y + window.stage.height <= area.maxY + 1)
            assertEquals("4 files differ", window.header.text)
            assertEquals("Untracked files are not included.", window.note.text)
            assertTrue(
                window.stage.scene.root.styleClass
                    .contains("git-log"),
                "the colours of the changes",
            )
        }
        assertEquals(feature.name, workingTreeLoads.first())

        // Again: the same window.
        robot.interact { pane.menu.workingTreeItem.fire() }
        robot.interact {
            assertEquals(1, view.diffs.workingTreeWindows.size)
            assertTrue(window.stage.isShowing)
        }

        workingTree = { GitWorkingTreeChanges.Files("c2", someFiles.take(1)) }
        robot.interact { window.refreshButton.fire() }
        eventually {
            assertEquals("1 file differs", window.header.text)
            assertEquals(listOf("src/", "One.kt"), treeOf(window))
        }
        workingTree = { GitWorkingTreeChanges.Files("c2", emptyList()) }
        robot.interact { window.refreshButton.fire() }
        eventually { assertEquals("No differences.", window.placeholder.text) }
        workingTree = { GitWorkingTreeChanges.Missing }
        robot.interact { window.refresh() }
        eventually { assertEquals("feature no longer exists.", window.placeholder.text) }
        workingTree = { null }
        robot.interact { window.refresh() }
        eventually { assertEquals("The differences could not be read.", window.placeholder.text) }

        // HEAD has a window of its own; another folder closes them all.
        selectBranches(robot, "HEAD")
        robot.interact { pane.menu.workingTreeItem.fire() }
        val head = windowOf("HEAD")
        robot.interact { assertEquals("HEAD ↔ Working Tree", head.stage.title) }
        robot.interact { view.open(null) }
        robot.interact {
            assertFalse(window.stage.isShowing)
            assertFalse(head.stage.isShowing)
            assertTrue(view.diffs.workingTreeWindows.isEmpty())
        }
    }

    @Test
    fun `Compare with Local on one commit, its diffs go to the window's callback`(robot: FxRobot) {
        val shown = CopyOnWriteArrayList<WorkingTreeFileDiff>()
        var showable = true
        robot.interact { view.onShowWorkingTreeDiff = { diff -> shown.add(diff).let { showable } } }
        openAndExpand(robot)
        robot.interact {
            view.table.selectionModel.selectIndices(0, 1)
            assertTrue(view.table.compareWithLocalItem.isDisable, "two commits")
            view.table.selectionModel.clearAndSelect(0)
            assertFalse(view.table.compareWithLocalItem.isDisable)
        }
        val commit =
            view.table.items[0]
                .commit.hash
        robot.interact { view.table.compareWithLocalItem.fire() }
        val window = windowOf(commit)
        eventually { assertEquals("4 files differ", window.header.text) }
        robot.interact { assertEquals("$commit ↔ Working Tree", window.stage.title) }
        // Show History is the log's (ADR 0046): not in the window's menu.
        robot.interact {
            selectFile(window, "new.md")
            window.tree.contextMenu.onShowing
                .handle(null)
            assertFalse(window.tree.showHistoryItem.isVisible)
        }

        robot.interact {
            selectFile(window, "new.md")
            press(window, KeyCode.ENTER)
        }
        eventually { assertEquals(1, shown.size) }
        assertEquals(
            WorkingTreeFileDiff(
                repo,
                "docs/new.md",
                "docs/old.md",
                GitChange.RENAMED,
                "commit-of-$commit",
                commit,
                "old docs/new.md\n",
                "new docs/new.md\n",
                listOf(GitHunk(0..0, 0..0)),
            ),
            shown.single(),
        )
        assertEquals(listOf("commit-of-$commit" to "docs/new.md"), diffLoadsWithWorkingTree)

        diffWithWorkingTree = { GitFileDiff.Binary }
        robot.interact {
            selectFile(window, "One.kt")
            press(window, KeyCode.ENTER)
        }
        eventually { assertEquals("One.kt is a binary file.", window.status.text) }
        robot.interact { selectFile(window, "Two.kt") }
        robot.interact { assertEquals("", window.status.text, "cleared by the next selection") }

        diffWithWorkingTree = { file -> GitFileDiff.Text("same\n", "same\n", emptyList()) }
        robot.interact { press(window, KeyCode.ENTER) }
        eventually { assertEquals(2, shown.size) }
        robot.interact {
            selectFile(window, "new.md")
            press(window, KeyCode.ENTER)
        }
        eventually { assertEquals("new.md has no content changes.", window.status.text) }

        showable = false
        diffWithWorkingTree = { GitFileDiff.Text("a\n", "b\n", listOf(GitHunk(0..0, 0..0))) }
        robot.interact {
            selectFile(window, "One.kt")
            press(window, KeyCode.ENTER)
        }
        eventually { assertEquals("One.kt cannot be shown as text.", window.status.text) }

        robot.interact { view.close() }
        robot.interact { assertFalse(window.stage.isShowing) }
    }
}
