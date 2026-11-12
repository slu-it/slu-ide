package dev.sluide.codereview.window

import dev.sluide.codereview.ReviewBase
import dev.sluide.codereview.ReviewChange
import dev.sluide.codereview.ReviewCommit
import dev.sluide.codereview.ReviewFile
import dev.sluide.codereview.ReviewScope
import dev.sluide.codereview.list.ReviewDiffView
import dev.sluide.codereview.model.ExpandDirection
import dev.sluide.codereview.model.ReviewRow
import dev.sluide.codereview.model.ReviewSelection
import dev.sluide.codereview.model.ReviewTreeNode
import dev.sluide.codereview.model.load
import dev.sluide.codereview.model.oneModifiedLine
import dev.sluide.projectdata.ProjectDataStore
import dev.sluide.settings.FontSettings
import dev.sluide.settings.SettingsStore
import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.collections.ObservableList
import javafx.event.Event
import javafx.geometry.Orientation
import javafx.geometry.Rectangle2D
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.ContextMenu
import javafx.scene.control.ListCell
import javafx.scene.control.ScrollBar
import javafx.scene.control.SeparatorMenuItem
import javafx.scene.control.TreeCell
import javafx.scene.control.skin.VirtualFlow
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.Region
import javafx.scene.text.Text
import javafx.stage.Stage
import javafx.stage.Window
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A diff view whose top file the test sets and that records the scrolls the window asks for. */
private class TopDiffView : ReviewDiffView {
    override val node: Node = Region().apply { isFocusTraversable = true }
    override var onExpand: (String, Int, ExpandDirection) -> Unit = { _, _, _ -> }
    override var onViewedChanged: (String, Boolean) -> Unit = { _, _ -> }
    override var onCollapsedChanged: (String, Boolean) -> Unit = { _, _ -> }
    override var onLoadDiff: (String) -> Unit = {}
    val top = ReadOnlyObjectWrapper<String?>(null)
    override val topFile: ReadOnlyObjectProperty<String?> = top.readOnlyProperty
    val scrolls = CopyOnWriteArrayList<Pair<Int, Double>>()

    override fun scrollToRow(
        index: Int,
        offset: Double,
    ) {
        scrolls += index to offset
    }

    override fun firstVisible() = 0 to 0.0

    /** The rows the test says are on screen. */
    val shown = mutableSetOf<Int>()

    override fun rowCell(index: Int): Node? = if (index in shown) Region() else null
}

