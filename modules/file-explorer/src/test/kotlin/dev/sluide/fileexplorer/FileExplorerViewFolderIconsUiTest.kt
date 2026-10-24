package dev.sluide.fileexplorer

import dev.sluide.fileexplorer.tree.FileTreeItem
import dev.sluide.fileicons.FileIcon
import dev.sluide.filewatcher.FileWatcher
import dev.sluide.settings.FontSettings
import dev.sluide.settings.SettingsStore
import javafx.application.Platform
import javafx.scene.Scene
import javafx.scene.control.TreeCell
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import javafx.stage.Stage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.kordamp.ikonli.javafx.FontIcon
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

/** The icons of the folders of an [ExplorerRoot] with [FolderIcons]. Needs a display, see the `uiTest` task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class FileExplorerViewFolderIconsUiTest : FileExplorerViewUiFixture() {
    @TempDir
    lateinit var extra: Path

    private val icons = FolderIcons(FileIcon.SCRATCHES, FileIcon.SCRATCH_FOLDER, FileIcon.SCRATCH_FOLDER_OPEN)

    @Start
    fun start(stage: Stage) = startView(stage)

    private fun open(
        robot: FxRobot,
        folderIcons: FolderIcons?,
    ) {
        project.resolve("pdir").createDirectories()
        extra.resolve("sub").createDirectories()
        extra.resolve("Main.kt").createFile()
        robot.interact { view.open(project, listOf(ExplorerRoot(extra, "Extra", folderIcons))) }
    }

    private fun item(name: String): FileTreeItem {
        fun find(item: FileTreeItem): FileTreeItem? =
            if (item.label == name || item.value.name == name) {
                item
            } else {
                item.children.filterIsInstance<FileTreeItem>().firstNotNullOfOrNull(::find)
            }
        return view.root.children
            .filterIsInstance<FileTreeItem>()
            .firstNotNullOf(::find)
    }

    private fun expand(
        robot: FxRobot,
        vararg names: String,
    ) {
        robot.interact { names.forEach { item(it).isExpanded = true } }
    }

    private fun iconOf(
        robot: FxRobot,
        name: String,
    ): Pair<String?, List<String>> {
        val cell =
            robot
                .lookup(".tree-cell")
                .queryAllAs(TreeCell::class.java)
                .first { (it.treeItem as? FileTreeItem)?.let { i -> (i.label ?: i.value.name) == name } == true }
        val icon = (cell.graphic as HBox).children.filterIsInstance<FontIcon>().first()
        return icon.iconCode.description to icon.styleClass.filter { it.startsWith("file-icon-") }
    }

    private fun assertIcon(
        robot: FxRobot,
        name: String,
        expected: FileIcon,
    ) = assertEquals(expected.ikon.description to listOf(expected.styleClass), iconOf(robot, name), name)

    @Test
    fun `a folder in the extra root shows the closed then the open icon`(robot: FxRobot) {
        open(robot, icons)
        expand(robot, "Extra")
        assertIcon(robot, "sub", FileIcon.SCRATCH_FOLDER)

        expand(robot, "sub")
        assertIcon(robot, "sub", FileIcon.SCRATCH_FOLDER_OPEN)
    }

    @Test
    fun `the project and its folders keep the normal folder icons`(robot: FxRobot) {
        open(robot, icons)
        assertIcon(robot, project.name, FileIcon.FOLDER_OPEN)
        assertIcon(robot, "pdir", FileIcon.FOLDER)

        expand(robot, "pdir")
        assertIcon(robot, "pdir", FileIcon.FOLDER_OPEN)

        robot.interact { item("pdir").isExpanded = false }
        assertIcon(robot, "pdir", FileIcon.FOLDER)
    }

    @Test
    fun `a folder created and then renamed after loading shows the closed icon`(robot: FxRobot) {
        open(robot, icons)
        expand(robot, "Extra")

        extra.resolve("later").createDirectories()
        robot.interact { view.refreshAll() }
        assertIcon(robot, "later", FileIcon.SCRATCH_FOLDER)

        view.askName = { _, _, _ -> "renamed" }
        robot.interact { view.rename(item("later")) }
        assertIcon(robot, "renamed", FileIcon.SCRATCH_FOLDER)
    }
}
