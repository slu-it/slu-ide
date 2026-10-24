package dev.sluide.gitlog

import dev.sluide.fileicons.FileIcon
import dev.sluide.git.GitChange
import dev.sluide.git.GitChangedFile
import dev.sluide.git.GitFileDiff
import dev.sluide.git.GitHunk
import javafx.application.ColorScheme
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.control.TreeCell
import javafx.scene.input.KeyCode
import javafx.scene.paint.Paint
import javafx.scene.text.Text
import javafx.stage.Stage
import javafx.util.Duration
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.kordamp.ikonli.javafx.FontIcon
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** UI tests of the git log view: the changed files and their diffs. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class GitLogViewChangedFilesUiTest : GitLogViewUiFixture() {
    @Start
    fun start(stage: Stage) = startView(stage)

    @Test
    fun `shows the changed files above the details as a tree, folders compacted, names coloured`(robot: FxRobot) {
        showFilesOfFirstCommit(robot)

        robot.interact {
            assertEquals(
                listOf("docs/", "  new.md", "src/main/kotlin/dev/", "  One.kt", "  Two.kt", "README.md"),
                shownTree(),
            )
            assertEquals("4 files", changes.header.text)
            assertTrue(changes.header.isVisible)
            assertEquals(listOf("h1" to "h2"), fileLoads.toList())
            // Above the details, half and half.
            assertTrue(changes.localToScene(0.0, 0.0).y < details.localToScene(0.0, 0.0).y)
            assertEquals(changes.height, details.height, 10.0)
            assertEquals(view.inner.width * 0.3, changes.width, 10.0)
        }

        fun nameOf(cell: TreeCell<*>) = (cell.graphic as javafx.scene.Parent).childrenUnmodifiable[1] as Label
        robot.interact {
            val colours = listOf("One.kt", "Two.kt", "README.md", "new.md").map { nameOf(treeCell(robot, it)).textFill }
            assertEquals(
                listOf("#1e63c8", "#2e7d32", "#8a8a8a", "#1e63c8").map(javafx.scene.paint.Color::web),
                colours,
            )
            val renamed = treeCell(robot, "new.md")
            val old = (renamed.graphic as javafx.scene.Parent).childrenUnmodifiable[2] as Label
            assertEquals(" ← docs/old.md", old.text)
            assertTrue(old.isVisible)
            assertEquals("Renamed from docs/old.md", renamed.tooltip?.text)
            assertNull(treeCell(robot, "One.kt").tooltip)
            assertFalse(((treeCell(robot, "One.kt").graphic as javafx.scene.Parent).childrenUnmodifiable[2]).isVisible)
            val folderIcon = (treeCell(robot, "docs").graphic as javafx.scene.Parent).childrenUnmodifiable[0]
            val fileIcon = (treeCell(robot, "One.kt").graphic as javafx.scene.Parent).childrenUnmodifiable[0]
            assertNotEquals(
                (folderIcon as org.kordamp.ikonli.javafx.FontIcon).iconCode,
                (fileIcon as org.kordamp.ikonli.javafx.FontIcon).iconCode,
            )
        }

        fun addedColour(scheme: ColorScheme): Paint {
            robot.interact { view.scene.preferences.colorScheme = scheme }
            WaitForAsyncUtils.waitForFxEvents()
            return nameOf(treeCell(robot, "Two.kt")).textFill
        }
        val light = addedColour(ColorScheme.LIGHT)
        val dark = addedColour(ColorScheme.DARK)
        assertEquals(
            javafx.scene.paint.Color
                .web("#5cbf62"),
            dark,
        )
        assertNotEquals(light, dark)
        assertEquals(light, addedColour(ColorScheme.LIGHT))

        // Selected: the selection text colour, not the change's.
        selectFile(robot, "Two.kt")
        robot.interact {
            assertNotEquals(
                javafx.scene.paint.Color
                    .web("#2e7d32"),
                nameOf(treeCell(robot, "Two.kt")).textFill,
            )
        }
    }

    @Test
    fun `shows file type icons, a folder's open when expanded`(robot: FxRobot) {
        // Few enough rows to all fit into the tree's viewport.
        filesOf =
            {
                listOf(
                    GitChangedFile(GitChange.MODIFIED, "One.kt"),
                    GitChangedFile(GitChange.DELETED, "README.md"),
                    GitChangedFile(GitChange.ADDED, "größe/日本語.kt"),
                    GitChangedFile(GitChange.COPIED, "copy.txt", "orig.txt"),
                )
            }
        showFilesOfFirstCommit(robot)

        fun iconOf(name: String) =
            ((treeCell(robot, name).graphic as javafx.scene.Parent).childrenUnmodifiable[0] as FontIcon)

        robot.interact {
            assertEquals(FileIcon.KOTLIN.ikon, iconOf("One.kt").iconCode)
            assertTrue(FileIcon.KOTLIN.styleClass in iconOf("One.kt").styleClass)
            assertEquals(FileIcon.MARKDOWN.ikon, iconOf("README.md").iconCode)
            assertEquals(FileIcon.FOLDER_OPEN.ikon, iconOf("größe").iconCode)
            assertEquals(FileIcon.KOTLIN.ikon, iconOf("日本語.kt").iconCode, "a name, not a Path, is mapped")
            assertEquals("Copied from orig.txt", treeCell(robot, "copy.txt").tooltip?.text)
            treeCell(robot, "größe").treeItem.isExpanded = false
        }
        WaitForAsyncUtils.waitForFxEvents()
        robot.interact {
            assertEquals(FileIcon.FOLDER.ikon, iconOf("größe").iconCode)
            assertEquals(1, iconOf("größe").styleClass.count { it.startsWith("file-icon-") })
        }
    }

    @Test
    fun `the header notes the first parent of a merge`(robot: FxRobot) {
        history =
            commits(10).mapIndexed { index, commit ->
                if (index == 0) commit.copy(parents = listOf("abcdef0123456789", "side")) else commit
            }
        filesOf = { listOf(GitChangedFile(GitChange.MODIFIED, "a.txt")) }
        showFilesOfFirstCommit(robot)

        robot.interact { assertEquals("1 file (compared with the first parent abcdef01)", changes.header.text) }
        assertEquals(listOf<Pair<String, String?>>("h1" to "abcdef0123456789"), fileLoads.toList())
    }

    @Test
    fun `says when no or several commits are selected, while loading, when reading fails and for no files`(
        robot: FxRobot,
    ) {
        openAndExpand(robot)
        robot.interact { changes.loading.duration = Duration.millis(1.0) }
        robot.interact { assertEquals("No commit selected", filesPlaceholder()) }
        select(robot, 0, 2)
        eventually { assertEquals("2 commits selected", filesPlaceholder()) }

        fileGates["h2"] = CountDownLatch(1)
        select(robot, 1)
        eventually { assertEquals("Loading…", filesPlaceholder()) }
        fileGates.getValue("h2").countDown()
        eventually { assertTrue(shownTree().isNotEmpty()) }

        filesOf = { hash -> if (hash == "h4") null else emptyList() }
        select(robot, 3)
        eventually { assertEquals("The changed files could not be read.", filesPlaceholder()) }
        select(robot, 4)
        eventually { assertEquals("No changed files", filesPlaceholder()) }
        robot.interact { assertFalse(changes.header.isVisible) }
    }

    @Test
    fun `a refresh that keeps the commit selected does not read its files again and keeps the selected file`(
        robot: FxRobot,
    ) {
        showFilesOfFirstCommit(robot)
        selectFile(robot, "Two.kt")
        val header = changes.header.text
        // What a commit changed cannot change; a fake that would answer otherwise is not asked (ADR 0062).
        filesOf = { someFiles + GitChangedFile(GitChange.ADDED, "added.txt") }

        refreshAndAwaitReload(robot)

        assertEquals(1, fileLoads.size)
        robot.interact {
            assertEquals(header, changes.header.text)
            assertEquals("src/main/kotlin/dev/Two.kt", changes.selectedFile?.path)
        }
    }

    @Test
    fun `double-click, Enter and Shortcut+D show the diff of the selected file`(robot: FxRobot) {
        val shown = CopyOnWriteArrayList<CommitFileDiff>()
        view.onShowDiff = { shown += it }
        showFilesOfFirstCommit(robot)

        selectFile(robot, "new.md")
        click(robot, treeCell(robot, "new.md"), clickCount = 2)
        eventually { assertEquals(1, shown.size) }
        assertEquals(
            CommitFileDiff(
                repo,
                "docs/new.md",
                "docs/old.md",
                GitChange.RENAMED,
                "h1",
                "h2",
                "old docs/new.md\n",
                "new docs/new.md\n",
                listOf(GitHunk(0..0, 0..0)),
            ),
            shown.single(),
        )

        selectFile(robot, "One.kt")
        pressInTree(robot, KeyCode.ENTER)
        eventually { assertEquals(2, shown.size) }
        assertEquals("src/main/kotlin/dev/One.kt", shown.last().path)

        selectFile(robot, "Two.kt")
        pressInTree(robot, KeyCode.D, shortcut = true)
        eventually { assertEquals(3, shown.size) }
        assertEquals(GitChange.ADDED, shown.last().change)

        robot.interact { changes.showDiffItem.fire() }
        eventually { assertEquals(4, shown.size) }
        assertEquals(
            listOf(
                "docs/new.md",
                "src/main/kotlin/dev/One.kt",
                "src/main/kotlin/dev/Two.kt",
                "src/main/kotlin/dev/Two.kt",
            ),
            diffLoads.map { it.third },
        )
        assertTrue(diffLoads.all { it.first == "h1" && it.second == "h2" })
    }

    @Test
    fun `folders have no actions, Enter opens and closes them`(robot: FxRobot) {
        val shown = CopyOnWriteArrayList<CommitFileDiff>()
        view.onShowDiff = { shown += it }
        showFilesOfFirstCommit(robot)

        selectFile(robot, "src/main/kotlin/dev")
        robot.interact {
            assertTrue(changes.showDiffItem.isDisable)
            assertTrue(changes.openFileItem.isDisable)
            assertTrue(changes.copyPathItem.isDisable)
        }
        pressInTree(robot, KeyCode.ENTER)
        robot.interact { assertEquals(listOf("docs/", "  new.md", "src/main/kotlin/dev/", "README.md"), shownTree()) }
        pressInTree(robot, KeyCode.ENTER)
        robot.interact { assertEquals(6, shownTree().size) }
        pressInTree(robot, KeyCode.D, shortcut = true)
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(emptyList(), shown)
        assertEquals(emptyList(), diffLoads)
    }

    @Test
    fun `a binary or too large file, or a failure, is told in the header instead`(robot: FxRobot) {
        val shown = CopyOnWriteArrayList<CommitFileDiff>()
        view.onShowDiff = { shown += it }
        diffOf = { file ->
            when (file.path) {
                "README.md" -> GitFileDiff.Binary
                "docs/new.md" -> GitFileDiff.TooLarge
                else -> null
            }
        }
        showFilesOfFirstCommit(robot)

        selectFile(robot, "README.md")
        pressInTree(robot, KeyCode.ENTER)
        eventually { assertEquals("README.md is a binary file.", status(robot)) }
        selectFile(robot, "new.md")
        robot.interact { assertEquals("", status(robot), "until the next selection") }
        pressInTree(robot, KeyCode.ENTER)
        eventually { assertEquals("new.md is too large to compare.", status(robot)) }
        selectFile(robot, "One.kt")
        pressInTree(robot, KeyCode.ENTER)
        eventually { assertEquals("The diff could not be read.", status(robot)) }
        assertEquals(emptyList(), shown)

        robot.interact { view.showStatus("One.kt cannot be shown as text.") }
        robot.interact { assertEquals("One.kt cannot be shown as text.", status(robot)) }
        select(robot, 1)
        robot.interact { assertEquals("", status(robot), "until the next commit is selected") }
    }

    @Test
    fun `F4 and Open Local File open the file in the working tree, not a deleted one`(
        robot: FxRobot,
        @TempDir root: Path,
    ) {
        Files.createDirectories(root.resolve("src/main/kotlin/dev"))
        Files.writeString(root.resolve("src/main/kotlin/dev/One.kt"), "")
        otherHistory = history
        val opened = CopyOnWriteArrayList<Path>()
        view.onOpenFile = { opened.add(it) }
        robot.interact {
            view.open(root)
            view.expanded.set(true)
        }
        eventually { assertTrue(view.table.items.isNotEmpty()) }
        select(robot, 0)
        eventually { assertTrue(shownTree().isNotEmpty()) }

        selectFile(robot, "One.kt")
        robot.interact { assertFalse(changes.openFileItem.isDisable) }
        pressInTree(robot, KeyCode.F4)
        assertEquals(listOf(root.resolve("src/main/kotlin/dev/One.kt")), opened.toList())
        robot.interact { changes.openFileItem.fire() }
        assertEquals(2, opened.size)

        selectFile(robot, "README.md")
        robot.interact { assertTrue(changes.openFileItem.isDisable, "deleted") }
        pressInTree(robot, KeyCode.F4)
        // Added in the commit, but not in the working tree.
        selectFile(robot, "Two.kt")
        robot.interact { assertTrue(changes.openFileItem.isDisable) }
        pressInTree(robot, KeyCode.F4)
        assertEquals(2, opened.size)
    }

    @Test
    fun `Copy Path copies the path relative to the root`(robot: FxRobot) {
        showFilesOfFirstCommit(robot)
        selectFile(robot, "new.md")

        robot.interact { changes.copyPathItem.fire() }

        assertEquals("docs/new.md", clipboard())
    }

    @Test
    fun `the tree takes the focus, and collapsing the log with the focus in it returns the focus`(robot: FxRobot) {
        showFilesOfFirstCommit(robot)
        var returned = 0
        view.returnFocus = { returned++ }

        click(robot, treeCell(robot, "One.kt"))
        assertSame<Node?>(changes.tree, view.scene.focusOwner)
        robot.interact { view.expanded.set(false) }

        assertEquals(1, returned)
    }

    @Test
    fun `a submodule or a file without content changes is told in the header instead of a diff`(robot: FxRobot) {
        val shown = CopyOnWriteArrayList<CommitFileDiff>()
        view.onShowDiff = { shown += it }
        val old = "1234567890".repeat(4)
        val new = "abcdef0123".repeat(4)
        filesOf = {
            listOf(
                GitChangedFile(GitChange.MODIFIED, "lib/moved"),
                GitChangedFile(GitChange.ADDED, "lib/added"),
                GitChangedFile(GitChange.DELETED, "lib/removed"),
                GitChangedFile(GitChange.MODIFIED, "run.sh"),
                GitChangedFile(GitChange.RENAMED, "new.txt", "old.txt"),
                GitChangedFile(GitChange.ADDED, "empty.txt"),
            )
        }
        diffOf = { file ->
            when (file.path) {
                "lib/moved" -> GitFileDiff.Submodule(old, new)
                "lib/added" -> GitFileDiff.Submodule(null, new)
                "lib/removed" -> GitFileDiff.Submodule(old, null)
                "empty.txt" -> GitFileDiff.Text("", "", emptyList())
                else -> GitFileDiff.Text("same\n", "same\n", emptyList())
            }
        }
        showFilesOfFirstCommit(robot)

        fun showDiffOf(name: String): String {
            selectFile(robot, name)
            pressInTree(robot, KeyCode.ENTER)
            eventually { assertEquals(diffLoads.last().third.substringAfterLast('/'), name) }
            WaitForAsyncUtils.waitForFxEvents()
            return status(robot)
        }
        assertEquals("moved is a submodule: 12345678 → abcdef01.", showDiffOf("moved"))
        assertEquals("added is a submodule, added at abcdef01.", showDiffOf("added"))
        assertEquals("removed is a submodule, removed.", showDiffOf("removed"))
        assertEquals("run.sh has no content changes.", showDiffOf("run.sh"))
        assertEquals("new.txt has no content changes.", showDiffOf("new.txt"))
        assertEquals(emptyList(), shown)

        // An added empty file is a change of its own: its diff opens.
        selectFile(robot, "empty.txt")
        pressInTree(robot, KeyCode.ENTER)
        eventually { assertEquals(listOf("empty.txt"), shown.map { it.path }) }
    }

    @Test
    fun `a late message about a diff is not shown once the selection has changed`(robot: FxRobot) {
        diffOf = { GitFileDiff.Binary }
        showFilesOfFirstCommit(robot)

        diffGate = CountDownLatch(1)
        selectFile(robot, "README.md")
        pressInTree(robot, KeyCode.ENTER)
        selectFile(robot, "One.kt")
        checkNotNull(diffGate).countDown()
        eventually { assertEquals(1, diffLoads.size) }
        awaitReadersIdle()
        robot.interact { assertEquals("", status(robot)) }

        diffGate = CountDownLatch(1)
        pressInTree(robot, KeyCode.ENTER)
        select(robot, 1)
        checkNotNull(diffGate).countDown()
        eventually { assertEquals(2, diffLoads.size) }
        awaitReadersIdle()
        robot.interact { assertEquals("", status(robot), "another commit selected") }
    }

    @Test
    fun `a refresh of the same commit keeps collapsed folders collapsed`(robot: FxRobot) {
        showFilesOfFirstCommit(robot)
        selectFile(robot, "docs")
        pressInTree(robot, KeyCode.ENTER)
        robot.interact {
            assertEquals(listOf("docs/", "src/main/kotlin/dev/", "  One.kt", "  Two.kt", "README.md"), shownTree())
        }

        refreshAndAwaitReload(robot)
        assertEquals(1, fileLoads.size, "not read again (ADR 0062)")
        robot.interact {
            assertEquals(listOf("docs/", "src/main/kotlin/dev/", "  One.kt", "  Two.kt", "README.md"), shownTree())
        }

        // Another commit: everything expanded.
        select(robot, 1)
        eventually { assertEquals(6, shownTree().size) }
    }
}