/** What needs a scene: the toolbar pickers and the files pane wired to the window, the loader and the diff view. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class ReviewWindowPickersUiTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var ide: Stage
    private val source =
        FakeSource(
            listOf(
                file("a.kt"),
                file("src/b.kt"),
                file("src/c.kt"),
                ReviewFile(ReviewChange.DELETED, "src/gone.kt"),
                ReviewFile(ReviewChange.RENAMED, "src/new.kt", "src/old.kt"),
            ),
        )
    private val store by lazy { ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList()) }
    private val fonts by lazy {
        FontSettings(SettingsStore(dir.resolve("settings.properties")), FontSettings.systemDefaults())
    }

    @Start
    fun start(stage: Stage) {
        ide = stage
        stage.scene = Scene(Region(), 300.0, 200.0)
        stage.show()
    }

    private fun window(
        robot: FxRobot,
        scope: ReviewScope = ReviewScope.Branch("HEAD"),
    ): ReviewWindow {
        var window: ReviewWindow? = null
        robot.interact {
            window =
                ReviewWindow(
                    dir.resolve("project"),
                    scope,
                    ide,
                    Rectangle2D(0.0, 0.0, 800.0, 600.0),
                    source,
                    fonts,
                    ReviewPrefs(store),
                    { _, _ -> null },
                    ReviewActions({ _, _ -> }, {}),
                    diffViewFactory = { _: ObservableList<ReviewRow>, _ -> TopDiffView() },
                ).also(ReviewWindow::show)
        }
        return checkNotNull(window).also { loaded(it) }
    }

    private fun <T> fx(read: () -> T): T = WaitForAsyncUtils.asyncFx<T>(read).get()

    private fun eventually(assertion: () -> Unit) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (true) {
            WaitForAsyncUtils.waitForFxEvents()
            val failure = runCatching(assertion).exceptionOrNull() ?: return
            if (System.nanoTime() > deadline) throw failure
            Thread.sleep(50) // polling for the review thread's result
        }
    }

    private fun loaded(window: ReviewWindow) {
        eventually {
            assertFalse(fx { window.loading.get() }, "loaded")
            assertTrue(fx { window.resolved.get() != null })
        }
    }

    private fun ReviewWindow.top() = diffView as TopDiffView

    private fun click(
        node: Node,
        shift: Boolean = false,
    ) {
        Event.fireEvent(
            node,
            MouseEvent(
                MouseEvent.MOUSE_CLICKED,
                1.0,
                1.0,
                1.0,
                1.0,
                MouseButton.PRIMARY,
                1,
                shift,
                false,
                false,
                false,
                true,
                false,
                false,
                true,
                false,
                true,
                null,
            ),
        )
    }

    private fun key(
        node: Node,
        code: KeyCode,
        shift: Boolean = false,
        shortcut: Boolean = false,
    ) {
        val mac = System.getProperty("os.name").contains("Mac", ignoreCase = true)
        Event.fireEvent(
            node,
            KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, shortcut && !mac, false, shortcut && mac),
        )
    }

    private fun entryCell(
        window: ReviewWindow,
        text: String,
    ): ListCell<*> =
        window.pickers.commitList
            .lookupAll(".list-cell")
            .filterIsInstance<ListCell<*>>()
            .first { (it.item as? PickerEntry)?.text?.startsWith(text) == true }

    private fun fileCell(
        window: ReviewWindow,
        path: String,
    ): TreeCell<*> =
        window.files.tree
            .lookupAll(".tree-cell")
            .filterIsInstance<TreeCell<*>>()
            .first { (it.item as? ReviewTreeNode)?.path == path }

    private fun treeFilePaths(window: ReviewWindow): List<String> {
        fun walk(item: javafx.scene.control.TreeItem<ReviewTreeNode>): List<String> =
            listOfNotNull(item.value?.file?.path) + item.children.flatMap(::walk)
        return walk(window.files.tree.root)
    }

    @Test
    fun `a base item resolves the review again with that base, the default ones first`(robot: FxRobot) {
        source.baseList =
            listOf(
                ReviewBase("refs/heads/main", "main", true),
                ReviewBase("refs/heads/x", "x", false),
            )
        val window = window(robot)

        eventually {
            assertEquals(
                listOf("main (default)", "<separator>", "x"),
                fx {
                    window.pickers.baseButton.items
                        .map { if (it is SeparatorMenuItem) "<separator>" else it.text }
                },
            )
        }
        assertEquals("into: main", fx { window.pickers.baseButton.text })
        robot.interact {
            window.pickers.baseButton.items[2]
                .fire()
        }

        eventually { assertEquals(ReviewScope.Branch("HEAD", "refs/heads/x"), source.scopes.last()) }
    }

    @Test
    fun `a click on a commit selects it, Shift+click a range, and the list closes`(robot: FxRobot) {
        val window = window(robot)
        robot.interact { window.pickers.commitButton.fire() }
        assertTrue(fx { window.pickers.commitPopup.isShowing })

        robot.interact { click(entryCell(window, "c3")) }
        robot.interact {
            assertEquals(ReviewSelection.Commits(0, 0), window.selection.get())
            assertFalse(window.pickers.commitPopup.isShowing)
        }
        robot.interact { window.pickers.commitButton.fire() }
        robot.interact { click(entryCell(window, "c1"), shift = true) }

        robot.interact {
            assertEquals(ReviewSelection.Commits(0, 2), window.selection.get())
            assertEquals(
                "c1..c3 (3 commits)",
                window.pickers.commitButton.text
                    .removeSuffix(" ▾"),
            )
        }
        // The anchor stays at c3: a second Shift+click runs from it again.
        robot.interact { window.pickers.commitButton.fire() }
        robot.interact { click(entryCell(window, "c2"), shift = true) }
        robot.interact { assertEquals(ReviewSelection.Commits(0, 1), window.selection.get()) }
        robot.interact { window.pickers.commitButton.fire() }
        robot.interact { click(entryCell(window, "All changes")) }
        robot.interact { assertEquals(ReviewSelection.All, window.selection.get()) }
    }

    @Test
    fun `Down, Enter, Shift+Down and Escape work in the commit list`(robot: FxRobot) {
        val window = window(robot)
        val list = window.pickers.commitList
        robot.interact { window.pickers.commitButton.fire() }

        robot.interact {
            key(list, KeyCode.DOWN)
            key(list, KeyCode.ENTER)
        }
        robot.interact {
            assertEquals(ReviewSelection.Commits(0, 0), window.selection.get())
            assertFalse(window.pickers.commitPopup.isShowing)
        }
        robot.interact { window.pickers.commitButton.fire() }
        robot.interact {
            key(list, KeyCode.DOWN, shift = true)
            key(list, KeyCode.DOWN, shift = true)
            key(list, KeyCode.ENTER)
        }
        robot.interact { assertEquals(ReviewSelection.Commits(0, 2), window.selection.get()) }
        robot.interact { window.pickers.commitButton.fire() }
        robot.interact { key(list, KeyCode.ESCAPE) }
        robot.interact { assertFalse(window.pickers.commitPopup.isShowing) }
    }

    @Test
    fun `the commit list shows every entry without a horizontal scroll bar, also for a long subject`(robot: FxRobot) {
        val long = "A subject that is much longer than the commit list is wide ".repeat(4)
        source.resolved = {
            review(
                commits =
                    listOf(
                        ReviewCommit("c3", "c3", long, "me", Instant.EPOCH, "c2"),
                        ReviewCommit("c2", "c2", "two", "me", Instant.EPOCH, "c1"),
                    ),
            )
        }
        val window = window(robot)

        robot.interact { window.pickers.showCommitPopup() }

        robot.interact {
            val list = window.pickers.entries
            val horizontal =
                list.lookupAll(".scroll-bar").filterIsInstance<ScrollBar>().filter {
                    it.orientation == Orientation.HORIZONTAL && it.isVisible
                }
            assertTrue(horizontal.isEmpty(), "no horizontal scroll bar")
            val last =
                list.lookupAll(".list-cell").filterIsInstance<ListCell<*>>().first {
                    it.index ==
                        list.items.lastIndex
                }
            val cellBottom = last.localToScene(last.layoutBounds).maxY
            assertTrue(cellBottom <= list.localToScene(list.layoutBounds).maxY + 1.0, "the last entry is shown")
        }
    }

    @Test
    fun `the commit picker is hidden for a single commit and shown for several`(robot: FxRobot) {
        source.resolved = { review(commits = listOf(commit("c1", "c0"))) }
        val single = window(robot, ReviewScope.Commits("c1", "c1"))
        robot.interact { assertFalse(single.pickers.commitButton.isVisible) }

        source.resolved = { review() }
        val several = window(robot, ReviewScope.Commits("c1", "c3"))
        robot.interact { assertTrue(several.pickers.commitButton.isVisible) }
    }

    @Test
    fun `the filter text is the document's filter and the tree shows only the matches`(robot: FxRobot) {
        val window = window(robot)
        assertEquals(
            listOf("a.kt", "src/b.kt", "src/c.kt", "src/gone.kt", "src/new.kt"),
            fx { treeFilePaths(window) }.sorted(),
        )

        robot.interact { window.files.filter.text = "SRC/B" }

        robot.interact {
            assertEquals("SRC/B", window.document.filter.get())
            assertEquals(listOf("src/b.kt"), treeFilePaths(window))
        }
        robot.interact { key(window.files.filter, KeyCode.ESCAPE) }
        robot.interact { assertEquals("", window.document.filter.get()) }
    }

    @Test
    fun `a click on a file scrolls the diff to its header`(robot: FxRobot) {
        val window = window(robot)

        robot.interact { click(fileCell(window, "src/c.kt")) }

        robot.interact { assertEquals(window.document.headerRow("src/c.kt") to 0.0, window.top().scrolls.last()) }
    }

    @Test
    fun `the diff's top file selects its node in the tree without scrolling the diff`(robot: FxRobot) {
        val window = window(robot)
        val before = window.top().scrolls.size

        robot.interact { window.top().top.set("src/c.kt") }

        robot.interact {
            assertEquals("src/c.kt", window.files.tree.selectionModel.selectedItem.value.path)
            assertEquals(before, window.top().scrolls.size)
        }
    }

    @Test
    fun `the selected file stays selected while its header is on screen, even when another file is at the top`(
        robot: FxRobot,
    ) {
        val window = window(robot)
        robot.interact { click(fileCell(window, "src/c.kt")) }
        robot.interact { window.top().shown += window.document.headerRow("src/c.kt") }

        robot.interact { window.top().top.set("src/b.kt") }

        robot.interact { assertEquals("src/c.kt", window.files.tree.selectionModel.selectedItem.value.path) }
    }

    @Test
    fun `Shortcut+P focuses the filter`(robot: FxRobot) {
        val window = window(robot)

        robot.interact { window.diffView.node.requestFocus() }
        robot.interact { assertEquals(window.diffView.node, window.stage.scene.focusOwner) }

        robot.interact { key(window.stage.scene.root, KeyCode.P, shortcut = true) }

        robot.interact { assertEquals(window.files.filter, window.stage.scene.focusOwner) }
    }

    @Test
    fun `Enter in the filter scrolls to the first match and focuses the diff, Down enters the tree`(robot: FxRobot) {
        val window = window(robot)
        robot.interact { window.files.filter.text = "src/" }

        robot.interact { key(window.files.filter, KeyCode.ENTER) }

        robot.interact {
            assertEquals(window.document.headerRow("src/b.kt") to 0.0, window.top().scrolls.last())
            assertEquals(window.diffView.node, window.stage.scene.focusOwner)
        }
        robot.interact { key(window.files.filter, KeyCode.DOWN) }
        robot.interact {
            assertEquals("src/b.kt", window.files.tree.selectionModel.selectedItem.value.path)
            assertEquals(window.files.tree, window.stage.scene.focusOwner)
        }
    }

    @Test
    fun `a viewed file shows a check mark and the counter counts it`(robot: FxRobot) {
        val window = window(robot)
        robot.interact {
            assertEquals("0 / 5 viewed", window.files.viewed.text)
            assertFalse(fileCell(window, "a.kt").lookup(".review-viewed-check").isVisible)
        }

        robot.interact { window.document.setViewed("a.kt", true) }

        robot.interact {
            assertEquals("1 / 5 viewed", window.files.viewed.text)
            assertTrue(fileCell(window, "a.kt").lookup(".review-viewed-check").isVisible)
            assertFalse(fileCell(window, "src/b.kt").lookup(".review-viewed-check").isVisible)
        }
    }

    @Test
    fun `a deleted file's name is struck through and coloured apart from a modified one`(robot: FxRobot) {
        val window = window(robot)

        robot.interact {
            val deleted = fileCell(window, "src/gone.kt").lookup(".text") as Text
            val modified = fileCell(window, "a.kt").lookup(".text") as Text
            assertTrue(deleted.isStrikethrough)
            assertFalse(modified.isStrikethrough)
            assertNotEquals(deleted.fill, modified.fill)
            assertEquals("Renamed from src/old.kt", fileCell(window, "src/new.kt").tooltip.text)
            assertNull(fileCell(window, "a.kt").tooltip)
        }
    }

    private fun treeItems(window: ReviewWindow) = window.files.tree.root.children

    private fun drawn(node: Node): List<String> = node.lookupAll(".text").filterIsInstance<Text>().map { it.text }

    @Test
    fun `underscores in a base and a subject are drawn, not eaten as mnemonics`(robot: FxRobot) {
        source.baseList = listOf(ReviewBase("refs/heads/feature_login", "feature_login", true))
        source.resolved = {
            review(
                base = ReviewBase("refs/heads/feature_login", "feature_login", true),
                commits = listOf(ReviewCommit("c3", "c3", "fix_npe", "me", Instant.EPOCH, "c2")),
            )
        }
        val window = window(robot)
        robot.interact { window.selection.set(ReviewSelection.Commits(0, 0)) }

        robot.interact {
            assertTrue(
                "into: feature_login" in drawn(window.pickers.baseButton),
                drawn(window.pickers.baseButton).toString(),
            )
            assertTrue(
                "c3 fix_npe ▾" in drawn(window.pickers.commitButton),
                drawn(window.pickers.commitButton).toString(),
            )
            window.pickers.baseButton.show()
        }
        robot.interact {
            val menu = Window.getWindows().filterIsInstance<ContextMenu>().first { it.isShowing }
            assertTrue("feature_login (default)" in drawn(menu.scene.root), drawn(menu.scene.root).toString())
            window.pickers.baseButton.hide()
        }
    }

    @Test
    fun `Enter on a file scrolls the diff to it, on a folder it opens or closes the folder without scrolling`(
        robot: FxRobot,
    ) {
        val window = window(robot)
        val tree = window.files.tree
        val folder = fx { tree.root.children.first { it.value.file == null } }
        val file = fx { folder.children.first { it.value.path == "src/c.kt" } }
        robot.interact { tree.selectionModel.select(file) }
        val before = window.top().scrolls.size

        robot.interact { key(tree, KeyCode.ENTER) }

        robot.interact {
            assertEquals(before + 1, window.top().scrolls.size)
            assertEquals(window.document.headerRow("src/c.kt") to 0.0, window.top().scrolls.last())
        }
        robot.interact { tree.selectionModel.select(folder) }
        robot.interact { key(tree, KeyCode.ENTER) }
        robot.interact {
            assertFalse(folder.isExpanded)
            key(tree, KeyCode.ENTER)
        }
        robot.interact {
            assertTrue(folder.isExpanded)
            assertEquals(before + 1, window.top().scrolls.size)
        }
    }

    @Test
    fun `the tree is not built again for row changes, and a closed folder stays closed when it is`(robot: FxRobot) {
        val window = window(robot)
        val tree = window.files.tree
        val folder = fx { tree.root.children.first { it.value.file == null } }
        robot.interact { folder.isExpanded = false }
        val items = fx { treeItems(window).toList() }

        robot.interact {
            window.document.setViewed("a.kt", true)
            window.document.load("a.kt", oneModifiedLine(10, 5))
        }

        robot.interact {
            assertTrue(items.zip(treeItems(window)).all { (old, new) -> old === new }, "the same items")
            assertFalse(folder.isExpanded)
        }
        robot.interact {
            window.document.setFiles(listOf(file("a.kt"), file("src/b.kt"), file("src/z.kt"), file("x/y.kt")))
        }
        robot.interact {
            val now = tree.root.children.first { it.value.path == "src" }
            assertFalse(now.isExpanded, "still closed")
            assertTrue(
                tree.root.children
                    .first { it.value.path == "x" }
                    .isExpanded,
                "a new folder is open",
            )
        }
    }

    @Test
    fun `the tree scrolls to the diff's top file only when it is out of view`(robot: FxRobot) {
        source.files = (0 until 100).map { file("d/f%03d.kt".format(it)) }
        val window = window(robot)
        val tree = window.files.tree
        val flow = { tree.lookup(".virtual-flow") as VirtualFlow<*> }

        robot.interact { window.top().top.set("d/f099.kt") }

        robot.interact {
            val row = tree.getRow(tree.selectionModel.selectedItem)
            assertTrue(flow().lastVisibleCell.index >= row, "the row is in view")
            assertTrue(flow().firstVisibleCell.index > 0, "scrolled")
        }
        // Away from the end, where a scroll would not be clamped.
        robot.interact { window.top().top.set("d/f050.kt") }
        val firstBefore = fx { flow().firstVisibleCell.index }

        robot.interact { window.top().top.set("d/f055.kt") }

        robot.interact {
            assertEquals("d/f055.kt", tree.selectionModel.selectedItem.value.path)
            assertEquals(firstBefore, flow().firstVisibleCell.index, "a row in view does not scroll the tree")
        }
    }
}
