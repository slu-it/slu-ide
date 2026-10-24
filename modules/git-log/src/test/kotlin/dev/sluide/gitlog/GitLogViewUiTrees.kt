package dev.sluide.gitlog

import dev.sluide.gitlog.data.BranchLeaf
import dev.sluide.gitlog.data.BranchNode
import dev.sluide.gitlog.data.BranchParent
import dev.sluide.gitlog.data.ChangeFile
import dev.sluide.gitlog.data.ChangeFolder
import dev.sluide.gitlog.data.ChangeNode
import javafx.event.Event
import javafx.scene.control.TreeCell
import javafx.scene.control.TreeItem
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import org.testfx.api.FxRobot
import org.testfx.util.WaitForAsyncUtils
import kotlin.test.assertTrue

// Lookups and actions of the changed-files tree and the branches tree of a [GitLogViewUiFixture].

internal val GitLogViewUiFixture.changes get() = view.changesPane

/** The expanded tree as indented names, folders ending with `/`. */
internal fun GitLogViewUiFixture.shownTree(): List<String> {
    fun lines(
        item: TreeItem<ChangeNode>,
        indent: String,
    ): List<String> =
        item.children.flatMap { child ->
            when (val node = child.value) {
                is ChangeFolder -> {
                    listOf("$indent${node.name}/") +
                        if (child.isExpanded) lines(child, "$indent  ") else emptyList()
                }

                is ChangeFile -> {
                    listOf("$indent${node.name}")
                }
            }
        }
    return if (changes.tree.isVisible) lines(changes.tree.root, "") else emptyList()
}

internal fun GitLogViewUiFixture.filesPlaceholder() =
    if (changes.placeholder.isVisible && !changes.tree.isVisible) changes.placeholder.text else null

/** The shown cells of the tree, top to bottom. */
internal fun GitLogViewUiFixture.treeCells(robot: FxRobot): List<TreeCell<*>> =
    robot
        .lookup(".git-changes-tree .tree-cell")
        .queryAllAs(TreeCell::class.java)
        .filter { !it.isEmpty && it.item != null }
        .sortedBy { it.index }

/** The cell of the file or folder named [name]. */
internal fun GitLogViewUiFixture.treeCell(
    robot: FxRobot,
    name: String,
): TreeCell<*> = treeCells(robot).single { (it.item as ChangeNode).name == name }

/** Selects the file or folder named [name] in the tree. */
internal fun GitLogViewUiFixture.selectFile(
    robot: FxRobot,
    name: String,
) {
    robot.interact {
        val index =
            (0 until changes.tree.expandedItemCount).single {
                changes.tree
                    .getTreeItem(it)
                    .value.name == name
            }
        changes.tree.selectionModel.clearAndSelect(index)
    }
}

internal fun GitLogViewUiFixture.pressInTree(
    robot: FxRobot,
    code: KeyCode,
    shortcut: Boolean = false,
) {
    val mac = System.getProperty("os.name").startsWith("Mac")
    robot.interact {
        Event.fireEvent(
            changes.tree,
            KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, shortcut && !mac, false, shortcut && mac),
        )
    }
    WaitForAsyncUtils.waitForFxEvents()
}

internal fun GitLogViewUiFixture.showFilesOfFirstCommit(robot: FxRobot) {
    openAndExpand(robot)
    select(robot, 0)
    eventually { assertTrue(shownTree().isNotEmpty()) }
}

internal val GitLogViewUiFixture.branches get() = view.branchesPane

/** The tree as shown: the expanded nodes indented, groups and folders ending with `/`. */
internal fun GitLogViewUiFixture.shownBranches(): List<String> =
    WaitForAsyncUtils
        .asyncFx<List<String>> {
            branchLines()
        }.get()

/** [shownBranches], on the JavaFX thread. */
internal fun GitLogViewUiFixture.branchLines(): List<String> {
    fun lines(
        item: TreeItem<BranchNode>,
        indent: String,
    ): List<String> =
        item.children.flatMap { child ->
            when (val node = child.value) {
                is BranchParent -> {
                    listOf("$indent${node.name}/") +
                        if (child.isExpanded) lines(child, "$indent  ") else emptyList()
                }

                is BranchLeaf -> {
                    listOf("$indent${node.name}")
                }
            }
        }
    return lines(branches.tree.root, "")
}

/** The tree item of the node at [path], also in collapsed nodes. */
internal fun GitLogViewUiFixture.branchItem(path: String): TreeItem<BranchNode> {
    fun find(item: TreeItem<BranchNode>): TreeItem<BranchNode>? =
        if (item.value?.path == path) item else item.children.firstNotNullOfOrNull(::find)
    return checkNotNull(find(branches.tree.root)) { path }
}

/** Selects the nodes at [paths] in the tree, as with the mouse and Shift/Ctrl. */
internal fun GitLogViewUiFixture.selectBranches(
    robot: FxRobot,
    vararg paths: String,
) {
    robot.interact {
        // At once, as a click with Shift does: not one after the other.
        val rows = paths.map { branches.tree.getRow(branchItem(it)) }
        branches.tree.selectionModel.clearSelection()
        branches.tree.selectionModel.selectIndices(rows.first(), *rows.drop(1).toIntArray())
    }
    WaitForAsyncUtils.waitForFxEvents()
}

/** Clicks [cell] at its right end, where a click selects it (not on the disclosure arrow). */
internal fun GitLogViewUiFixture.clickBranch(
    robot: FxRobot,
    cell: TreeCell<*>,
    clickCount: Int = 1,
) {
    val point = WaitForAsyncUtils.asyncFx<javafx.geometry.Point2D> { cell.localToScene(cell.width - 10, 5.0) }.get()
    click(robot, cell, clickCount, point.x, point.y)
}

internal fun GitLogViewUiFixture.branchCell(path: String): TreeCell<*> =
    branches.tree
        .lookupAll(".tree-cell")
        .filterIsInstance<TreeCell<*>>()
        .single { (it.item as? BranchNode)?.path == path }

internal fun GitLogViewUiFixture.openBranches(robot: FxRobot) {
    refs = branchRefs
    openAndExpand(robot)
    eventually {
        assertTrue(
            branches.tree.root.children
                .isNotEmpty(),
        )
    }
}
