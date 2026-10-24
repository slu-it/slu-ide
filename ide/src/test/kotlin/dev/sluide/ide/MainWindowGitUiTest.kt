package dev.sluide.ide

import dev.sluide.editorarea.EditorTab
import dev.sluide.fileviewers.IgnoreFileEffects
import dev.sluide.fileviewers.IgnoreRuleEffects
import dev.sluide.git.GitChange
import dev.sluide.git.GitHunk
import dev.sluide.git.GitStatus
import dev.sluide.gitlog.CommitFileDiff
import dev.sluide.gitlog.WorkingTreeFileDiff
import dev.sluide.ide.git.CommitDiffTab
import dev.sluide.ide.git.WorkingTreeDiffTab
import dev.sluide.projecttrust.ProjectTrustState
import dev.sluide.settings.FontArea
import dev.sluide.settings.FontSpec
import javafx.beans.property.SimpleStringProperty
import javafx.event.Event
import javafx.geometry.Orientation
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.MenuBar
import javafx.scene.control.MenuItem
import javafx.scene.control.TableView
import javafx.scene.control.TreeCell
import javafx.scene.control.TreeView
import javafx.scene.input.ContextMenuEvent
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyEvent
import javafx.scene.layout.Region
import javafx.scene.text.Text
import javafx.stage.Stage
import javafx.stage.Window
import org.fxmisc.richtext.CodeArea
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * UI tests of the main window: the git log and the diffs of changed files. Needs a display, see the `uiTest` Gradle
 * task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class MainWindowGitUiTest : MainWindowUiFixture() {
    @Start
    fun start(stage: Stage) = startView(stage)

    @Test
    fun `a git repository has the git log under the editor area, collapsed to its header until View - Git Log`(
        robot: FxRobot,
    ) {
        assumeTrue(git.available, "git is not installed")
        runGit("init", "-q")
        project.resolve("tracked.md").writeText("one")
        runGit("add", ".")
        runGit("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-q", "-m", "Initial commit")
        val gitLogItem = menu(robot, "View").items.single { it.text == "Git Log" }
        assertTrue(gitLogItem.isDisable, "no folder, no git log")

        robot.interact {
            projectService.open(project)
            editorArea.open(project.resolve("tracked.md"))
        }
        WaitForAsyncUtils.waitForFxEvents()
        // The explorer split stays the outer one and still gets its start width.
        assertEquals(fileExplorer.startWidth(), fileExplorer.width, DIVIDER_SLACK)

        val accelerator = gitLogItem.accelerator as KeyCodeCombination
        assertEquals(KeyCode.DIGIT9, accelerator.code)
        val mac = System.getProperty("os.name").startsWith("Mac")
        assertEquals(
            if (mac) KeyCombination.ModifierValue.DOWN else KeyCombination.ModifierValue.UP,
            accelerator.shortcut,
        )
        assertEquals(if (mac) KeyCombination.ModifierValue.UP else KeyCombination.ModifierValue.DOWN, accelerator.alt)
        assertFalse(gitLogItem.isDisable)
        val logSplit = splitPaneOf(gitLogView)
        assertEquals(Orientation.VERTICAL, logSplit.orientation)
        assertEquals(listOf<Node>(editorArea, gitLogView), logSplit.items)
        assertEquals(listOf<Node>(fileExplorer.parent, logSplit), splitPaneOf(fileExplorer).items)
        val header = robot.lookup(".git-log-header").queryAs(Region::class.java)
        assertTrue(header.height > 0)
        assertEquals(header.height, gitLogView.height, 1.0, "collapsed to its header")
        assertTrue(robot.lookup(".git-log-table").tryQuery<Node>().isEmpty)

        robot.interact { gitLogItem.fire() }
        assertEquals(0.3 * logSplit.height, gitLogView.height, DIVIDER_SLACK + 4, "the lower 30 %")
        val table = robot.lookup(".git-log-table").queryAs(TableView::class.java)
        assertSame<Node?>(table, stage.scene.focusOwner)
        eventually(robot) { assertEquals(listOf("Initial commit"), shownSubjects(robot)) }

        // A commit changes the .git folder, which reloads the expanded log.
        runGit(
            "-c",
            "user.name=Test",
            "-c",
            "user.email=test@example.com",
            "commit",
            "-q",
            "--allow-empty",
            "-m",
            "Second",
        )
        eventually(robot) {
            assertEquals(listOf("Second", "Initial commit"), shownSubjects(robot))
        }

        // The window's height goes to the editor area.
        val expandedHeight = gitLogView.height
        robot.interact { stage.height -= 100 }
        assertEquals(expandedHeight, gitLogView.height, DIVIDER_SLACK)

        // Collapsing gives the focus back to the editor and pins the log to its header.
        robot.interact { logSplit.setDividerPositions(0.5) }
        val dragged = gitLogView.height
        assertTrue(dragged > expandedHeight + 50, "dragged from $expandedHeight to $dragged")
        robot.interact { gitLogItem.fire() }
        assertEquals(header.height, gitLogView.height, 1.0, "collapsed again")
        val editorContent = editorArea.selectedTab?.content
        val focused = stage.scene.focusOwner
        assertTrue(
            editorContent != null && generateSequence(focused) { it.parent }.any { it === editorContent },
            "focus on $focused",
        )
        robot.interact { logSplit.setDividerPositions(0.5) }
        assertEquals(header.height, gitLogView.height, 1.0, "the divider of the collapsed log does not move")

        // Expanding again restores the dragged height, also after the window was resized meanwhile.
        robot.interact { stage.height -= 100 }
        robot.interact { gitLogItem.fire() }
        assertEquals(dragged, gitLogView.height, DIVIDER_SLACK)
    }

    @Test
    fun `Show Diff in the git log opens what a commit changed in a tab once, Open Local File opens the file`(
        robot: FxRobot,
    ) {
        assumeTrue(git.available, "git is not installed")
        val commit = arrayOf("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-q")
        runGit("init", "-q")
        project.resolve("tracked.md").writeText("one\ntwo\n")
        runGit("add", ".")
        runGit(*commit, "-m", "Initial commit")
        val parent = runGit("rev-parse", "HEAD")
        project.resolve("tracked.md").writeText("one\nTWO\n")
        runGit(*commit, "-am", "Change")
        val changed = runGit("rev-parse", "HEAD")

        robot.interact {
            projectService.open(project)
            gitLogView.expanded.set(true)
        }
        eventually(robot) { assertEquals(listOf("Change", "Initial commit"), shownSubjects(robot)) }
        val table = robot.lookup(".git-log-table").queryAs(TableView::class.java)
        robot.interact { table.selectionModel.clearAndSelect(0) }
        val tree = robot.lookup(".git-changes-tree").queryAs(TreeView::class.java)
        eventually(robot) { assertEquals(1, tree.expandedItemCount) }
        robot.interact { tree.selectionModel.clearAndSelect(0) }

        fun press(code: KeyCode) {
            robot.interact {
                Event.fireEvent(
                    tree,
                    KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false),
                )
            }
            WaitForAsyncUtils.waitForFxEvents()
        }
        press(KeyCode.ENTER)
        eventually(robot) { assertTrue(editorArea.selectedTab is CommitDiffTab) }
        val tab = editorArea.selectedTab as CommitDiffTab
        assertTrue(tab.closableByShortcut)
        assertEquals("tracked.md (${changed.take(8)})", tab.title.value)
        assertEquals("tracked.md at ${changed.take(8)}", tab.tooltip)
        val areas =
            robot
                .lookup(".editor-content .code-area")
                .queryAllAs(CodeArea::class.java)
                .sortedBy { it.localToScene(0.0, 0.0).x }
        assertEquals(listOf("one\ntwo", "one\nTWO"), areas.map { it.text })
        robot.interact { fontSettings.apply(mapOf(FontArea.EDITOR to FontSpec("Serif", 22.0))) }
        assertEquals(22.0, codeText(robot, "TWO").font.size, "the diff follows the editor font")
        assertEquals(
            listOf(parent.take(8), changed.take(8)),
            robot
                .lookup(".editor-content .diff-title")
                .queryAllAs(Label::class.java)
                .sortedBy { it.localToScene(0.0, 0.0).x }
                .map { it.text },
        )
        assertTrue(robot.lookup(".editor-content .diff-back").queryAs(Button::class.java).let { !it.isVisible })

        // F4: the file as it is now, in its own tab.
        press(KeyCode.F4)
        assertEquals(project.resolve("tracked.md"), editorArea.selectedPath)

        // Shown again: the same tab.
        press(KeyCode.ENTER)
        eventually(robot) { assertSame(tab, editorArea.selectedTab) }
        assertEquals(1, editorArea.tabs.count { it is CommitDiffTab })
        assertEquals(2, editorArea.tabs.size)
    }

    @Test
    fun `a diff with the working tree opens in a tab once, with the file on disk on the right`(robot: FxRobot) {
        val file = project.resolve("tracked.md").apply { writeText("one\nTWO\n") }
        val commit = "1a2b3c4d".repeat(5)
        val diff =
            WorkingTreeFileDiff(
                project,
                "tracked.md",
                null,
                GitChange.MODIFIED,
                commit,
                "feature",
                "one\ntwo\n",
                // Read with the diff; the tab shows the file as it is on disk.
                "one\nstale\n",
                listOf(GitHunk(1 until 2, 1 until 2)),
            )

        fun areas() =
            robot
                .lookup(".editor-content .code-area")
                .queryAllAs(CodeArea::class.java)
                .sortedBy { it.localToScene(0.0, 0.0).x }
                .map { it.text }

        fun titles() =
            robot
                .lookup(".editor-content .diff-title")
                .queryAllAs(Label::class.java)
                .sortedBy { it.localToScene(0.0, 0.0).x }
                .map { it.text }

        robot.interact { assertTrue(gitLogView.onShowWorkingTreeDiff(diff)) }
        val tab = editorArea.selectedTab as WorkingTreeDiffTab
        assertTrue(tab.closableByShortcut)
        assertEquals("tracked.md (feature ↔ Working Tree)", tab.title.value)
        assertEquals("tracked.md: feature (1a2b3c4d) ↔ Working Tree", tab.tooltip)
        assertEquals(listOf("one\ntwo", "one\nTWO"), areas())
        assertEquals(listOf("feature (1a2b3c4d)", "Working Tree"), titles())
        robot.interact { fontSettings.apply(mapOf(FontArea.EDITOR to FontSpec("Serif", 22.0))) }
        assertEquals(22.0, codeText(robot, "TWO").font.size, "the diff follows the editor font")

        // Again: the same tab, which reads the file again.
        file.writeText("one\nTwo\n")
        robot.interact { assertTrue(gitLogView.onShowWorkingTreeDiff(diff)) }
        assertSame(tab, editorArea.selectedTab)
        assertEquals(1, editorArea.tabs.size)
        assertEquals(listOf("one\ntwo", "one\nTwo"), areas())
        assertEquals(22.0, codeText(robot, "Two").font.size, "the diff read again keeps the editor font")

        // A deleted file: nothing on the right.
        val deleted =
            diff.copy(
                path = "gone.md",
                change = GitChange.DELETED,
                current = "",
                hunks =
                    listOf(
                        GitHunk(0 until 2, 0 until 0),
                    ),
            )
        robot.interact { assertTrue(gitLogView.onShowWorkingTreeDiff(deleted)) }
        assertEquals(2, editorArea.tabs.size)
        assertEquals(listOf("one\ntwo", "\n"), areas(), "filler rows on the right")
        assertEquals(listOf("feature (1a2b3c4d)", "Working Tree (deleted)"), titles())
        val deletedTab = editorArea.selectedTab

        // Back in the working tree: the same tab shows it read again, now with the file on disk.
        project.resolve("gone.md").writeText("one\nthree\n")
        val restored =
            deleted.copy(
                change = GitChange.MODIFIED,
                current = "",
                hunks =
                    listOf(
                        GitHunk(
                            1 until 2,
                            1 until 2,
                        ),
                    ),
            )
        robot.interact { assertTrue(gitLogView.onShowWorkingTreeDiff(restored)) }
        assertSame(deletedTab, editorArea.selectedTab)
        assertEquals(2, editorArea.tabs.size)
        assertEquals(listOf("one\ntwo", "one\nthree"), areas())
        assertEquals(listOf("feature (1a2b3c4d)", "Working Tree"), titles())

        // Unsaved changes in an editor: the right side is the file as saved.
        robot.interact {
            editorArea.open(file)
            shownArea(robot).appendText("unsaved")
        }
        robot.interact { assertTrue(gitLogView.onShowWorkingTreeDiff(diff)) }
        assertSame(tab, editorArea.selectedTab)
        assertEquals(listOf("feature (1a2b3c4d)", "Working Tree (saved)"), titles())
    }

    @Test
    fun `Show History in the explorer opens the file's history in a tab of the git log`(robot: FxRobot) {
        assumeTrue(git.available, "git is not installed")
        val commit = arrayOf("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-q")
        runGit("init", "-q")
        project.resolve("old.md").writeText((1..20).joinToString("") { "line $it\n" })
        runGit("add", ".")
        runGit(*commit, "-m", "Initial commit")
        runGit("mv", "old.md", "tracked.md")
        runGit(*commit, "-m", "Rename")
        project.resolve("other.md").writeText("other\n")
        runGit("add", ".")
        runGit(*commit, "-m", "Other file")
        project.resolve("folder").createDirectories()

        robot.interact { projectService.open(project) }
        // Show History needs the git log, which opens once the folder's trust check is through.
        eventually(robot) { assertIs<ProjectTrustState.Trusted>(projectTrust.state.get()) }

        fun showHistoryItem(name: String): MenuItem {
            val cell =
                robot
                    .lookup(".tree-cell")
                    .queryAllAs(TreeCell::class.java)
                    .single { (it.item as? Path)?.name == name }
            robot.interact {
                Event.fireEvent(
                    cell,
                    ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 1.0, 1.0, 1.0, 1.0, false, null),
                )
            }
            WaitForAsyncUtils.waitForFxEvents()
            val item = fileExplorer.contextMenu.items.single { it.text == "Show History" }
            robot.interact { fileExplorer.contextMenu.hide() }
            return item
        }
        assertTrue(showHistoryItem("folder").isDisable)
        val item = showHistoryItem("tracked.md")
        assertFalse(item.isDisable)

        robot.interact { item.fire() }

        assertTrue(gitLogView.expanded.get())
        assertEquals(
            listOf("History: tracked.md", "Log"),
            robot
                .lookup(".git-log-tab-title")
                .queryAllAs(Label::class.java)
                .map { it.text }
                .sorted(),
        )
        eventually(robot) { assertEquals(listOf("Rename", "Initial commit"), shownSubjects(robot)) }
    }

    @Test
    fun `a write of the git log reloads the log when it ends`(robot: FxRobot) {
        assumeTrue(git.available, "git is not installed")
        runGit("init", "-q")
        runGit(
            "-c",
            "user.name=Test",
            "-c",
            "user.email=test@example.com",
            "commit",
            "-q",
            "--allow-empty",
            "-m",
            "One",
        )
        robot.interact {
            projectService.open(project)
            gitLogView.expanded.set(true)
        }
        eventually(robot) { assertEquals(listOf("One"), shownSubjects(robot)) }

        // The log's operations tell the status service, which holds its cycles meanwhile.
        assertEquals<Any>(gitStatus::writeStarted, gitLogView.onWriteStarted)
        assertEquals<Any>(gitStatus::writeFinished, gitLogView.onWriteFinished)
        // As the log's operations do: start, git writes, end. That it reloads once is GitStatusServiceCascadeTest's.
        robot.interact { gitLogView.onWriteStarted(true) }
        runGit(
            "-c",
            "user.name=Test",
            "-c",
            "user.email=test@example.com",
            "commit",
            "-q",
            "--allow-empty",
            "-m",
            "Two",
        )
        robot.interact { gitLogView.onWriteFinished() }

        eventually(robot) { assertEquals(listOf("Two", "One"), shownSubjects(robot)) }
    }

    @Test
    fun `folders that are no git repository have no git log`(robot: FxRobot) {
        assertTrue(robot.lookup(".git-log").tryQuery<Node>().isEmpty, "no folder")
        project.resolve("README.md").createFile()

        robot.interact { projectService.open(project) }

        assertTrue(robot.lookup(".git-log").tryQuery<Node>().isEmpty)
        assertEquals(listOf<Node>(fileExplorer.parent, editorArea), splitPaneOf(editorArea).items)
        assertTrue(menu(robot, "View").items.single { it.text == "Git Log" }.isDisable)
        assertTrue(menu(robot, "Git").items.single { it.text == "Commit…" }.isDisable)
        assertTrue(menu(robot, "Git").items.single { it.text == "Push…" }.isDisable)
    }

    @Test
    fun `Git - Commit opens the Commit tab of the git log with the changes and the message focused`(robot: FxRobot) {
        assumeTrue(git.available, "git is not installed")
        runGit("init", "-q")
        project.resolve("tracked.md").writeText("one")
        runGit("add", ".")
        runGit("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-q", "-m", "Initial commit")
        val menus = robot.lookup(".menu-bar").queryAs(MenuBar::class.java).menus
        val git = menus.indexOfFirst { it.text == "Git" }
        assertEquals(listOf("View", "Git", "Terminal"), menus.subList(git - 1, git + 2).map { it.text })
        val commitItem = menu(robot, "Git").items.single { it.text == "Commit…" }
        val accelerator = commitItem.accelerator as KeyCodeCombination
        assertEquals(KeyCode.K, accelerator.code)
        assertEquals(KeyCombination.ModifierValue.DOWN, accelerator.shortcut)
        assertEquals(KeyCombination.ModifierValue.UP, accelerator.shift)
        assertTrue(commitItem.isDisable, "no folder, no git log")
        val pushItem = menu(robot, "Git").items.single { it.text == "Push…" }
        assertEquals(listOf("Commit…", "Push…"), menu(robot, "Git").items.map { it.text })
        val pushAccelerator = pushItem.accelerator as KeyCodeCombination
        assertEquals(KeyCode.K, pushAccelerator.code)
        assertEquals(KeyCombination.ModifierValue.DOWN, pushAccelerator.shortcut)
        assertEquals(KeyCombination.ModifierValue.DOWN, pushAccelerator.shift)
        assertTrue(pushItem.isDisable, "no folder, no git log")

        project.resolve("tracked.md").writeText("two")
        project.resolve("new.md").writeText("new")
        robot.interact { projectService.open(project) }
        // The git log, and with it Commit and Push, opens once the folder's trust check is through.
        eventually(robot) { assertFalse(commitItem.isDisable) }
        assertFalse(pushItem.isDisable)
        robot.interact { commitItem.fire() }

        eventually(robot) {
            val groups =
                robot
                    .lookup(".git-commit-group")
                    .queryAllAs(Label::class.java)
                    .map { it.text }
                    .sorted()
            assertEquals(listOf("Changes", "Unversioned Files"), groups)
        }
        val message = robot.lookup(".git-commit-message").queryAs(Node::class.java)
        assertSame<Node?>(message, stage.scene.focusOwner)

        robot.interact { pushItem.fire() }
        eventually(robot) {
            assertTrue(
                Window.getWindows().any {
                    it is Stage && it.isShowing &&
                        it.title?.startsWith("Push Commits") == true
                },
            )
        }
        robot.interact {
            Window
                .getWindows()
                .filter {
                    it is Stage && it.title?.startsWith(
                        "Push Commits",
                    ) == true
                }.forEach { it.hide() }
        }
    }

    @Test
    fun `a diff of a file that cannot be shown as text opens no tab and says so in the git log`(robot: FxRobot) {
        val commit = "c".repeat(40)
        val picture =
            CommitFileDiff(
                project,
                "docs/pic.bin",
                null,
                GitChange.MODIFIED,
                commit,
                "p".repeat(40),
                "",
                "",
                emptyList(),
            )
        assumeTrue(git.available, "git is not installed")
        runGit("init", "-q")
        runGit(
            "-c",
            "user.name=Test",
            "-c",
            "user.email=test@example.com",
            "commit",
            "-q",
            "--allow-empty",
            "-m",
            "One",
        )
        robot.interact {
            projectService.open(project)
            gitLogView.expanded.set(true)
        }
        eventually(robot) { assertEquals(listOf("One"), shownSubjects(robot)) }
        robot.interact { gitLogView.onShowDiff(picture) }
        assertEquals(0, editorArea.tabs.size)
        assertEquals(
            "pic.bin cannot be shown as text.",
            robot.lookup(".git-log-status").queryAs(Label::class.java).text,
        )

        val working =
            WorkingTreeFileDiff(project, "docs/pic.bin", null, GitChange.MODIFIED, commit, "main", "", "", emptyList())
        robot.interact { assertFalse(gitLogView.onShowWorkingTreeDiff(working)) }
        assertEquals(0, editorArea.tabs.size)
    }

    @Test
    fun `the window tells the lines of a gitignore file that ignore something`(robot: FxRobot) {
        assumeTrue(git.available, "git is not installed")
        runGit("init", "-q")
        val ignoreFile = project.resolve(".gitignore").apply { writeText("*.log\n") }
        project.resolve("a.log").writeText("log")
        robot.interact { projectService.open(project) }
        // Git runs only once the folder's trust check is through; before, the lookup answers nothing.
        eventually(robot) { assertIs<ProjectTrustState.Trusted>(projectTrust.state.get()) }
        val effects = checkNotNull(editorArea.windowServices.get(IgnoreRuleEffects::class.java))

        val answers = LinkedBlockingQueue<IgnoreFileEffects?>()
        robot.interact { effects.lookup(ignoreFile) { answers.add(it) } }
        eventually(robot) {
            val answer = answers.peek()
            assertEquals(1, answer?.lines?.get(0)?.ignored)
        }
    }

    @Test
    fun `modified files open in the editor with their changes against HEAD marked`(robot: FxRobot) {
        assumeTrue(git.available, "git is not installed")
        runGit("init", "-q")
        project.resolve("tracked.md").writeText("one\ntwo\n")
        runGit("add", ".")
        runGit("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-q", "-m", "Initial commit")
        project.resolve("tracked.md").writeText("one\nTWO\nthree\n")
        project.resolve("new.md").writeText("new\n")

        robot.interact { projectService.open(project) }
        eventually(robot) {
            assertEquals(GitStatus.MODIFIED, gitStatus.snapshot.get().fileStatus(project.resolve("tracked.md")))
        }

        fun showsDiff() = robot.lookup(".editor-content .diff-view").tryQuery<Node>().isPresent
        robot.interact { editorArea.open(project.resolve("new.md")) }
        assertEquals(emptyMap(), shownMarkers(robot), "untracked files are new as a whole")
        robot.interact { editorArea.open(project.resolve("tracked.md")) }
        assertFalse(showsDiff())
        // Git answers in the background (ADR 0062).
        eventually(robot) { assertEquals(mapOf(2 to "diff-modified", 3 to "diff-modified"), shownMarkers(robot)) }

        runGit("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-q", "-am", "Change")
        eventually(robot) { assertEquals(emptyMap(), shownMarkers(robot), "clean files have no markers") }
        assertEquals(project.resolve("tracked.md"), editorArea.selectedPath)

        // Changed again on disk: the editor asks for the diff as soon as the status changes.
        project.resolve("tracked.md").writeText("one\nTWO\nfour\n")
        eventually(robot) { assertEquals(mapOf(3 to "diff-modified"), shownMarkers(robot)) }
        assertFalse(showsDiff())
    }

    @Test
    fun `View - Show Changes with Shortcut+Shift+D switches between the editor and the diff against HEAD`(
        robot: FxRobot,
    ) {
        assumeTrue(git.available, "git is not installed")
        runGit("init", "-q")
        project.resolve("tracked.md").writeText("one\ntwo\n")
        runGit("add", ".")
        runGit("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-q", "-m", "Initial commit")
        project.resolve("tracked.md").writeText("one\nTWO\n")
        project.resolve("new.md").writeText("new\n")
        robot.interact { projectService.open(project) }
        eventually(robot) {
            assertEquals(GitStatus.MODIFIED, gitStatus.snapshot.get().fileStatus(project.resolve("tracked.md")))
        }
        val showChanges = menu(robot, "View").items.single { it.text == "Show Changes" }
        val accelerator = showChanges.accelerator as KeyCodeCombination
        assertEquals(KeyCode.D, accelerator.code)
        assertEquals(KeyCombination.ModifierValue.DOWN, accelerator.shortcut)
        assertEquals(KeyCombination.ModifierValue.DOWN, accelerator.shift)

        fun diffTitles() = robot.lookup(".editor-content .diff-title").queryAllAs(Label::class.java).map { it.text }
        robot.interact { editorArea.open(project.resolve("new.md")) }
        assertTrue(showChanges.isDisable, "untracked files have no diff")
        robot.interact { editorArea.open(project.resolve("tracked.md")) }
        // Enabled once git has answered (ADR 0062).
        eventually(robot) { assertFalse(showChanges.isDisable) }

        val mac = System.getProperty("os.name").startsWith("Mac")
        robot.interact {
            Event.fireEvent(
                shownArea(robot),
                KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.D, true, !mac, false, mac),
            )
        }
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(setOf("HEAD", "Working Tree"), diffTitles().toSet())

        robot.interact { showChanges.fire() }
        assertEquals(emptyList(), diffTitles())

        // Other tabs, e.g. terminals, have no changes to show; switching back enables it again.
        robot.interact {
            editorArea.open(
                object : EditorTab {
                    override val title = SimpleStringProperty("Terminal 1")
                    override val tooltip = "Terminal 1"
                    override val content = Label()
                },
            )
        }
        assertTrue(showChanges.isDisable)
        robot.interact { editorArea.open(project.resolve("new.md")) }
        assertTrue(showChanges.isDisable)
        robot.interact { editorArea.open(project.resolve("tracked.md")) }
        assertFalse(showChanges.isDisable)
    }

    /** The text of the shown tab's code areas that contains [part]. */
    private fun codeText(
        robot: FxRobot,
        part: String,
    ): Text {
        WaitForAsyncUtils.waitForFxEvents()
        return robot
            .lookup(".editor-content .code-area .text")
            .queryAllAs(Text::class.java)
            .first { part in it.text }
    }
}
