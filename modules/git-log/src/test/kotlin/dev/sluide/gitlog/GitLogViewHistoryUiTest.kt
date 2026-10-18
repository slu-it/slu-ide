package dev.sluide.gitlog

import dev.sluide.git.GitChange
import dev.sluide.git.GitChangedFile
import dev.sluide.git.GitCommit
import dev.sluide.git.GitFileDiff
import dev.sluide.git.GitFileHistoryEntry
import javafx.event.Event
import javafx.scene.control.Label
import javafx.scene.control.TableRow
import javafx.scene.input.KeyCode
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * UI tests of the git log view: file histories in tabs of the area (ADR 0046). Needs a display, see the `uiTest` Gradle
 * task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class GitLogViewHistoryUiTest : GitLogViewUiFixture() {
    @Start
    fun start(stage: Stage) = startView(stage)

    /** The commit of [history] with the hash `h<number>`. */
    private fun commitOf(number: Int): GitCommit = history.orEmpty()[number - 1]

    /** An entry of a file's history: the commit `h<number>` and the file's change there. */
    private fun entry(
        number: Int,
        change: GitChange,
        path: String,
        oldPath: String? = null,
    ) = GitFileHistoryEntry(commitOf(number), GitChangedFile(change, path, oldPath))

    /** A history of `src/App.kt`, renamed from `src/Old.kt` in h4. */
    private val renamed =
        listOf(
            entry(2, GitChange.MODIFIED, "src/App.kt"),
            entry(4, GitChange.RENAMED, "src/App.kt", "src/Old.kt"),
            entry(6, GitChange.MODIFIED, "src/Old.kt"),
            entry(9, GitChange.ADDED, "src/Old.kt"),
        )

    private val app = Path.of("/repo/src/App.kt")

    /** The texts of the header: the title, or the tabs' titles with the selected one in brackets. */
    private fun headerTabs(): List<String> =
        WaitForAsyncUtils
            .asyncFx<List<String>> {
                view.tabs.titles.children.map { node ->
                    when (node) {
                        is AreaTab -> if (node.selected) "[${node.label.text}]" else node.label.text
                        is Label -> node.text
                        else -> "?"
                    }
                }
            }.get()

    private val historyView get() = checkNotNull(view.tabs.selectedHistoryView)

    /** The subjects of the history's rows. */
    private fun historySubjects(): List<String> =
        WaitForAsyncUtils.asyncFx<List<String>> { historyView.table.items.map { it.commit.subject } }.get()

    private fun selectedSubject(): String? =
        WaitForAsyncUtils
            .asyncFx<String?> {
                historyView.table.selectionModel.selectedItem
                    ?.commit
                    ?.subject
            }.get()

    private fun historyPlaceholder(): String =
        WaitForAsyncUtils.asyncFx<String> { (historyView.table.placeholder as Label).text }.get()

    private fun openHistory(
        robot: FxRobot,
        file: Path = app,
    ) {
        robot.interact { view.showHistory(file) }
        WaitForAsyncUtils.waitForFxEvents()
    }

    private fun selectHistoryRow(
        robot: FxRobot,
        index: Int,
    ) {
        robot.interact { historyView.table.selectionModel.clearAndSelect(index) }
        WaitForAsyncUtils.waitForFxEvents()
    }

    private fun middleClick(
        robot: FxRobot,
        tab: AreaTab,
    ) {
        robot.interact {
            Event.fireEvent(
                tab,
                MouseEvent(
                    MouseEvent.MOUSE_CLICKED,
                    1.0,
                    1.0,
                    1.0,
                    1.0,
                    MouseButton.MIDDLE,
                    1,
                    false,
                    false,
                    false,
                    false,
                    false,
                    true,
                    false,
                    true,
                    false,
                    false,
                    null,
                ),
            )
        }
        WaitForAsyncUtils.waitForFxEvents()
    }

    @Test
    fun `the header shows tabs while a history is open, and the plain title again once the last closes`(
        robot: FxRobot,
    ) {
        fileHistoryOf = { _, _ -> renamed }
        robot.interact { view.open(repo) }
        assertEquals(listOf("Git Log"), headerTabs())
        assertFalse(view.expanded.get())

        openHistory(robot)

        assertTrue(view.expanded.get(), "opening a history expands the area")
        assertEquals(listOf("Log", "[History: App.kt]"), headerTabs())
        assertSame(historyView, view.center)
        assertTrue(historyLoads.isNotEmpty())
        assertTrue(loads.isEmpty(), "the log is not read while a history is shown")
        assertSame(historyView.table, view.scene.focusOwner)
        assertEquals(
            "src/App.kt",
            view.tabs.historyTabs
                .single()
                .tooltip
                ?.text,
        )

        // A click on the Log tab selects it, without collapsing the area.
        click(robot, view.tabs.logTab.label)
        assertEquals(listOf("[Log]", "History: App.kt"), headerTabs())
        assertSame(view.body, view.center)
        assertTrue(view.expanded.get())
        eventually { assertTrue(view.table.items.isNotEmpty()) }

        // Elsewhere on the header still collapses; a tab expands again.
        click(robot, view.header)
        assertFalse(view.expanded.get())
        click(
            robot,
            view.tabs.historyTabs
                .single()
                .label,
        )
        assertTrue(view.expanded.get())
        assertEquals(listOf("Log", "[History: App.kt]"), headerTabs())

        robot.interact {
            checkNotNull(
                view.tabs.historyTabs
                    .single()
                    .closeButton,
            ).fire()
        }
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(listOf("Git Log"), headerTabs())
        assertSame(view.body, view.center)
        assertTrue(view.expanded.get())
    }

    @Test
    fun `the same file selects its tab, up to here opens another, a middle click closes a tab`(robot: FxRobot) {
        fileHistoryOf = { _, _ -> renamed }
        robot.interact { view.open(repo) }
        openHistory(robot)
        click(robot, view.tabs.logTab.label)

        openHistory(robot)
        assertEquals(listOf("Log", "[History: App.kt]"), headerTabs())

        // Up to here: the commit's path, from the commit on; a deleted file's from its first parent.
        robot.interact { view.tabs.showHistoryUpTo(commitOf(4), GitChangedFile(GitChange.MODIFIED, "src/App.kt")) }
        robot.interact { view.tabs.showHistoryUpTo(commitOf(7), GitChangedFile(GitChange.DELETED, "gone.txt")) }
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(listOf("Log", "History: App.kt", "History: App.kt", "[History: gone.txt]"), headerTabs())
        eventually {
            assertTrue(listOf("src/App.kt", "h4", 0, 500) in historyLoads)
            assertTrue(listOf("gone.txt", "h8", 0, 500) in historyLoads)
        }
        val tips = view.tabs.historyTabs.map { it.tooltip?.text }
        assertEquals(listOf("src/App.kt", "src/App.kt up to h4", "gone.txt up to h8"), tips)

        // Closing the selected tab selects the one left of it.
        middleClick(robot, view.tabs.historyTabs.last())
        assertEquals(listOf("Log", "History: App.kt", "[History: App.kt]"), headerTabs())
        middleClick(robot, view.tabs.historyTabs.first())
        assertEquals(listOf("Log", "[History: App.kt]"), headerTabs())

        // Files outside the repository have none.
        openHistory(robot, Path.of("/elsewhere/App.kt"))
        assertEquals(1, view.tabs.historyTabs.size)
    }

    @Test
    fun `lists the commits of the file with a Path column while it had several paths`(robot: FxRobot) {
        fileHistoryOf = { _, _ -> renamed }
        robot.interact { view.open(repo) }
        openHistory(robot)

        eventually { assertEquals(listOf("Subject 2", "Subject 4", "Subject 6", "Subject 9"), historySubjects()) }
        assertEquals("Subject 2", selectedSubject(), "the first row at first")
        robot.interact {
            val table = historyView.table
            assertEquals(listOf("Subject", "Author", "Date", "Path"), table.visibleLeafColumns.map { it.text })
            assertEquals(
                listOf("src/App.kt", "src/App.kt", "src/Old.kt", "src/Old.kt"),
                table.items.map { historyView.pathColumn.getCellData(it) },
            )
        }
        eventually { assertTrue(historyView.detailsPane.subject.text == "Subject 2") }

        // Up to here: the start commit's row is selected at first. Git lists it first; the fake lists newer ones too, to
        // show that the row is found by its hash.
        robot.interact { view.tabs.showHistoryUpTo(commitOf(6), GitChangedFile(GitChange.MODIFIED, "src/Old.kt")) }
        eventually { assertEquals(listOf("Subject 2", "Subject 4", "Subject 6", "Subject 9"), historySubjects()) }
        assertEquals("Subject 6", selectedSubject())

        // One path only: no Path column.
        fileHistoryOf = { _, _ -> renamed.take(2).map { it.copy(file = GitChangedFile(GitChange.MODIFIED, "a")) } }
        openHistory(robot, Path.of("/repo/a"))
        eventually { assertEquals(listOf("Subject 2", "Subject 4"), historySubjects()) }
        robot.interact {
            assertEquals(listOf("Subject", "Author", "Date"), historyView.table.visibleLeafColumns.map { it.text })
        }
    }

    @Test
    fun `the table says when the history is loading, empty or unreadable`(robot: FxRobot) {
        historyGate = CountDownLatch(1)
        fileHistoryOf = { _, _ -> emptyList() }
        robot.interact { view.open(repo) }
        openHistory(robot)
        assertEquals("Loading…", historyPlaceholder())
        historyGate?.countDown()
        eventually { assertEquals("No history: App.kt has no commits.", historyPlaceholder()) }

        fileHistoryOf = { _, _ -> null }
        openHistory(robot, Path.of("/repo/b.txt"))
        eventually { assertEquals("The history could not be read.", historyPlaceholder()) }

        // A failed refresh keeps the rows; the header says it.
        fileHistoryOf = { _, _ -> renamed }
        openHistory(robot, Path.of("/repo/c.txt"))
        eventually { assertEquals(4, historySubjects().size) }
        fileHistoryOf = { _, _ -> null }
        robot.interact { view.refresh() }
        eventually { assertEquals("The history could not be read.", status(robot)) }
        assertEquals(4, historySubjects().size)
    }

    @Test
    fun `loads further pages when scrolled near the end`(robot: FxRobot) {
        val long = (1..700).map { entry(it, GitChange.MODIFIED, "src/App.kt") }
        fileHistoryOf = { _, _ -> long }
        robot.interact { view.open(repo) }
        openHistory(robot)
        eventually { assertEquals(500, historySubjects().size) }

        robot.interact { historyView.table.scrollTo(499) }
        eventually { assertEquals(700, historySubjects().size) }
        assertEquals(
            listOf<List<Any?>>(listOf("src/App.kt", null, 0, 500), listOf("src/App.kt", null, 500, 500)),
            historyLoads.toList(),
        )
    }

    @Test
    fun `Show Diff shows the change of the file in a commit against its first parent, with a renamed file's old path`(
        robot: FxRobot,
    ) {
        fileHistoryOf = { _, _ -> renamed }
        val shown = CopyOnWriteArrayList<CommitFileDiff>()
        view.onShowDiff = { shown += it }
        robot.interact { view.open(repo) }
        openHistory(robot)
        eventually { assertEquals(4, historySubjects().size) }

        selectHistoryRow(robot, 1)
        press(robot, historyView.table, KeyCode.ENTER)
        eventually { assertEquals(1, shown.size) }
        assertEquals(Triple("h4", "h5", "src/App.kt"), diffLoads.single())
        val diff = shown.single()
        assertEquals("src/App.kt", diff.path)
        assertEquals("src/Old.kt", diff.oldPath)
        assertEquals(GitChange.RENAMED, diff.change)
        assertEquals("h4", diff.commit)
        assertEquals("h5", diff.parent)

        // Shortcut+D, the context menu and a double-click do the same.
        selectHistoryRow(robot, 2)
        press(robot, historyView.table, KeyCode.D, shortcut = true)
        eventually { assertEquals(2, shown.size) }
        robot.interact { historyView.showDiffItem.fire() }
        eventually { assertEquals(3, shown.size) }
        assertEquals(listOf("src/Old.kt", "src/Old.kt"), shown.drop(1).map { it.path })
        selectHistoryRow(robot, 3)
        val row =
            robot
                .lookup(".git-history-table .table-row-cell")
                .queryAllAs(TableRow::class.java)
                .single { it.index == 3 }
        click(robot, row, clickCount = 2)
        eventually { assertEquals(4, shown.size) }
        assertEquals(Triple("h9", "h10", "src/Old.kt"), diffLoads.last())

        // What keeps a diff from being shown is told in the header.
        diffOf = { GitFileDiff.Binary }
        press(robot, historyView.table, KeyCode.ENTER)
        eventually { assertEquals("Old.kt is a binary file.", status(robot)) }
    }

    @Test
    fun `Compare with Local compares the file at a commit with the working tree file`(robot: FxRobot) {
        fileHistoryOf = { _, _ -> renamed + entry(1, GitChange.DELETED, "src/App.kt") }
        val shown = CopyOnWriteArrayList<WorkingTreeFileDiff>()
        view.onShowWorkingTreeDiff = { shown.add(it) }
        view.fileExists = { it == app }
        robot.interact { view.open(repo) }
        openHistory(robot)
        eventually { assertEquals(5, historySubjects().size) }

        // At the same path.
        selectHistoryRow(robot, 0)
        robot.interact { historyView.compareWithLocalItem.fire() }
        eventually { assertEquals(1, shown.size) }
        assertEquals("h2" to GitChangedFile(GitChange.MODIFIED, "src/App.kt"), workingTreeDiffLoads.last())
        assertEquals("h2", shown.last().commit)
        assertEquals("h2", shown.last().label)
        assertEquals("src/App.kt", shown.last().path)

        // At its old path: renamed since.
        selectHistoryRow(robot, 2)
        robot.interact { historyView.compareWithLocalItem.fire() }
        eventually { assertEquals(2, shown.size) }
        assertEquals("h6" to GitChangedFile(GitChange.RENAMED, "src/App.kt", "src/Old.kt"), workingTreeDiffLoads.last())
        assertEquals("src/Old.kt", shown.last().oldPath)

        // A commit that deleted it: all of the working-tree file is new.
        selectHistoryRow(robot, 4)
        robot.interact { historyView.compareWithLocalItem.fire() }
        eventually { assertEquals(3, shown.size) }
        assertEquals("h1" to GitChangedFile(GitChange.ADDED, "src/App.kt"), workingTreeDiffLoads.last())

        // Gone from the working tree.
        view.fileExists = { false }
        selectHistoryRow(robot, 0)
        robot.interact { historyView.compareWithLocalItem.fire() }
        eventually { assertEquals(4, shown.size) }
        assertEquals("h2" to GitChangedFile(GitChange.DELETED, "src/App.kt"), workingTreeDiffLoads.last())

        // The same content, or none shown as text: the header says so.
        view.fileExists = { true }
        workingTreeDiffOf = { GitFileDiff.Text("same\n", "same\n", emptyList()) }
        robot.interact { historyView.compareWithLocalItem.fire() }
        eventually { assertEquals("App.kt is the same in the working tree.", status(robot)) }
        workingTreeDiffOf = { GitFileDiff.Text("a\n", "b\n", emptyList()) }
        view.onShowWorkingTreeDiff = { false }
        robot.interact { historyView.compareWithLocalItem.fire() }
        eventually { assertEquals("App.kt cannot be shown as text.", status(robot)) }
    }

    @Test
    fun `Show in Log and a hash link in the details select the Log and go to the commit`(robot: FxRobot) {
        fileHistoryOf = { _, _ -> renamed + entry(1, GitChange.MODIFIED, "src/App.kt").copy(commit = commit("x1")) }
        robot.interact { view.open(repo) }
        openHistory(robot)
        eventually { assertEquals(5, historySubjects().size) }

        selectHistoryRow(robot, 2)
        robot.interact { historyView.showInLogItem.fire() }
        WaitForAsyncUtils.waitForFxEvents()
        assertNull(view.tabs.selectedHistoryView)
        assertEquals(listOf("[Log]", "History: App.kt"), headerTabs())
        eventually {
            assertEquals(
                listOf("Subject 6"),
                view.table.selectionModel.selectedItems
                    .map { it.commit.subject },
            )
        }
        assertSame(view.table, view.scene.focusOwner)

        // A hash link from the history's details.
        click(
            robot,
            view.tabs.historyTabs
                .single()
                .label,
        )
        robot.interact { historyView.detailsPane.openCommit("h700") }
        eventually {
            assertEquals(
                listOf("Subject 700"),
                view.table.selectionModel.selectedItems
                    .map { it.commit.subject },
            )
        }
        assertNull(view.tabs.selectedHistoryView)

        // A commit that is not in the log: the header says so.
        click(
            robot,
            view.tabs.historyTabs
                .single()
                .label,
        )
        selectHistoryRow(robot, 4)
        robot.interact { historyView.showInLogItem.fire() }
        eventually { assertEquals("x1 is not in the log.", status(robot)) }
    }

    @Test
    fun `Copy Revision Number copies the selected commit's hash`(robot: FxRobot) {
        fileHistoryOf = { _, _ -> renamed }
        robot.interact { view.open(repo) }
        openHistory(robot)
        eventually { assertEquals(4, historySubjects().size) }

        selectHistoryRow(robot, 1)
        press(robot, historyView.table, KeyCode.C, shortcut = true)
        assertEquals("h4", clipboard())
        selectHistoryRow(robot, 3)
        robot.interact { historyView.copyRevisionNumberItem.fire() }
        assertEquals("h9", clipboard())
    }

    @Test
    fun `a refresh reads the histories again and keeps their selected commits, another folder closes them`(
        robot: FxRobot,
    ) {
        fileHistoryOf = { _, _ -> renamed }
        robot.interact { view.open(repo) }
        openHistory(robot)
        eventually { assertEquals(4, historySubjects().size) }
        selectHistoryRow(robot, 2)

        fileHistoryOf = { _, _ -> listOf(entry(1, GitChange.MODIFIED, "src/App.kt")) + renamed }
        robot.interact { view.refresh() }
        eventually { assertEquals(5, historySubjects().size) }
        assertEquals("Subject 6", selectedSubject())

        // Collapsed, a refresh waits for the next expand.
        robot.interact { view.expanded.set(false) }
        val before = historyLoads.size
        robot.interact { view.refresh() }
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(before, historyLoads.size)
        robot.interact { view.expanded.set(true) }
        eventually { assertEquals(before + 1, historyLoads.size) }

        robot.interact { view.open(Path.of("/other")) }
        assertEquals(listOf("Git Log"), headerTabs())
        assertTrue(view.tabs.historyTabs.isEmpty())
        assertSame(view.body, view.center)
    }

    @Test
    fun `Show History in the changed files opens the history up to the selected commit`(robot: FxRobot) {
        fileHistoryOf = { _, _ -> renamed }
        showFilesOfFirstCommit(robot)
        selectFile(robot, "new.md")
        robot.interact {
            changes.tree.contextMenu.onShowing
                .handle(null)
        }
        assertTrue(changes.showHistoryItem.isVisible)
        assertFalse(changes.showHistoryItem.isDisable)

        robot.interact { changes.showHistoryItem.fire() }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(listOf("Log", "[History: new.md]"), headerTabs())
        eventually { assertTrue(listOf("docs/new.md", "h1", 0, 500) in historyLoads) }

        // On a folder it does nothing.
        click(robot, view.tabs.logTab.label)
        selectFile(robot, "docs")
        robot.interact {
            changes.tree.contextMenu.onShowing
                .handle(null)
        }
        assertTrue(changes.showHistoryItem.isDisable)
    }

    @Test
    fun `many tabs in a narrow area shorten and give way, the header's buttons stay whole`(robot: FxRobot) {
        fileHistoryOf = { _, _ -> renamed }
        robot.interact {
            view.scene.window.width = 420.0
            view.open(repo)
        }
        val long = "a-file-with-a-rather-long-name-that-does-not-fit-${"x".repeat(40)}.kt"
        for (index in 1..8) openHistory(robot, Path.of("/repo/$index-$long"))
        WaitForAsyncUtils.waitForFxEvents()

        robot.interact {
            val header = view.header.localToScene(view.header.layoutBounds)
            val refresh = view.refreshButton.localToScene(view.refreshButton.layoutBounds)
            val fetch = view.fetchButton.localToScene(view.fetchButton.layoutBounds)
            assertTrue(refresh.maxX <= header.maxX + 0.5 && refresh.minX >= header.minX, "$refresh in $header")
            assertTrue(fetch.maxX <= refresh.minX + 0.5, "$fetch left of $refresh")
            assertEquals(view.refreshButton.prefWidth(-1.0), view.refreshButton.width, 0.5)
            assertTrue(view.tabs.historyTabs.all { it.label.width <= 180.0 })
            assertTrue(view.tabs.titles.width < view.tabs.titles.prefWidth(-1.0), "the tabs are cut off")
        }
        // The header still collapses the area, the tabs still select.
        click(robot, view.header)
        assertFalse(view.expanded.get())
        click(
            robot,
            view.tabs.historyTabs
                .first()
                .label,
        )
        assertTrue(view.expanded.get())
        assertEquals("History: 1-$long", headerTabs()[1].removeSurrounding("[", "]"))
    }

    @Test
    fun `Shortcut+G in a history goes to the Log and opens Go to`(robot: FxRobot) {
        fileHistoryOf = { _, _ -> renamed }
        robot.interact { view.open(repo) }
        openHistory(robot)
        eventually { assertEquals(4, historySubjects().size) }

        press(robot, historyView.table, KeyCode.G, shortcut = true)

        assertNull(view.tabs.selectedHistoryView)
        assertTrue(WaitForAsyncUtils.asyncFx<Boolean> { view.goToPopup.isShowing }.get())
        // The log's table was never laid out before (the area opened straight into the history): the popup is placed
        // at its top all the same, not over the header.
        robot.interact {
            val table = view.table.localToScreen(view.table.boundsInLocal)
            val header = view.header.localToScreen(view.header.boundsInLocal)
            assertEquals(table.minY, view.goToPopup.popup.y, 5.0, "at the top of the table")
            assertTrue(view.goToPopup.popup.y >= header.maxY, "below the header")
            view.goToPopup.hide()
        }
    }

    @Test
    fun `header messages stay with their tab`(robot: FxRobot) {
        fileHistoryOf = { _, _ -> renamed }
        openAndExpand(robot)
        history = null
        robot.interact { view.refresh() }
        eventually { assertEquals("The git log could not be read.", status(robot)) }

        // The log's failure is the log's.
        openHistory(robot)
        eventually { assertEquals(4, historySubjects().size) }
        assertEquals("", status(robot))

        // A diff's message goes with another tab.
        diffOf = { GitFileDiff.Binary }
        press(robot, historyView.table, KeyCode.ENTER)
        eventually { assertEquals("App.kt is a binary file.", status(robot)) }
        click(robot, view.tabs.logTab.label)
        assertEquals("The git log could not be read.", status(robot))

        // A history that failed while not shown says so when it is again.
        fileHistoryOf = { _, _ -> null }
        robot.interact { view.refresh() }
        WaitForAsyncUtils.waitForFxEvents()
        click(
            robot,
            view.tabs.historyTabs
                .single()
                .label,
        )
        eventually { assertEquals("The history could not be read.", status(robot)) }
        assertEquals(4, historySubjects().size)
    }

    @Test
    fun `closing a tab, another folder and a newer load cancel a history's git`(robot: FxRobot) {
        historyGate = CountDownLatch(1)
        fileHistoryOf = { _, _ -> renamed }
        robot.interact { view.open(repo) }
        openHistory(robot)
        eventually { assertEquals(1, historyCancellations.size) }
        robot.interact { view.refresh() }
        eventually { assertTrue(historyCancellations.first().cancelled) }
        robot.interact {
            checkNotNull(
                view.tabs.historyTabs
                    .single()
                    .closeButton,
            ).fire()
        }
        historyGate?.countDown()
        eventually { assertTrue(historyCancellations.all { it.cancelled }) }

        openHistory(robot)
        eventually { assertEquals(4, historySubjects().size) }
        historyGate = CountDownLatch(1)
        robot.interact { view.refresh() }
        eventually { assertFalse(historyCancellations.last().cancelled) }
        robot.interact { view.open(Path.of("/other")) }
        historyGate?.countDown()
        eventually { assertTrue(historyCancellations.last().cancelled) }
    }
}
