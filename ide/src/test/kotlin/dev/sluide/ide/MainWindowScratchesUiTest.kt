package dev.sluide.ide

import dev.sluide.scratches.SCRATCH_TYPES
import dev.sluide.scratches.ScratchType
import javafx.scene.Node
import javafx.scene.control.ListView
import javafx.scene.control.TreeCell
import javafx.scene.control.TreeItem
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.stage.PopupWindow
import javafx.stage.Stage
import javafx.stage.Window
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.deleteExisting
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** UI tests of the scratch files in the main window (ADR 0066). Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class MainWindowScratchesUiTest : MainWindowUiFixture() {
    private val kotlin = SCRATCH_TYPES.single { it.extension == "kt" }

    /** The type each ask was preselected with, recorded by [answering]. */
    private val preselected = mutableListOf<ScratchType?>()

    @Start
    fun start(stage: Stage) = startView(stage)

    /** Answers every ask for the type of a new scratch file with [type], recording the preselected one. */
    private fun answering(type: ScratchType) {
        mainWindow.scratches.askType = { _, onPick ->
            preselected += scratchFiles.lastType
            onPick(type)
        }
    }

    private fun newScratchFile(robot: FxRobot) {
        robot.interact { fileMenuItem(robot, "New Scratch File…").fire() }
        WaitForAsyncUtils.waitForFxEvents()
    }

    private fun openProject(robot: FxRobot) {
        robot.interact { projectService.open(project) }
        WaitForAsyncUtils.waitForFxEvents()
    }

    private fun topLevel(): List<TreeItem<Path>> = fileExplorer.root?.children.orEmpty()

    private fun scratchesNode(): TreeItem<Path> = topLevel().single { it.value == scratchFiles.folder }

    @Test
    fun `offers File - New Scratch File first, with Shortcut+Shift+N`(robot: FxRobot) {
        val items = menu(robot, "File").items

        assertEquals("New Scratch File…", items[0].text)
        val accelerator = items[0].accelerator as KeyCodeCombination
        assertEquals(KeyCode.N, accelerator.code)
        assertEquals(KeyCombination.ModifierValue.DOWN, accelerator.shortcut)
        assertEquals(KeyCombination.ModifierValue.DOWN, accelerator.shift)
        assertEquals("Open Folder…", items[2].text)
    }

    @Test
    fun `the explorer shows the project and the created scratches folder collapsed below it`(robot: FxRobot) {
        assertFalse(Files.exists(scratchFiles.folder), "not before a folder opens")

        openProject(robot)

        assertTrue(Files.isDirectory(scratchFiles.folder))
        assertEquals(listOf(project.toAbsolutePath().normalize(), scratchFiles.folder), topLevel().map { it.value })
        assertEquals(listOf(true, false), topLevel().map { it.isExpanded })
        val texts = robot.lookup(".tree-cell").queryAllAs(TreeCell::class.java).mapNotNull { it.text }
        assertTrue("Scratches" in texts, "rows: $texts")
    }

    @Test
    fun `a new scratch file is created, shown in the explorer and opened with the focus`(robot: FxRobot) {
        openProject(robot)
        answering(kotlin)

        newScratchFile(robot)

        val first = scratchFiles.folder.resolve("scratch.kt")
        assertTrue(Files.isRegularFile(first))
        assertEquals(listOf("scratch.kt"), tabTitles(robot))
        assertEquals(first, editorArea.selectedPath)
        eventually(robot) { assertSame<Node?>(shownArea(robot), stage.scene.focusOwner) }
        assertTrue(scratchesNode().isExpanded)
        assertEquals(first, fileExplorer.selectionModel.selectedItem?.value)

        newScratchFile(robot)

        assertTrue(Files.isRegularFile(scratchFiles.folder.resolve("scratch_1.kt")))
        assertEquals(listOf("scratch.kt", "scratch_1.kt"), tabTitles(robot))
        assertEquals(listOf(null, kotlin), preselected)
    }

    // Real key input on purpose (unlike docs/testing.md advises): only real input sends the KEY_TYPED of the picking
    // Enter that must not reach the newly focused editor.
    @Test
    fun `the type picker creates the type typed with Enter, which does not reach the editor`(robot: FxRobot) {
        openProject(robot)

        newScratchFile(robot)
        robot.write("kt").type(KeyCode.ENTER)
        WaitForAsyncUtils.waitForFxEvents()

        val scratch = scratchFiles.folder.resolve("scratch.kt")
        assertEquals(listOf(scratch), editorArea.openPaths)
        assertEquals(0L, Files.size(scratch))
        assertEquals(listOf("scratch.kt"), tabTitles(robot), "not edited")
        eventually(robot) { assertSame<Node?>(shownArea(robot), stage.scene.focusOwner) }
        robot.interact { assertEquals("", shownArea(robot).text) }

        newScratchFile(robot)

        val list = shownPickerList()
        try {
            robot.interact { assertEquals(kotlin, list.selectionModel.selectedItem) }
        } finally {
            robot.interact { list.scene.window.hide() }
        }
    }

    /** The list of the type picker on screen. */
    private fun shownPickerList(): ListView<*> =
        WaitForAsyncUtils
            .asyncFx<ListView<*>> {
                Window
                    .getWindows()
                    .filterIsInstance<PopupWindow>()
                    .filter { it.isShowing }
                    .mapNotNull { it.scene.root.lookup(".scratch-picker-list") as? ListView<*> }
                    .single()
            }.get()

    @Test
    fun `a scratch file that cannot be created shows an error and opens nothing`(robot: FxRobot) {
        openProject(robot)
        val errors = mutableListOf<String>()
        mainWindow.showError = { _, message -> errors += message }
        answering(kotlin)
        Files.delete(scratchFiles.folder)
        scratchFiles.folder.createFile()

        newScratchFile(robot)

        assertEquals(listOf("The scratch file cannot be created. ${scratchFiles.folder} is in the way."), errors)
        assertEquals(emptyList(), tabTitles(robot))
    }

    @Test
    fun `closing the tab of an empty scratch file deletes it, one with content is kept`(robot: FxRobot) {
        openProject(robot)
        answering(kotlin)
        newScratchFile(robot)
        newScratchFile(robot)
        val empty = scratchFiles.folder.resolve("scratch.kt")
        val written = scratchFiles.folder.resolve("scratch_1.kt").apply { writeText("fun main() {}") }

        robot.interact { editorArea.closeAll() }
        WaitForAsyncUtils.waitForFxEvents()

        assertFalse(Files.exists(empty))
        assertTrue(Files.isRegularFile(written))
    }

    @Test
    fun `an empty scratch file open in another window and an empty project file are kept`(robot: FxRobot) {
        openProject(robot)
        answering(kotlin)
        newScratchFile(robot)
        val scratch = scratchFiles.folder.resolve("scratch.kt")
        openInOtherWindows.add(scratch)
        val projectFile = project.resolve("empty.txt").createFile()
        robot.interact { editorArea.open(projectFile) }

        robot.interact { editorArea.closeAll() }
        WaitForAsyncUtils.waitForFxEvents()

        assertTrue(Files.isRegularFile(scratch))
        assertTrue(Files.isRegularFile(projectFile))
    }

    @Test
    fun `an empty scratch file still open under another name is kept`(robot: FxRobot) {
        openProject(robot)
        answering(kotlin)
        newScratchFile(robot)
        val scratch = scratchFiles.folder.resolve("scratch.kt")
        val link =
            project
                .resolve("link.kt")
                .createSymbolicLinkPointingTo(scratch)
                .toAbsolutePath()
                .normalize()
        robot.interact { editorArea.open(link) }

        robot.interact { editorArea.closeUnder(scratch, emptyList()) }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(listOf(link), editorArea.openPaths)
        assertTrue(Files.isRegularFile(scratch))
    }

    @Test
    fun `closing the window deletes its empty scratch files`(robot: FxRobot) {
        openProject(robot)
        answering(kotlin)
        newScratchFile(robot)

        robot.interact { mainWindow.dispose() }
        WaitForAsyncUtils.waitForFxEvents()

        assertFalse(Files.exists(scratchFiles.folder.resolve("scratch.kt")))
        assertNull(fileExplorer.root)
    }

    @Test
    fun `changes only in the scratches folder do not count for git`(robot: FxRobot) {
        openProject(robot)
        val scratches = mainWindow.scratches
        val scratch = scratchFiles.folder.resolve("scratch.kt")

        assertTrue(scratches.onlyScratches(listOf(scratch, scratchFiles.folder)))
        assertFalse(scratches.onlyScratches(listOf(scratch, project.resolve("a.txt"))))
        assertFalse(scratches.onlyScratches(emptyList()), "a manual refresh")
    }

    @Test
    fun `without a usable scratches folder the explorer shows only the project`(robot: FxRobot) {
        scratchFiles.folder.parent.createDirectories()
        scratchFiles.folder.createFile()

        openProject(robot)

        assertEquals(listOf(project.toAbsolutePath().normalize()), topLevel().map { it.value })
        scratchFiles.folder.deleteExisting()
    }
}
