package dev.sluide.fileexplorer

import dev.sluide.fileexplorer.tree.FileTreeItem
import dev.sluide.fileicons.FileIcon
import javafx.scene.control.TreeCell
import javafx.scene.layout.HBox
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.kordamp.ikonli.javafx.FontIcon
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.deleteExisting
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The icons of the folders of an [ExplorerRoot] with [FolderIcons]. Needs a display, see the `uiTest` task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class FileExplorerViewFolderIconsUiTest : FileExplorerViewUiFixture() {
    @TempDir
    lateinit var extra: Path

    private val icons = FolderIcons(FileIcon.SCRATCHES, FileIcon.SCRATCH_FOLDER, FileIcon.SCRATCH_FOLDER_OPEN)

    @Start
    fun start(stage: Stage) {
        startView(stage)
        // A venv is also a generated folder: shown so that its icon can be checked.
        explorerSettings.hideGeneratedFolders.set(false)
    }

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
    fun `a library folder shows the lock icon and its tooltip until the setting is turned off`(robot: FxRobot) {
        project.resolve(".venv/lib").createDirectories()
        project.resolve(".venv/pyvenv.cfg").createFile()
        project.resolve("pyvenv.cfg").createFile() // the project itself is a venv: no lock on a top-level node
        robot.interact { view.open(project) }
        assertIcon(robot, ".venv", FileIcon.LIBRARY_FOLDER)
        val cell = robot.lookup(".tree-cell").queryAllAs(TreeCell::class.java).first { it.text == ".venv" }
        assertEquals(
            "Library code (virtual environment): opens read-only\n" +
                "Generated folder: virtual environment (pyvenv.cfg). Hidden while Hide Generated Files is on",
            cell.tooltip.text,
        )
        assertIcon(robot, project.name, FileIcon.FOLDER_OPEN)

        expand(robot, ".venv")
        assertIcon(robot, ".venv", FileIcon.LIBRARY_FOLDER_OPEN)
        assertIcon(robot, "lib", FileIcon.FOLDER)

        robot.interact { libraryCodeSettings.readOnly.set(false) }
        assertIcon(robot, ".venv", FileIcon.GENERATED_FOLDER_OPEN)
        val shown = robot.lookup(".tree-cell").queryAllAs(TreeCell::class.java).first { it.text == ".venv" }
        assertEquals(
            "Generated folder: virtual environment (pyvenv.cfg). Hidden while Hide Generated Files is on",
            shown.tooltip.text,
        )
    }

    @Test
    fun `a compacted pypackages row shows the lock icon`(robot: FxRobot) {
        project.resolve("__pypackages__/3.12/lib").createDirectories()
        project.resolve("__pypackages__/3.12/lib/a.py").createFile()
        robot.interact {
            explorerSettings.compactFolders.set(true)
            view.open(project)
        }
        assertIcon(robot, "lib", FileIcon.LIBRARY_FOLDER)
    }

    @Test
    fun `a folder gets and loses the lock icon when its pyvenv cfg appears or goes`(robot: FxRobot) {
        project.resolve("env/lib").createDirectories()
        project.resolve("later/lib").createDirectories()
        robot.interact { view.open(project) }
        expand(robot, "env")
        assertIcon(robot, "env", FileIcon.FOLDER_OPEN)

        // The loaded folder is watched: its own refresh looks again.
        val cfg = project.resolve("env/pyvenv.cfg").createFile()
        eventually(robot) { assertIcon(robot, "env", FileIcon.LIBRARY_FOLDER_OPEN) }
        cfg.deleteExisting()
        eventually(robot) { assertIcon(robot, "env", FileIcon.FOLDER_OPEN) }

        // A folder never opened is not watched: the refresh of its parent looks again.
        project.resolve("later/pyvenv.cfg").createFile()
        robot.interact { view.refreshAll() }
        assertIcon(robot, "later", FileIcon.LIBRARY_FOLDER)
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

    @Test
    fun `a generated folder shown with the option off gets the cog icon and tooltip, its folders keep theirs`(
        robot: FxRobot,
    ) {
        project.resolve("package.json").createFile()
        project.resolve("node_modules/pkg").createDirectories()
        robot.interact { view.open(project) }

        assertIcon(robot, "node_modules", FileIcon.GENERATED_FOLDER)
        assertEquals(
            "Generated folder: node_modules for package.json. Hidden while Hide Generated Files is on",
            cellOf(robot, "node_modules").tooltip.text,
        )
        expand(robot, "node_modules")
        assertIcon(robot, "node_modules", FileIcon.GENERATED_FOLDER_OPEN)
        assertIcon(robot, "pkg", FileIcon.FOLDER)
    }

    @Test
    fun `a row loses the cog icon and tooltip when git says it is not ignored`(robot: FxRobot) {
        project.resolve("package.json").createFile()
        project.resolve("node_modules").createDirectories()
        robot.interact { view.open(project) }
        assertIcon(robot, "node_modules", FileIcon.GENERATED_FOLDER)

        robot.interact {
            view.ignoredByGit = { false }
            view.gitIgnoresChanged()
        }

        // The folder is hidden only with the option on, so with it off the same row stays, as a normal folder.
        assertIcon(robot, "node_modules", FileIcon.FOLDER)
        assertNull(cellOf(robot, "node_modules").tooltip)
    }

    private fun cellOf(
        robot: FxRobot,
        name: String,
    ) = robot.lookup(".tree-cell").queryAllAs(TreeCell::class.java).first { it.text == name }
}
