package dev.sluide.gitlog

import dev.sluide.git.GitChange
import dev.sluide.git.GitChangedFile
import dev.sluide.git.GitCommitChanges
import dev.sluide.git.GitFileDiff
import dev.sluide.git.GitPendingMerge
import dev.sluide.git.GitResult
import dev.sluide.gitlog.area.AreaTab
import dev.sluide.gitlog.commit.CheckState
import dev.sluide.gitlog.commit.CommitEntryNode
import dev.sluide.gitlog.commit.CommitGroupNode
import dev.sluide.gitlog.commit.CommitNode
import javafx.application.Platform
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.Label
import javafx.scene.control.TreeCell
import javafx.scene.control.TreeItem
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.HBox
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * UI tests of the Commit tab of the git log area (ADR 0048): the tab, the tree of changes with its checks, the message
 * and Amend, committing and the diffs against `HEAD`. Needs a display, see the `uiTest` Gradle task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class GitLogCommitUiTest : GitLogWritesUiFixture() {
    @Start
    fun start(stage: Stage) = startView(stage)

    private val tracked =
        listOf(
            GitChangedFile(GitChange.MODIFIED, "src/main/App.kt"),
            GitChangedFile(GitChange.ADDED, "src/main/Util.kt"),
            GitChangedFile(GitChange.MODIFIED, "README.md"),
            GitChangedFile(GitChange.RENAMED, "docs/new.md", "docs/old.md"),
        )

    private fun changes(
        files: List<GitChangedFile> = tracked,
        untracked: List<String> = listOf("notes.txt", "tmp/scratch.txt"),
        headPushed: Boolean = false,
    ) = GitCommitChanges(files, untracked, null, emptyList(), headPushed)

    /** The tree as lines: indented by depth, `[x]`, `[-]` or `[ ]`, the name (a group's with its count). */
    private val initialTree =
        listOf(
            "[x] Changes 4",
            "  [x] docs",
            "    [x] new.md",
            "  [x] src/main",
            "    [x] App.kt",
            "    [x] Util.kt",
            "  [x] README.md",
            "[ ] Unversioned Files 2",
            "  [ ] tmp",
            "    [ ] scratch.txt",
            "  [ ] notes.txt",
        )

    private fun commitView() = checkNotNull(view.tabs.commitView)

    /** Opens the repository and the Commit tab, and waits for its tree. */
    private fun showCommit(robot: FxRobot) {
        commitChanges = changes()
        robot.interact {
            view.open(repo)
            view.showCommit()
        }
        eventually { assertEquals(initialTree, treeLines()) }
    }

    private fun treeLines(): List<String> {
        fun lines(
            item: TreeItem<CommitNode>,
            depth: Int,
        ): List<String> =
            item.children.flatMap { child ->
                val node = child.value
                val mark =
                    when (commitView().checkState(node)) {
                        CheckState.ALL -> "[x]"
                        CheckState.SOME -> "[-]"
                        CheckState.NONE -> "[ ]"
                    }
                val line = "  ".repeat(depth) + "$mark ${nameOf(node)}"
                listOf(line) + if (child.isExpanded) lines(child, depth + 1) else emptyList()
            }
        return WaitForAsyncUtils.asyncFx<List<String>> { lines(commitView().tree.root, 0) }.get()
    }

    private fun nameOf(node: CommitNode) =
        when (node) {
            is CommitGroupNode -> "${node.group.title} ${node.paths.size}"
            is CommitEntryNode -> node.node.name
        }

    private fun item(name: String): TreeItem<CommitNode> {
        fun find(item: TreeItem<CommitNode>): TreeItem<CommitNode>? =
            if (item.value?.let(::nameOf)?.substringBefore(" ") == name.substringBefore(" ")) {
                item
            } else {
                item.children.firstNotNullOfOrNull(::find)
            }
        return checkNotNull(find(commitView().tree.root)) { name }
    }

    /** Clicks the checkbox of the node [name] in its cell. */
    private fun clickCheck(
        robot: FxRobot,
        name: String,
    ) {
        robot.interact {
            val target = item(name)
            val cell =
                robot
                    .lookup(".git-commit-tree .tree-cell")
                    .queryAllAs(TreeCell::class.java)
                    .first { it.treeItem === target && !it.isEmpty }
            ((cell.graphic as HBox).children.first() as CheckBox).fire()
        }
        WaitForAsyncUtils.waitForFxEvents()
    }

    private fun select(
        robot: FxRobot,
        vararg names: String,
    ) {
        robot.interact {
            val tree = commitView().tree
            tree.selectionModel.clearSelection()
            names.forEach { tree.selectionModel.select(item(it)) }
        }
        WaitForAsyncUtils.waitForFxEvents()
    }

    /** Presses [code] at [target], as typed: with its own key handling and the filters above it. */
    private fun press(
        target: Node,
        code: KeyCode,
        shortcut: Boolean = false,
    ) {
        val mac = System.getProperty("os.name").startsWith("Mac")
        Platform.runLater {
            // The shortcut is Cmd on macOS, else Ctrl.
            val event = KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, shortcut && !mac, false, shortcut && mac)
            Event.fireEvent(target, event)
        }
        WaitForAsyncUtils.waitForFxEvents()
    }

    private fun headerTabs(): List<String> =
        WaitForAsyncUtils
            .asyncFx<List<String>> {
                view.tabs.titles.children.map { node ->
                    when (node) {
                        is AreaTab -> if (node.selected) "[${node.label.text}]" else node.label.text
                        else -> (node as Label).text
                    }
                }
            }.get()

    private fun commitDisabled() = WaitForAsyncUtils.asyncFx<Boolean> { commitView().commitButton.isDisabled }.get()

    private fun typeMessage(
        robot: FxRobot,
        text: String,
    ) {
        robot.interact { commitView().messageArea.text = text }
    }

    @Test
    fun `showCommit opens the tab after the Log, before the histories, expands the area and focuses the message`(
        robot: FxRobot,
    ) {
        robot.interact { view.showCommit() }
        assertNull(view.tabs.commitView, "no repository")

        robot.interact {
            view.open(repo)
            view.showHistory(repo.resolve("src/main/App.kt"))
            view.expanded.set(false)
        }
        assertEquals(listOf("Log", "[History: App.kt]"), headerTabs())
        commitChanges = changes()
        robot.interact { view.showCommit() }

        assertEquals(listOf("Log", "[Commit]", "History: App.kt"), headerTabs())
        assertTrue(view.expanded.get())
        assertSame<Node?>(commitView(), view.center)
        assertSame<Node?>(commitView().messageArea, view.scene.focusOwner)
        eventually { assertEquals(initialTree, treeLines()) }
        assertEquals(listOf("sluide-git-commit"), commitReads.distinct())
        assertTrue(robot.lookup(".git-change-unversioned").queryAllAs(Label::class.java).any { it.text == "notes.txt" })
        assertEquals("4 of 6 files selected", commitView().summary.text)

        // The Log and back: the same tab, the message focused again.
        robot.interact { view.tabs.logTab.onSelect() }
        assertEquals(listOf("[Log]", "Commit", "History: App.kt"), headerTabs())
        robot.interact { view.showCommit() }
        assertEquals(listOf("Log", "[Commit]", "History: App.kt"), headerTabs())
        assertSame<Node?>(commitView().messageArea, view.scene.focusOwner)

        // Closing a history right of it selects the Commit tab.
        robot.interact {
            view.tabs.historyTabs
                .single()
                .onSelect()
        }
        robot.interact {
            checkNotNull(
                view.tabs.historyTabs
                    .single()
                    .closeButton,
            ).fire()
        }
        assertEquals(listOf("Log", "[Commit]"), headerTabs())
    }

    @Test
    fun `closing and reopening keeps the draft, another folder discards it`(robot: FxRobot) {
        showCommit(robot)
        typeMessage(robot, "Draft")
        clickCheck(robot, "README.md")
        val draft = commitView()

        robot.interact { checkNotNull(view.tabs.commitAreaTab.closeButton).fire() }
        assertEquals(listOf("Git Log"), headerTabs())
        assertFalse(view.tabs.commitShown)
        assertSame<Node?>(view.body, view.center, "the log is shown")

        robot.interact { view.showCommit() }
        assertSame(draft, view.tabs.commitView)
        assertEquals("Draft", draft.messageArea.text)
        assertEquals("  [ ] README.md", treeLines()[6])

        robot.interact { view.open(Path.of("/other")) }
        assertNull(view.tabs.commitView)
        assertEquals(listOf("Git Log"), headerTabs())
        robot.interact { view.showCommit() }
        eventually { assertEquals(initialTree, treeLines()) }
        assertEquals("", commitView().messageArea.text)
    }

    @Test
    fun `folders are tri-state, a group or folder checks all below it, Space toggles the selected nodes`(
        robot: FxRobot,
    ) {
        showCommit(robot)

        clickCheck(robot, "App.kt")
        assertEquals(
            listOf("[-] Changes 4", "  [x] docs", "    [x] new.md", "  [-] src/main", "    [ ] App.kt"),
            treeLines().take(5),
        )
        assertEquals("3 of 6 files selected", commitView().summary.text)

        // An indeterminate folder checks all.
        clickCheck(robot, "src/main")
        assertEquals(initialTree, treeLines())
        clickCheck(robot, "Unversioned")
        assertEquals("[x] Unversioned Files 2", treeLines()[7])
        assertEquals("    [x] scratch.txt", treeLines()[9])

        // Space: unchecks the selected nodes if all are checked, else checks them all.
        select(robot, "README.md", "notes.txt")
        press(commitView().tree, KeyCode.SPACE)
        assertEquals("  [ ] README.md", treeLines()[6])
        assertEquals("  [ ] notes.txt", treeLines()[10])
        select(robot, "README.md", "scratch.txt")
        press(commitView().tree, KeyCode.SPACE)
        assertEquals("  [x] README.md", treeLines()[6])
        assertEquals("    [x] scratch.txt", treeLines()[9])
    }

    @Test
    fun `checks are kept across reloads, new tracked files arrive checked and new untracked ones not`(robot: FxRobot) {
        showCommit(robot)
        clickCheck(robot, "App.kt")
        clickCheck(robot, "notes.txt")

        commitChanges =
            changes(
                files = tracked + GitChangedFile(GitChange.ADDED, "Build.kt"),
                untracked = listOf("notes.txt", "tmp/scratch.txt", "other.txt"),
            )
        robot.interact { view.workingTreeChanged() }

        eventually {
            assertEquals(
                listOf(
                    "[-] Changes 5",
                    "  [x] docs",
                    "    [x] new.md",
                    "  [-] src/main",
                    "    [ ] App.kt",
                    "    [x] Util.kt",
                    "  [x] Build.kt",
                    "  [x] README.md",
                    "[-] Unversioned Files 3",
                    "  [ ] tmp",
                    "    [ ] scratch.txt",
                    "  [x] notes.txt",
                    "  [ ] other.txt",
                ),
                treeLines(),
            )
        }
        assertEquals("5 of 8 files selected", commitView().summary.text)

        // Nothing to commit.
        commitChanges = changes(files = emptyList(), untracked = emptyList())
        robot.interact { view.refresh() }
        eventually { assertEquals(emptyList(), treeLines()) }
        assertEquals("Nothing to commit.", commitView().summary.text)
        assertEquals("No changes.", commitView().placeholder.text)
        assertTrue(commitView().placeholder.isVisible)
    }

    @Test
    fun `Commit needs a message, a checked file or Amend, nothing in progress and no running write`(robot: FxRobot) {
        showCommit(robot)
        assertTrue(commitDisabled(), "no message")
        typeMessage(robot, "  \n ")
        assertTrue(commitDisabled(), "a blank message")
        typeMessage(robot, "Fix")
        assertFalse(commitDisabled())

        clickCheck(robot, "Changes")
        assertEquals("[ ] Changes 4", treeLines().first())
        assertTrue(commitDisabled(), "nothing checked")
        robot.interact { commitView().amendBox.isSelected = true }
        assertFalse(commitDisabled(), "Amend rewords")
        robot.interact { commitView().amendBox.isSelected = false }
        clickCheck(robot, "README.md")
        assertFalse(commitDisabled())

        commitChanges = changes().copy(inProgress = GitPendingMerge(listOf("h1"), null, emptyList()))
        robot.interact { view.workingTreeChanged() }
        eventually { assertTrue(commitView().blockedNote.isVisible) }
        assertEquals("A merge is in progress; continue or abort it in the log first.", commitView().blockedNote.text)
        assertTrue(commitDisabled(), "a merge in progress")
        commitChanges = changes().copy(conflicts = listOf("README.md"))
        robot.interact { view.workingTreeChanged() }
        eventually { assertEquals("1 file has conflicts; resolve it first.", commitView().blockedNote.text) }
        assertTrue(commitDisabled(), "conflicts")
        commitChanges = changes()
        robot.interact { view.workingTreeChanged() }
        eventually { assertFalse(commitView().blockedNote.isVisible) }
        assertFalse(commitDisabled())

        writes.gate = CountDownLatch(1)
        robot.interact { view.fetchButton.fire() }
        assertTrue(commitDisabled(), "a fetch is running")
        writes.gate?.countDown()
        eventually { assertFalse(commitView().commitButton.isDisabled) }
    }

    @Test
    fun `Shortcut+Enter commits the checked files with the message, Enter is a new line`(robot: FxRobot) {
        showCommit(robot)
        clickCheck(robot, "Util.kt")
        clickCheck(robot, "notes.txt")
        typeMessage(robot, "Fix")
        val area = commitView().messageArea
        robot.interact {
            area.requestFocus()
            area.positionCaret(area.length)
        }

        press(area, KeyCode.ENTER)
        assertEquals("Fix\n", area.text)
        assertTrue(writes.calls.isEmpty())
        robot.interact { area.appendText("Body") }
        press(area, KeyCode.ENTER, shortcut = true)

        eventually { assertEquals("Committed 5e6f7a8b: Fix.", status(robot)) }
        assertEquals(listOf("commit src/main/App.kt,README.md,docs/new.md|notes.txt: Fix\\nBody"), writes.calls)
        assertEquals(listOf("sluide-git-write"), writes.threads)
        assertEquals(listOf("Commit"), fileChanges, "unsaved editors asked about")
        // The draft is done: the message and the committed files' checks go, the tab and the log read again.
        assertEquals("", area.text)
        assertFalse(commitView().amendBox.isSelected)
        eventually { assertTrue(commitReads.size >= 2) }
    }

    @Test
    fun `a failed commit shows the Commit Failed dialog and keeps the draft, a cancelled one only says so`(
        robot: FxRobot,
    ) {
        showCommit(robot)
        clickCheck(robot, "README.md")
        typeMessage(robot, "Fix")
        writes.result = { GitResult(false, "pre-commit: tests fail") }

        Platform.runLater { commitView().commitButton.fire() }
        val pane = awaitDialog(robot)
        assertEquals("Commit Failed", (pane.scene.window as Stage).title)
        assertEquals("The commit could not be made.", pane.headerText)
        assertTrue(pane.lookupAll(".git-message").isNotEmpty())
        press(robot, pane, ButtonType.OK)

        assertEquals("Fix", commitView().messageArea.text)
        assertEquals("  [ ] README.md", treeLines()[6])

        // Cancelled from the header while it runs: no dialog, the draft stays.
        writes.result = { GitResult(true, "") }
        writes.gate = CountDownLatch(1)
        robot.interact { commitView().commitButton.fire() }
        assertEquals("Committing…", status(robot))
        assertTrue(cancelShown())
        writes.result = { GitResult(false, "Cancelled.") }
        robot.interact { view.cancelButton.fire() }
        writes.gate?.countDown()
        eventually { assertEquals("Commit cancelled.", status(robot)) }
        assertNoDialog()
        assertEquals("Fix", commitView().messageArea.text)
    }

    @Test
    fun `Amend fills an empty message with HEAD's, unticking takes it out, and tells when HEAD is pushed`(
        robot: FxRobot,
    ) {
        headMessage = "Earlier\n\nBody"
        showCommit(robot)
        val draft = commitView()
        assertFalse(draft.amendBox.isDisabled)
        assertFalse(draft.pushedNote.isVisible)

        robot.interact { draft.amendBox.isSelected = true }
        eventually { assertEquals("Earlier\n\nBody", draft.messageArea.text) }
        assertEquals(listOf("sluide-git-commit"), headMessageReads)
        assertFalse(draft.pushedNote.isVisible, "not pushed")
        robot.interact { draft.amendBox.isSelected = false }
        assertEquals("", draft.messageArea.text)

        // A message of the user's stays.
        robot.interact { draft.amendBox.isSelected = true }
        eventually { assertEquals("Earlier\n\nBody", draft.messageArea.text) }
        robot.interact { draft.messageArea.appendText(" changed") }
        robot.interact { draft.amendBox.isSelected = false }
        assertEquals("Earlier\n\nBody changed", draft.messageArea.text)
        typeMessage(robot, "Mine")
        robot.interact { draft.amendBox.isSelected = true }
        assertEquals("Mine", draft.messageArea.text)
        assertEquals(2, headMessageReads.size, "not read for a message")

        commitChanges = changes(headPushed = true)
        robot.interact { view.workingTreeChanged() }
        eventually { assertTrue(draft.pushedNote.isVisible) }
        assertEquals("This commit is already pushed: amending it needs a force push.", draft.pushedNote.text)

        // The commit amends.
        Platform.runLater { draft.commitButton.fire() }
        eventually { assertEquals("Amended 5e6f7a8b: Mine.", status(robot)) }
        assertTrue(writes.calls.single().startsWith("commit --amend "))
        assertFalse(draft.amendBox.isSelected)

        // Without a commit there is nothing to amend.
        headCommit = null
        robot.interact { view.workingTreeChanged() }
        eventually { assertTrue(draft.amendBox.isDisabled) }
    }

    @Test
    fun `Show Diff reads the file against HEAD on the tab's thread, an untracked one as added`(robot: FxRobot) {
        val diffs = CopyOnWriteArrayList<WorkingTreeFileDiff>()
        val opened = CopyOnWriteArrayList<Path>()
        robot.interact {
            view.onShowWorkingTreeDiff = { diff ->
                diffs += diff
                true
            }
            view.onOpenFile = { opened.add(it) }
            view.fileExists = { true }
        }
        showCommit(robot)

        select(robot, "App.kt")
        press(commitView().tree, KeyCode.D, shortcut = true)
        eventually { assertEquals(1, diffs.size) }
        val diff = diffs.single()
        assertEquals("src/main/App.kt", diff.path)
        assertEquals("h1", diff.commit)
        assertEquals("HEAD", diff.label)
        assertEquals("old\n", diff.original)
        assertEquals(listOf("h1 MODIFIED src/main/App.kt"), workingTreeDiffLoads)
        assertEquals(listOf("sluide-git-commit"), workingTreeDiffThreads.toList())

        select(robot, "notes.txt")
        robot.interact { commitView().showDiffItem.fire() }
        eventually { assertEquals(2, diffs.size) }
        assertEquals("h1 ADDED notes.txt", workingTreeDiffLoads.last())

        select(robot, "new.md")
        press(commitView().tree, KeyCode.ENTER)
        eventually { assertEquals(3, diffs.size) }
        assertEquals("h1 RENAMED docs/new.md", workingTreeDiffLoads.last())
        assertEquals("docs/old.md", diffs.last().oldPath)

        // A binary file is told in the header.
        workingTreeDiff = GitFileDiff.Binary
        select(robot, "README.md")
        press(commitView().tree, KeyCode.D, shortcut = true)
        eventually { assertEquals("README.md is a binary file.", status(robot)) }

        // F4 opens the file.
        press(commitView().tree, KeyCode.F4)
        assertEquals(listOf(repo.resolve("README.md")), opened)

        // Without a commit there is nothing to compare with: the file opens.
        headCommit = null
        robot.interact { view.workingTreeChanged() }
        eventually { assertTrue(commitView().amendBox.isDisabled) }
        select(robot, "Util.kt")
        press(commitView().tree, KeyCode.D, shortcut = true)
        assertEquals(repo.resolve("src/main/Util.kt"), opened.last())
        assertEquals(3, diffs.size)
    }

    @Test
    fun `an untracked nested repository has no diff and does not open`(robot: FxRobot) {
        val opened = CopyOnWriteArrayList<Path>()
        robot.interact {
            view.onOpenFile = { opened.add(it) }
            view.fileExists = { true }
        }
        commitChanges = changes(untracked = listOf("notes.txt", "sub/"))
        robot.interact {
            view.open(repo)
            view.showCommit()
        }
        eventually {
            assertEquals(
                listOf("[ ] Unversioned Files 2", "  [ ] notes.txt", "  [ ] sub"),
                treeLines().drop(7),
            )
        }

        select(robot, "sub")
        assertTrue(commitView().openFileItem.isDisable)
        assertFalse(commitView().showDiffItem.isDisable)
        press(commitView().tree, KeyCode.D, shortcut = true)
        assertEquals("sub is a repository.", status(robot))
        press(commitView().tree, KeyCode.F4)
        assertTrue(opened.isEmpty())
        assertTrue(workingTreeDiffLoads.isEmpty())

        // Committed by its path as git listed it.
        clickCheck(robot, "sub")
        typeMessage(robot, "Add sub")
        robot.interact { commitView().commitButton.fire() }
        eventually {
            assertEquals(
                listOf("commit src/main/App.kt,src/main/Util.kt,README.md,docs/new.md|sub/: Add sub"),
                writes.calls,
            )
        }
    }

    @Test
    fun `text typed while HEAD's message is read or while the commit runs is kept`(robot: FxRobot) {
        showCommit(robot)
        val draft = commitView()
        headMessageGate = CountDownLatch(1)
        robot.interact { draft.amendBox.isSelected = true }
        eventually { assertEquals(1, headMessageReads.size) }
        typeMessage(robot, "Typed")
        val reads = commitReads.size
        headMessageGate?.countDown()
        // A read after it on the tab's thread: HEAD's message has been handed over by then.
        robot.interact { view.workingTreeChanged() }
        eventually { assertEquals(reads + 1, commitReads.size) }
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals("Typed", draft.messageArea.text)
        robot.interact { draft.amendBox.isSelected = false }
        assertEquals("Typed", draft.messageArea.text)

        writes.gate = CountDownLatch(1)
        robot.interact { draft.commitButton.fire() }
        assertEquals("Committing…", status(robot))
        typeMessage(robot, "Next one")
        writes.gate?.countDown()
        eventually { assertEquals("Committed 5e6f7a8b: Typed.", status(robot)) }
        assertEquals("Next one", draft.messageArea.text)
    }

    @Test
    fun `Amend is unticked when HEAD has no commit anymore, so nothing checked keeps Commit disabled`(robot: FxRobot) {
        showCommit(robot)
        val draft = commitView()
        clickCheck(robot, "Changes")
        typeMessage(robot, "Reword")
        robot.interact { draft.amendBox.isSelected = true }
        assertFalse(commitDisabled())

        // E.g. git checkout --orphan.
        headCommit = null
        robot.interact { view.workingTreeChanged() }
        eventually { assertTrue(draft.amendBox.isDisabled) }
        assertFalse(draft.amendBox.isSelected)
        assertTrue(commitDisabled())
        assertEquals("Reword", draft.messageArea.text)
    }

    @Test
    fun `another folder stops the tab's thread`(robot: FxRobot) {
        fun threadAlive() = Thread.getAllStackTraces().keys.any { it.name == "sluide-git-commit" && it.isAlive }
        showCommit(robot)
        assertTrue(threadAlive())

        robot.interact { view.open(Path.of("/other")) }
        eventually { assertFalse(threadAlive()) }
    }

    @Test
    fun `workingTreeChanged reads the changes again only while the tab is shown`(robot: FxRobot) {
        robot.interact {
            view.open(repo)
            view.workingTreeChanged()
        }
        WaitForAsyncUtils.waitForFxEvents()
        assertTrue(commitReads.isEmpty(), "no tab")

        // A failed first read says so in the tree.
        commitChanges = null
        robot.interact { view.showCommit() }
        eventually { assertEquals("The changes could not be read.", commitView().placeholder.text) }
        commitChanges = changes()
        robot.interact { view.workingTreeChanged() }
        eventually { assertEquals(initialTree, treeLines()) }
        val reads = commitReads.size

        robot.interact { view.expanded.set(false) }
        robot.interact { view.workingTreeChanged() }
        robot.interact { view.workingTreeChanged() }
        view.tabs.commitReaderIdle().get(10, TimeUnit.SECONDS)
        assertEquals(reads, commitReads.size, "collapsed")
        robot.interact { view.expanded.set(true) }
        eventually { assertEquals(reads + 1, commitReads.size) }

        robot.interact { view.tabs.logTab.onSelect() }
        robot.interact { view.workingTreeChanged() }
        view.tabs.commitReaderIdle().get(10, TimeUnit.SECONDS)
        assertEquals(reads + 1, commitReads.size, "the Log is shown")
        robot.interact { view.tabs.commitAreaTab.onSelect() }
        eventually { assertEquals(reads + 2, commitReads.size) }

        // A failed read with the tree kept says so in the header, also when the tab is selected again.
        commitChanges = null
        robot.interact { view.workingTreeChanged() }
        eventually { assertEquals("The changes could not be read.", status(robot)) }
        assertEquals(initialTree, treeLines())
        robot.interact { view.tabs.logTab.onSelect() }
        assertEquals("", status(robot))
        robot.interact { view.tabs.commitAreaTab.onSelect() }
        eventually { assertEquals("The changes could not be read.", status(robot)) }
    }
}
