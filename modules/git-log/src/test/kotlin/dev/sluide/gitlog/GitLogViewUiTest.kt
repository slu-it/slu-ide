package dev.sluide.gitlog

import dev.sluide.git.GitChange
import dev.sluide.git.GitChangedFile
import dev.sluide.git.GitCommit
import dev.sluide.git.GitCommitDetails
import dev.sluide.git.GitFileDiff
import dev.sluide.git.GitHunk
import dev.sluide.git.GitLogFilter
import dev.sluide.git.GitLogOrder
import dev.sluide.git.GitLogPage
import dev.sluide.git.GitRef
import dev.sluide.git.GitRefKind
import dev.sluide.git.GitRefs
import dev.sluide.git.GitTreePath
import javafx.animation.Animation
import javafx.application.ColorScheme
import javafx.application.Platform
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.TableRow
import javafx.scene.control.TreeCell
import javafx.scene.control.TreeItem
import javafx.scene.control.skin.VirtualFlow
import javafx.scene.effect.DropShadow
import javafx.scene.input.Clipboard
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.paint.Paint
import javafx.scene.shape.Circle
import javafx.scene.shape.LineTo
import javafx.scene.shape.MoveTo
import javafx.scene.text.Text
import javafx.stage.Stage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import javafx.scene.shape.Path as ShapePath

/** Shows the real git log view with a fake loader. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class GitLogViewUiTest {
    private val repo = Path.of("/repo")

    /** The commits of the repository, newest first; `null` makes loading fail. */
    @Volatile
    private var history: List<GitCommit>? = commits(1200)

    /** The same for any other folder. */
    @Volatile
    private var otherHistory: List<GitCommit>? = null

    /** Loads wait for this while it is not `null`. */
    @Volatile
    private var gate: CountDownLatch? = null

    /** The (skip, limit) of every load. */
    private val loads = CopyOnWriteArrayList<Pair<Int, Int>>()

    /** The filter of every load. */
    private val filters = CopyOnWriteArrayList<GitLogFilter>()

    /** The commits each revision reaches, for the revisions of a filter. */
    @Volatile
    private var reachable: Map<String, Set<String>> = emptyMap()

    /** The commit a filter's text names as a hash. */
    @Volatile
    private var hashMatchOf: (GitLogFilter) -> GitCommit? = { null }

    /** The user name of the repository. */
    @Volatile
    private var userName: String? = null

    /** The paths of `HEAD`'s tree. */
    @Volatile
    private var treePaths: List<GitTreePath>? = emptyList()

    /** Reading the user name or the tree's paths of a root waits for its gate, if it has one. */
    private val userNameGates = ConcurrentHashMap<Path, CountDownLatch>()
    private val pathGates = ConcurrentHashMap<Path, CountDownLatch>()

    /** The root of every read of the user name and of the tree's paths, once its gate is open. */
    private val userNameLoads = CopyOnWriteArrayList<Path>()
    private val pathLoads = CopyOnWriteArrayList<Path>()

    /** The details of each commit; `null` makes reading them fail. */
    @Volatile
    private var detailsOf: (String) -> GitCommitDetails? = ::defaultDetails

    /** Reading the details of a commit waits for its gate, if it has one. */
    private val detailGates = ConcurrentHashMap<String, CountDownLatch>()

    /** The hash of every read of details, once its gate is open. */
    private val detailLoads = CopyOnWriteArrayList<String>()

    /** The refs of the repository; `null` makes reading them fail. */
    @Volatile
    private var refs: GitRefs? = null

    /** The files each commit changed; `null` makes reading them fail. */
    @Volatile
    private var filesOf: (String) -> List<GitChangedFile>? = { someFiles }

    /** Reading the changed files of a commit waits for its gate, if it has one. */
    private val fileGates = ConcurrentHashMap<String, CountDownLatch>()

    /** The (commit, first parent) of every read of changed files, once its gate is open. */
    private val fileLoads = CopyOnWriteArrayList<Pair<String, String?>>()

    /** The diff of each changed file; `null` makes reading it fail. */
    @Volatile
    private var diffOf: (GitChangedFile) -> GitFileDiff? = { file ->
        GitFileDiff.Text("old ${file.path}\n", "new ${file.path}\n", listOf(GitHunk(0..0, 0..0)))
    }

    /** The (commit, first parent, path) of every read of a diff. */
    private val diffLoads = CopyOnWriteArrayList<Triple<String, String?, String>>()

    /** Reading a diff waits for this while it is not `null`. */
    @Volatile
    private var diffGate: CountDownLatch? = null

    private lateinit var view: GitLogView

    @Start
    fun start(stage: Stage) {
        val source =
            object : GitLogSource {
                override fun page(
                    root: Path,
                    filter: GitLogFilter,
                    skip: Int,
                    limit: Int,
                ): GitLogPage? {
                    gate?.await(10, TimeUnit.SECONDS)
                    loads += skip to limit
                    filters += filter
                    return (if (root == repo) history else otherHistory)?.let { history ->
                        val all = history.filter { passes(filter, it) }
                        val page = all.drop(skip).take(limit)
                        GitLogPage(page, complete = skip + page.size >= all.size)
                    }
                }

                override fun hashMatch(
                    root: Path,
                    filter: GitLogFilter,
                ) = hashMatchOf(filter)

                override fun userName(root: Path): String? {
                    userNameGates[root]?.await(10, TimeUnit.SECONDS)
                    userNameLoads.add(root)
                    return if (root == repo) userName else null
                }

                override fun paths(root: Path): List<GitTreePath>? {
                    pathGates[root]?.await(10, TimeUnit.SECONDS)
                    pathLoads.add(root)
                    return if (root == repo) treePaths else emptyList()
                }

                override fun refs(root: Path) = if (root == repo) refs else null

                override fun details(
                    root: Path,
                    hash: String,
                ): GitCommitDetails? {
                    detailGates[hash]?.await(10, TimeUnit.SECONDS)
                    detailLoads += hash
                    return detailsOf(hash)
                }

                override fun changedFiles(
                    root: Path,
                    commit: String,
                    firstParent: String?,
                ): List<GitChangedFile>? {
                    fileGates[commit]?.await(10, TimeUnit.SECONDS)
                    fileLoads += commit to firstParent
                    return filesOf(commit)
                }

                override fun fileDiff(
                    root: Path,
                    commit: String,
                    firstParent: String?,
                    file: GitChangedFile,
                ): GitFileDiff? {
                    diffGate?.await(10, TimeUnit.SECONDS)
                    diffLoads += Triple(commit, firstParent, file.path)
                    return diffOf(file)
                }
            }
        view = GitLogView(source, Platform::runLater)
        stage.scene = Scene(view, 800.0, 400.0)
        stage.show()
    }

    @AfterEach
    fun close() {
        gate?.countDown()
        detailGates.values.forEach(CountDownLatch::countDown)
        fileGates.values.forEach(CountDownLatch::countDown)
        diffGate?.countDown()
        userNameGates.values.forEach(CountDownLatch::countDown)
        pathGates.values.forEach(CountDownLatch::countDown)
        view.close()
    }

    /**
     * Whether [commit] passes [filter], as far as the fake understands it: the text as a substring of the subject
     * (ignoring case unless asked), the revisions by [reachable], the users by the author's name.
     */
    private fun passes(
        filter: GitLogFilter,
        commit: GitCommit,
    ): Boolean {
        val text = filter.text.trim()
        return commit.subject.contains(text, ignoreCase = !filter.matchCase) &&
            (filter.revisions.isEmpty() || filter.revisions.any { commit.hash in reachable[it].orEmpty() }) &&
            (filter.users.isEmpty() || filter.users.any { it.equals(commit.author, ignoreCase = true) })
    }

    /** A linear history of [count] commits, newest first. */
    private fun commits(
        count: Int,
        prefix: String = "h",
    ) = (1..count).map {
        GitCommit(
            "$prefix$it",
            if (it < count) listOf("$prefix${it + 1}") else emptyList(),
            "Author $it",
            "a$it@example.com",
            Instant.ofEpochSecond(it * 60L),
            "Subject $it",
        )
    }

    private fun openAndExpand(robot: FxRobot) {
        robot.interact {
            view.open(repo)
            view.expanded.set(true)
        }
        eventually { assertTrue(view.table.items.isNotEmpty()) }
    }

    /** Hides the branches pane and drags the details pane out of view, so that the table takes the whole width. */
    private fun hideDetails(robot: FxRobot) {
        robot.interact {
            bar.branchesToggle.isSelected = false
            view.inner.setDividerPositions(1.0)
        }
        WaitForAsyncUtils.waitForFxEvents()
    }

    /** Waits until [assertion] holds on the JavaFX thread. */
    private fun eventually(assertion: () -> Unit) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (true) {
            WaitForAsyncUtils.waitForFxEvents()
            val failure = WaitForAsyncUtils.asyncFx<Throwable?> { runCatching(assertion).exceptionOrNull() }.get()
            if (failure == null) return
            if (System.nanoTime() > deadline) throw failure
            Thread.sleep(20)
        }
    }

    private fun click(
        robot: FxRobot,
        node: Node,
        clickCount: Int = 1,
        sceneX: Double = 1.0,
        sceneY: Double = 1.0,
    ) {
        robot.interact {
            for (type in listOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED)) {
                Event.fireEvent(
                    node,
                    // Its x and y are the scene's, made local to each node the event reaches.
                    MouseEvent(
                        type,
                        sceneX,
                        sceneY,
                        sceneX,
                        sceneY,
                        MouseButton.PRIMARY,
                        clickCount,
                        false,
                        false,
                        false,
                        false,
                        true,
                        false,
                        false,
                        true,
                        false,
                        false,
                        null,
                    ),
                )
            }
        }
        WaitForAsyncUtils.waitForFxEvents()
    }

    private fun shownSubjects(robot: FxRobot): List<String> =
        robot
            .lookup(".git-log-table .table-row-cell")
            .queryAllAs(TableRow::class.java)
            .filter { !it.isEmpty && it.item != null }
            .sortedBy { it.index }
            .map { (it.item as GitLogRow).commit.subject }

    private fun placeholder(robot: FxRobot) =
        robot.lookup(".git-log-table .placeholder .label").queryAs(Label::class.java).text

    private fun clipboard() = WaitForAsyncUtils.asyncFx<String> { Clipboard.getSystemClipboard().string }.get()

    @Test
    fun `is collapsed at first, shows only the header and loads nothing`(robot: FxRobot) {
        robot.interact { view.open(repo) }
        Thread.sleep(100)
        WaitForAsyncUtils.waitForFxEvents()

        assertFalse(view.expanded.get())
        assertNull(view.center)
        assertTrue(robot.lookup(".git-log-table").tryQuery<Node>().isEmpty)
        assertEquals("Git Log", robot.lookup(".git-log-title").queryAs(Label::class.java).text)
        assertTrue(robot.lookup(".git-log-refresh").tryQuery<Node>().isPresent)
        assertEquals(view.header.prefHeight(-1.0), view.prefHeight(-1.0), 0.5)
        assertEquals(emptyList(), loads)
    }

    @Test
    fun `clicking the header expands with one load and focuses the table, clicking again collapses`(robot: FxRobot) {
        robot.interact { view.open(repo) }
        click(robot, view.header)

        assertTrue(view.expanded.get())
        eventually { assertEquals((1..5).map { "Subject $it" }, shownSubjects(robot).take(5)) }
        assertEquals(listOf(0 to 500), loads)
        assertSame<Node?>(view.table, view.scene.focusOwner)
        assertEquals(500, view.table.items.size)

        var focusReturned = 0
        view.returnFocus = { focusReturned++ }
        click(robot, view.header)
        assertFalse(view.expanded.get())
        assertNull(view.center)
        assertEquals(1, focusReturned, "the table had the focus")

        // Refresh in the header neither expands nor loads while collapsed.
        click(robot, robot.lookup(".git-log-refresh").query())
        assertFalse(view.expanded.get())
        robot.interact { view.expanded.set(true) }
        eventually { assertEquals(listOf(0 to 500, 0 to 500), loads.toList()) }

        // Collapsing without the focus in the log leaves the focus alone.
        robot.interact {
            view.header.requestFocus()
            view.expanded.set(false)
        }
        assertEquals(1, focusReturned)
    }

    @Test
    fun `shows Subject, Author and the short localized author date, the subject taking the most room`(robot: FxRobot) {
        openAndExpand(robot)
        hideDetails(robot)

        val columns = view.table.columns
        assertEquals(listOf("Subject", "Author", "Date"), columns.map { it.text })
        assertEquals("Author 1", columns[1].getCellData(0))
        val date =
            java.time.format.DateTimeFormatter
                .ofLocalizedDateTime(java.time.format.FormatStyle.SHORT)
                .withZone(java.time.ZoneId.systemDefault())
                .format(Instant.ofEpochSecond(60))
        assertEquals(date, columns[2].getCellData(0))
        assertTrue(columns[0].width > columns[1].width + columns[2].width, columns.map { it.width }.toString())

        // A narrower window narrows only the subject. Shrinks before growing back: a window cannot grow past the
        // screen, which on the macOS runners is barely wider than the test's window.
        val start = columns.map { it.width }
        robot.interact { view.scene.window.width -= 300 }
        WaitForAsyncUtils.waitForFxEvents()
        val narrower = columns.map { it.width }
        assertEquals(start[0] - 300, narrower[0], 2.0, "$start -> $narrower")
        assertEquals(start[1], narrower[1], 1.0, "$start -> $narrower")
        assertEquals(start[2], narrower[2], 1.0, "$start -> $narrower")

        // Too narrow for all of them: author and date shrink, the date stays in view.
        robot.interact { view.scene.window.width = 260.0 }
        WaitForAsyncUtils.waitForFxEvents()
        val narrow = columns.map { it.width }
        assertTrue(narrow[1] < start[1] && narrow[2] < start[2], "$start -> $narrow")
        assertTrue(narrow.sum() <= view.table.width, "$narrow in ${view.table.width}")

        robot.interact { view.scene.window.width = 800.0 }
        WaitForAsyncUtils.waitForFxEvents()
        val back = columns.map { it.width }
        for (index in start.indices) assertEquals(start[index], back[index], 1.0, "$start -> $back")

        // Dragging the author's edge takes the room from the date.
        robot.interact { view.table.resizeColumn(columns[1], 30.0) }
        val dragged = columns.map { it.width }
        assertEquals(back[0], dragged[0], 1.0, "$back -> $dragged")
        assertEquals(back[1] + 30, dragged[1], 1.0, "$back -> $dragged")
        assertEquals(back[2] - 30, dragged[2], 1.0, "$back -> $dragged")
    }

    @Test
    fun `scrolling with the wheel loads the next page right away`(robot: FxRobot) {
        openAndExpand(robot)
        val flow = view.table.lookup(".virtual-flow") as VirtualFlow<*>

        // The wheel scrolls by pixels; holding the JavaFX thread until the load ran shows that the scroll itself, not a
        // later layout, asked for it.
        var loadedDuringScroll = emptyList<Pair<Int, Int>>()
        robot.interact {
            while ((flow.lastVisibleCell?.index ?: 0) < 460) flow.scrollPixels(200.0)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (loads.size < 2 && System.nanoTime() < deadline) Thread.sleep(10)
            loadedDuringScroll = loads.toList()
        }

        assertEquals(listOf(0 to 500, 500 to 500), loadedDuringScroll)
        eventually { assertEquals(1000, view.table.items.size) }
    }

    @Test
    fun `scrolling near the end loads the next page until the last`(robot: FxRobot) {
        openAndExpand(robot)
        assertEquals(listOf(0 to 500), loads)

        robot.interact { view.table.scrollTo(400) }
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(listOf(0 to 500), loads, "not near the end yet")

        robot.interact { view.table.scrollTo(499) }
        eventually { assertEquals(1000, view.table.items.size) }
        robot.interact { view.table.scrollTo(999) }
        eventually { assertEquals(1200, view.table.items.size) }
        robot.interact { view.table.scrollTo(1199) }
        WaitForAsyncUtils.waitForFxEvents()
        Thread.sleep(100)
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(listOf(0 to 500, 500 to 500, 1000 to 500), loads)
        assertEquals(
            "Subject 1200",
            view.table.items
                .last()
                .commit.subject,
        )
    }

    @Test
    fun `Refresh reloads the first page and keeps the selected commit selected`(robot: FxRobot) {
        openAndExpand(robot)
        robot.interact { view.table.selectionModel.select(2) }
        history = commits(1, "new") + commits(1200)

        click(robot, robot.lookup(".git-log-refresh").query())

        eventually {
            assertEquals(
                "new1",
                view.table.items
                    .first()
                    .commit.hash,
            )
        }
        assertEquals("h3", view.table.selectionModel.selectedItem.commit.hash)
        assertEquals(
            listOf(3),
            view.table.selectionModel.selectedIndices
                .toList(),
        )
        assertEquals(500, view.table.items.size)
    }

    @Test
    fun `Copy Revision Number copies the selected hashes in table order, also with Shortcut+C`(robot: FxRobot) {
        openAndExpand(robot)
        val copy =
            view.table.contextMenu.items
                .single()
        assertEquals("Copy Revision Number", copy.text)
        assertTrue(copy.isDisable, "nothing selected")

        robot.interact {
            view.table.selectionModel.select(4)
            view.table.selectionModel.select(1)
        }
        assertFalse(copy.isDisable)
        robot.interact { copy.fire() }
        assertEquals("h2\nh5", clipboard())

        robot.interact {
            Clipboard.getSystemClipboard().clear()
            view.table.selectionModel.clearAndSelect(7)
        }
        val mac = System.getProperty("os.name").startsWith("Mac")
        robot.interact {
            Event.fireEvent(view.table, KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.C, false, !mac, false, mac))
        }
        assertEquals("h8", clipboard())
    }

    @Test
    fun `says while loading, for an empty repository and when git fails`(robot: FxRobot) {
        gate = CountDownLatch(1)
        robot.interact {
            view.open(repo)
            view.expanded.set(true)
        }
        assertEquals("Loading…", placeholder(robot))
        gate?.countDown()
        gate = null
        eventually { assertEquals(500, view.table.items.size) }

        // A failing refresh keeps the rows and says so in the header.
        history = null
        robot.interact { view.refresh() }
        eventually {
            assertEquals(
                "The git log could not be read.",
                robot.lookup(".git-log-status").queryAs(Label::class.java).text,
            )
        }
        assertEquals(500, view.table.items.size)

        robot.interact { view.open(Path.of("/other")) }
        eventually { assertEquals("The git log could not be read.", placeholder(robot)) }
        assertEquals("", robot.lookup(".git-log-status").queryAs(Label::class.java).text)

        otherHistory = emptyList()
        robot.interact { view.refresh() }
        eventually { assertEquals("No commits yet.", placeholder(robot)) }
    }

    @Test
    fun `results for a folder opened before are dropped`(robot: FxRobot) {
        gate = CountDownLatch(1)
        robot.interact {
            view.open(repo)
            view.expanded.set(true)
        }
        otherHistory = commits(3, "o")
        robot.interact { view.open(Path.of("/other")) }
        gate?.countDown()
        gate = null

        eventually { assertEquals(listOf("o1", "o2", "o3"), view.table.items.map { it.commit.hash }) }
        Thread.sleep(100)
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(listOf("o1", "o2", "o3"), view.table.items.map { it.commit.hash })
    }

    @Test
    fun `draws the commit graph left of the subject, in the colours of the theme`(robot: FxRobot) {
        fun commit(
            hash: String,
            vararg parents: String,
        ) = GitCommit(hash, parents.toList(), "A", "a@example.com", Instant.EPOCH, "Subject $hash")
        history = listOf(commit("m", "b", "f"), commit("f", "a"), commit("b", "a"), commit("a"))
        openAndExpand(robot)

        val cells = robot.lookup(".git-graph-cell").queryAllAs(GraphCell::class.java).filter { it.item != null }
        val merge = cells.single { it.item.commit.hash == "m" }
        assertEquals(2, merge.item.graph.width)
        assertEquals(2 * LANE_WIDTH, merge.graph.width)
        assertEquals(merge.height, merge.graph.height, "the graph spans the row")
        assertEquals("Subject m", merge.text)
        assertEquals("Subject m", merge.subject.text)
        assertTrue(
            merge.subject.boundsInParent.minX >= merge.graph.boundsInParent.maxX,
            "the text starts after the graph",
        )
        assertTrue(
            merge
                .lookupAll(".text")
                .filterIsInstance<Text>()
                .filter { it.isVisible && it.text == "Subject m" }
                .size ==
                1,
            "the subject is shown once",
        )
        // The lane continues down, the merged one opens to the right; nothing above the first commit.
        val edges = merge.graph.childrenUnmodifiable.filterIsInstance<ShapePath>()
        assertEquals(2, edges.single { "git-graph-edge-0" in it.styleClass }.elements.size)
        assertEquals(2, edges.single { "git-graph-edge-1" in it.styleClass }.elements.size)

        // The fork row is only one lane wide below, but two above.
        val fork = cells.single { it.item.commit.hash == "a" }
        assertEquals(2 * LANE_WIDTH, fork.graph.width)

        fun laneColour(scheme: ColorScheme): Paint {
            robot.interact { view.scene.preferences.colorScheme = scheme }
            WaitForAsyncUtils.waitForFxEvents()
            return edges.single { "git-graph-edge-0" in it.styleClass }.stroke
        }
        val light = laneColour(ColorScheme.LIGHT)
        val dark = laneColour(ColorScheme.DARK)
        assertNotEquals(light, dark)
        assertEquals(light, laneColour(ColorScheme.LIGHT))
    }

    private fun commit(
        hash: String,
        vararg parents: String,
    ) = GitCommit(hash, parents.toList(), "A", "a@example.com", Instant.EPOCH, "Subject $hash")

    /** A line of the graph: from (x, y) to (x, y) in a colour index. */
    private data class Segment(
        val fromX: Double,
        val fromY: Double,
        val toX: Double,
        val toY: Double,
        val colour: Int,
    )

    /** The coloured lines [pane] draws (not its halo). */
    private fun drawn(pane: GraphPane): Set<Segment> =
        pane.childrenUnmodifiable
            .filterIsInstance<ShapePath>()
            .filter { "git-graph-edge" in it.styleClass }
            .flatMap { path ->
                val colour =
                    path.styleClass
                        .single { it.matches(Regex("git-graph-edge-\\d")) }
                        .last()
                        .digitToInt()
                path.elements.chunked(2).map { (move, line) ->
                    move as MoveTo
                    line as LineTo
                    Segment(move.x, move.y, line.x, line.y, colour)
                }
            }.toSet()

    /** The lines [row] should be drawn with at [height]: all but those with both ends beyond the lane cap. */
    private fun expected(
        row: GraphRow,
        height: Double,
    ): Set<Segment> {
        fun x(position: Int) = (position + 0.5) * LANE_WIDTH
        val upper = (0 until row.upperEdges).map { Triple(row.upperFrom(it), row.upperTo(it), row.upperColour(it)) }
        val lower = (0 until row.lowerEdges).map { Triple(row.lowerFrom(it), row.lowerTo(it), row.lowerColour(it)) }
        return (
            upper.map { (from, to, colour) -> Segment(x(from), 0.0, x(to), height / 2, colour) to (from to to) } +
                lower.map { (from, to, colour) -> Segment(x(from), height / 2, x(to), height, colour) to (from to to) }
        ).filter { (_, ends) -> ends.first < MAX_LANES || ends.second < MAX_LANES }
            .map { it.first }
            .toSet()
    }

    /** Asserts that [cell] shows exactly the graph of its row, or none when it is empty. */
    private fun assertDrawn(cell: GraphCell) {
        val item = cell.item
        if (item == null) {
            assertNull(cell.graphic, "empty cell ${cell.index}")
            assertNull(cell.content.parent, "empty cell ${cell.index}")
            return
        }
        val graph = item.graph
        assertSame<Node?>(cell.content, cell.graphic)
        assertEquals(minOf(graph.width, MAX_LANES) * LANE_WIDTH, cell.graph.width, "row ${cell.index}")
        assertEquals(expected(graph, cell.height), drawn(cell.graph), "row ${cell.index}")
        val dot =
            cell.graph.childrenUnmodifiable
                .filterIsInstance<Circle>()
                .single { "git-graph-dot" in it.styleClass }
        assertEquals((graph.dot + 0.5) * LANE_WIDTH, dot.centerX, "row ${cell.index}")
        assertEquals(cell.height / 2, dot.centerY, "row ${cell.index}")
        assertTrue("git-graph-dot-${graph.colour}" in dot.styleClass, "row ${cell.index}: ${dot.styleClass}")
        assertEquals(
            1,
            dot.styleClass.count { it.startsWith("git-graph-dot-") },
            "row ${cell.index}: ${dot.styleClass}",
        )
    }

    private fun graphCells(robot: FxRobot) =
        robot
            .lookup(".git-graph-cell")
            .queryAllAs(GraphCell::class.java)
            .filter { it.tableRow?.isVisible == true && it.index >= 0 }

    @Test
    fun `selected rows draw a halo in the table's background colour under the graph`(robot: FxRobot) {
        history = listOf(commit("m", "b", "f"), commit("f", "a"), commit("b", "a"), commit("a"))
        openAndExpand(robot)
        robot.interact { view.table.selectionModel.select(1) }
        WaitForAsyncUtils.waitForFxEvents()

        val cells = graphCells(robot).filter { it.item != null }.associateBy { it.index }
        val selected = cells.getValue(1)
        val halo = selected.graph.halo
        assertTrue(halo.isVisible)
        // Modena: -fx-box-border, -fx-control-inner-background
        assertEquals(
            view.table.background.fills
                .last()
                .fill,
            halo.stroke,
            "the table's background",
        )
        assertEquals(drawn(selected.graph).size * 2, halo.elements.size, "under every line")
        val haloDot = selected.graph.childrenUnmodifiable.single { "git-graph-halo-dot" in it.styleClass }
        assertTrue(haloDot.isVisible)
        for (index in listOf(0, 2, 3)) {
            assertFalse(
                cells
                    .getValue(index)
                    .graph.halo.isVisible,
                "row $index",
            )
            assertFalse(
                cells
                    .getValue(index)
                    .graph.childrenUnmodifiable
                    .single { "git-graph-halo-dot" in it.styleClass }
                    .isVisible,
                "row $index",
            )
        }
    }

    @Test
    fun `lines with one end beyond 30 lanes are drawn up to the edge, those with both ends are left out`(
        robot: FxRobot,
    ) {
        val layout = GraphLayout()
        repeat(36) { layout.next("t$it", listOf("p$it")) }
        val merge = layout.next("p3", listOf("q", "p35"))
        val root = layout.next("p0", emptyList())

        fun x(position: Int) = (position + 0.5) * LANE_WIDTH

        robot.interact {
            for (row in listOf(merge, root)) {
                val pane = GraphPane()
                pane.row = row
                pane.resize(pane.prefWidth(-1.0), 24.0)
                pane.draw()
                assertEquals(MAX_LANES * LANE_WIDTH, pane.width)
                assertEquals(expected(row, 24.0), drawn(pane))
                assertTrue(drawn(pane).none { it.fromX > x(MAX_LANES - 1) && it.toX > x(MAX_LANES - 1) })
            }
        }
        assertTrue(Segment(x(3), 12.0, x(35), 24.0, 35 % GRAPH_COLOURS) in expected(merge, 24.0), "the merge line")
        assertTrue(Segment(x(30), 12.0, x(29), 24.0, 30 % GRAPH_COLOURS) in expected(root, 24.0), "the shifting lane")
    }

    @Test
    fun `recycled cells draw the graph of their new row`(robot: FxRobot) {
        // Branches merged now and then, so that the rows' graphs differ.
        history =
            (1..1200).map { i ->
                val parents =
                    listOfNotNull(
                        if (i <
                            1200
                        ) {
                            "h${i + 1}"
                        } else {
                            null
                        },
                        if (i % 5 == 0 && i < 1190) "h${i + 3 + i % 4}" else null,
                    )
                commit("h$i", *parents.toTypedArray())
            }
        openAndExpand(robot)
        WaitForAsyncUtils.waitForFxEvents()

        val before = graphCells(robot).associateWith { it.item?.commit?.hash }
        robot.interact { for (cell in graphCells(robot)) assertDrawn(cell) }
        for (target in listOf(203, 37, 451)) {
            robot.interact { view.table.scrollTo(target) }
            WaitForAsyncUtils.waitForFxEvents()
            robot.interact { for (cell in graphCells(robot)) assertDrawn(cell) }
        }
        assertTrue(
            before.any { (cell, hash) ->
                cell.item != null && cell.item.commit.hash != hash
            },
            "cells were reused",
        )
    }

    @Test
    fun `cells emptied by a reload with fewer rows show no graph`(robot: FxRobot) {
        history = listOf(commit("m", "b", "f"), commit("f", "a"), commit("b", "a"), commit("a"))
        openAndExpand(robot)
        robot.interact { assertEquals(4, graphCells(robot).count { it.item != null }) }

        history = listOf(commit("x", "y"), commit("y"))
        robot.interact { view.refresh() }
        eventually { assertEquals(listOf("x", "y"), view.table.items.map { it.commit.hash }) }
        WaitForAsyncUtils.waitForFxEvents()

        robot.interact {
            val cells = graphCells(robot)
            assertEquals(2, cells.count { it.item != null })
            assertTrue(cells.any { it.index in 2..3 && it.item == null })
            for (cell in cells) assertDrawn(cell)
        }
    }

    private fun ref(
        name: String,
        kind: GitRefKind,
        commit: String,
    ) = GitRef(name, kind, commit)

    /** HEAD and main (with origin/main) on h1, a tag on h3, a remote-only branch on h40. */
    private val someRefs =
        GitRefs(
            listOf(
                ref("HEAD", GitRefKind.HEAD, "h1"),
                ref("refs/heads/main", GitRefKind.LOCAL_BRANCH, "h1"),
                ref("refs/remotes/origin/main", GitRefKind.REMOTE_BRANCH, "h1"),
                ref("refs/tags/v1.0", GitRefKind.TAG, "h1"),
                ref("refs/tags/v0.9", GitRefKind.TAG, "h3"),
                ref("refs/remotes/origin/old", GitRefKind.REMOTE_BRANCH, "h40"),
            ),
            "refs/heads/main",
        )

    private fun visibleIcons(cell: GraphCell) =
        cell.labels
            .lookupAll(".git-ref-icon")
            .filter { it.isVisible && it.isManaged }
            .map { icon -> icon.styleClass.single { it.startsWith("git-ref-") && it != "git-ref-icon" } }

    @Test
    fun `shows ref labels at the right end of the subject, none on rows without refs`(robot: FxRobot) {
        refs = someRefs
        openAndExpand(robot)
        hideDetails(robot)
        WaitForAsyncUtils.waitForFxEvents()

        robot.interact {
            val cells = graphCells(robot).filter { it.item != null }.associateBy { it.item.commit.hash }
            val first = cells.getValue("h1")
            assertTrue(first.labels.isVisible)
            assertEquals("origin & main +1", first.labels.text.text)
            assertEquals(listOf("git-ref-head", "git-ref-local", "git-ref-remote", "git-ref-tag"), visibleIcons(first))
            val labels = first.labels.boundsInParent
            assertTrue(labels.minX >= first.subject.boundsInParent.maxX, "right of the subject")
            assertEquals(first.content.width, labels.maxX, 1.0, "at the right end")
            assertTrue(labels.width <= first.content.width / 3 + 0.5, "at most a third")

            val tagged = cells.getValue("h3")
            assertEquals("v0.9", tagged.labels.text.text)
            assertEquals(listOf("git-ref-tag"), visibleIcons(tagged))

            val plain = cells.getValue("h2")
            assertFalse(plain.labels.isVisible)
            assertEquals("Subject 2", (plain.subject.lookup(".text") as Text).text, "not cut off")
            assertEquals("Subject 1", (first.subject.lookup(".text") as Text).text, "not cut off")
        }

        // A narrower column: the labels take a third of it, their text is ellipsized.
        robot.interact { view.scene.window.width = 560.0 }
        WaitForAsyncUtils.waitForFxEvents()
        try {
            robot.interact {
                val first = graphCells(robot).single { it.item?.commit?.hash == "h1" }
                assertTrue(first.subject.width >= SUBJECT_MIN_WIDTH - 0.5, "${first.subject.width}")
                assertEquals(first.content.width / 3, first.labels.boundsInParent.width, 1.0)
                val shown = (first.labels.text.lookup(".text") as Text).text
                assertTrue(shown.endsWith("...") && shown.length > 3 && shown != first.labels.text.text, shown)
            }
        } finally {
            // The stage is shared by the tests.
            robot.interact { view.scene.window.width = 800.0 }
        }
    }

    @Test
    fun `recycled cells replace their labels`(robot: FxRobot) {
        refs = someRefs
        openAndExpand(robot)
        WaitForAsyncUtils.waitForFxEvents()
        val before = graphCells(robot).associateWith { it.item?.commit?.hash }

        for (target in listOf(30, 0, 39)) {
            robot.interact { view.table.scrollTo(target) }
            WaitForAsyncUtils.waitForFxEvents()
            robot.interact {
                for (cell in graphCells(robot).filter { it.item != null }) {
                    val expected = someRefs.at(cell.item.commit.hash)
                    assertEquals(expected.isNotEmpty(), cell.labels.isVisible, "row ${cell.index}")
                    assertEquals(expected, cell.labels.refs, "row ${cell.index}")
                    val text = refLabelText(refGroups(expected, someRefs.currentBranch))
                    assertEquals(text, cell.labels.text.text, "row ${cell.index}")
                    assertEquals(expected.map { refStyleClass(it.kind) }.distinct(), visibleIcons(cell))
                }
            }
        }
        assertTrue(
            before.any { (cell, hash) ->
                cell.item != null && cell.item.commit.hash != hash
            },
            "cells were reused",
        )
    }

    @Test
    fun `selected rows outline the ref icons in the table's background colour`(robot: FxRobot) {
        refs = someRefs
        openAndExpand(robot)
        robot.interact { view.table.selectionModel.select(0) }
        WaitForAsyncUtils.waitForFxEvents()

        robot.interact {
            val cells = graphCells(robot).filter { it.item != null }.associateBy { it.item.commit.hash }
            // Modena: -fx-box-border, -fx-control-inner-background
            val background =
                view.table.background.fills
                    .last()
                    .fill
            val selected = cells.getValue("h1").labels.lookupAll(".git-ref-icon")
            assertEquals(4, selected.size)
            for (icon in selected) assertEquals(background, (icon.effect as DropShadow).color, "$icon")
            for (icon in cells.getValue("h3").labels.lookupAll(".git-ref-icon")) assertNull(icon.effect, "unselected")
        }
    }

    @Test
    fun `the tooltip of the labels lists every ref, and the colours follow the theme`(robot: FxRobot) {
        refs = someRefs
        openAndExpand(robot)
        WaitForAsyncUtils.waitForFxEvents()
        val first = graphCells(robot).single { it.item?.commit?.hash == "h1" }

        robot.interact {
            first.labels.tooltip.show(view.scene.window)
        }
        WaitForAsyncUtils.waitForFxEvents()
        robot.interact {
            val lines = (first.labels.tooltip.graphic as javafx.scene.Parent).childrenUnmodifiable.map { it as Label }
            assertEquals(listOf("HEAD", "main", "origin/main", "v1.0"), lines.map { it.text })
            assertEquals(
                listOf("git-ref-head", "git-ref-local", "git-ref-remote", "git-ref-tag"),
                lines.map { line ->
                    line.graphic.styleClass.single { it.startsWith("git-ref-") && it != "git-ref-icon" }
                },
            )
            first.labels.tooltip.hide()
        }

        fun headColour(scheme: ColorScheme): Paint {
            robot.interact { view.scene.preferences.colorScheme = scheme }
            WaitForAsyncUtils.waitForFxEvents()
            return (first.labels.lookup(".git-ref-head") as org.kordamp.ikonli.javafx.FontIcon).iconColor
        }
        val light = headColour(ColorScheme.LIGHT)
        val dark = headColour(ColorScheme.DARK)
        assertNotEquals(light, dark)
        assertEquals(
            javafx.scene.paint.Color
                .web("#d9a000"),
            light,
        )
        assertEquals(light, headColour(ColorScheme.LIGHT))
    }

    @Test
    fun `rows still show without labels when the refs cannot be read`(robot: FxRobot) {
        refs = null
        openAndExpand(robot)
        WaitForAsyncUtils.waitForFxEvents()

        robot.interact {
            val cells = graphCells(robot).filter { it.item != null }
            assertTrue(cells.isNotEmpty())
            assertTrue(cells.none { it.labels.isVisible })
        }
    }

    /** The details of a commit of [commits]: committed by its author when it was written, with a long body. */
    private fun defaultDetails(hash: String): GitCommitDetails? {
        val commit = history?.firstOrNull { it.hash == hash } ?: return null
        val body = (1..40).joinToString(" ") { "Body of ${hash.removePrefix("h")}." }
        val message = "${commit.subject}\n\n$body\n"
        return GitCommitDetails(commit, commit.author, commit.authorEmail, commit.authorTime, message)
    }

    private fun at(time: Instant): String {
        val zone = java.time.ZoneId.systemDefault()
        val date =
            java.time.format.DateTimeFormatter
                .ofLocalizedDate(java.time.format.FormatStyle.SHORT)
        val clock =
            java.time.format.DateTimeFormatter
                .ofLocalizedTime(java.time.format.FormatStyle.SHORT)
        return "${date.withZone(zone).format(time)} at ${clock.withZone(zone).format(time)}"
    }

    private fun select(
        robot: FxRobot,
        vararg indices: Int,
    ) {
        robot.interact {
            view.table.selectionModel.clearSelection()
            indices.forEach(view.table.selectionModel::select)
        }
    }

    private val details get() = view.detailsPane

    private fun showsDetailsOf(subject: String) =
        details.scroll.isVisible && !details.placeholder.isVisible && details.subject.text == subject

    private fun placeholderShown() =
        if (details.placeholder.isVisible &&
            !details.scroll.isVisible
        ) {
            details.placeholder.text
        } else {
            null
        }

    @Test
    fun `shows the details of the selected commit right of the table, the committer only if it differs`(
        robot: FxRobot,
    ) {
        refs = someRefs
        detailsOf = { hash ->
            val details = checkNotNull(defaultDetails(hash))
            when (hash) {
                "h2" -> {
                    details.copy(
                        committer = "Other",
                        committerEmail = "other@example.com",
                        committerTime = Instant.ofEpochSecond(9000),
                    )
                }

                "h3" -> {
                    details.copy(committerTime = Instant.ofEpochSecond(9000))
                }

                // Amended within the same minute.
                "h4" -> {
                    details.copy(committerTime = details.commit.authorTime.plusSeconds(30))
                }

                "h5" -> {
                    details.copy(message = "")
                }

                else -> {
                    details
                }
            }
        }
        openAndExpand(robot)
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(view.inner.width * 0.3, details.width, 10.0, "the right 30 %")
        assertEquals("No commit selected", placeholderShown())

        select(robot, 0)
        eventually { assertTrue(showsDetailsOf("Subject 1")) }
        robot.interact {
            assertTrue(details.body.isVisible)
            assertTrue(details.body.text.startsWith("Body of 1. Body of 1."), details.body.text)
            assertEquals("h1 Author 1\na1@example.com\non ${at(Instant.ofEpochSecond(60))}", details.author.text)
            assertFalse(details.committed.isVisible)
            assertEquals(
                listOf("HEAD", "main", "origin/main", "v1.0"),
                details.refs.children.map { (it as Label).text },
            )
            assertTrue(
                "bold" in
                    details.subject.font.style
                        .lowercase(),
                details.subject.font.style,
            )
            // Wrapped and as high as its text: the body takes several lines, without a scroll bar of its own.
            assertEquals(emptyList(), details.body.lookupAll(".scroll-bar").filter { it.isVisible })
            val text =
                details.body
                    .lookupAll(".text")
                    .filterIsInstance<Text>()
                    .single()
            assertEquals(details.body.width, text.wrappingWidth, 1.0, "wrapped at the full width")
            assertTrue(
                text.layoutBounds.height <= details.body.height,
                "${text.layoutBounds} in ${details.body.height}",
            )
            assertTrue(details.body.height > details.subject.height * 3, "${details.body.height}")
        }

        select(robot, 1)
        eventually { assertTrue(showsDetailsOf("Subject 2")) }
        robot.interact {
            assertTrue(details.committed.isVisible)
            assertEquals("committed on ${at(Instant.ofEpochSecond(9000))} by Other", details.committed.text)
            assertFalse(details.refs.isVisible, "no refs")
        }

        select(robot, 2)
        eventually { assertTrue(showsDetailsOf("Subject 3")) }
        robot.interact {
            assertEquals("committed on ${at(Instant.ofEpochSecond(9000))}", details.committed.text)
            assertEquals(listOf("v0.9"), details.refs.children.map { (it as Label).text })
        }

        select(robot, 3)
        eventually { assertTrue(showsDetailsOf("Subject 4")) }
        robot.interact { assertFalse(details.committed.isVisible, "the same minute") }

        select(robot, 4)
        eventually { assertTrue(details.author.text.startsWith("h5 Author 5")) }
        robot.interact {
            assertFalse(details.subject.isVisible, "no empty bold line")
            assertFalse(details.subject.isManaged)
            assertFalse(details.body.isVisible)
            assertTrue(details.scroll.isVisible)
        }
    }

    @Test
    fun `keeps the details shown while the next ones load, then says Loading`(robot: FxRobot) {
        openAndExpand(robot)
        select(robot, 0)
        eventually { assertTrue(showsDetailsOf("Subject 1")) }

        detailGates["h2"] = CountDownLatch(1)
        select(robot, 1)
        robot.interact { assertTrue(showsDetailsOf("Subject 1"), "kept while the next ones load") }
        eventually { assertEquals("Loading…", placeholderShown()) }

        detailGates.getValue("h2").countDown()
        eventually { assertTrue(showsDetailsOf("Subject 2")) }
        robot.interact {
            assertEquals(
                javafx.animation.Animation.Status.STOPPED,
                details.loading.status,
                "Loading… does not come back",
            )
        }
    }

    @Test
    fun `the details pane keeps its share of the width when the log is resized`(robot: FxRobot) {
        refs = someRefs
        openAndExpand(robot)
        select(robot, 0)
        eventually { assertTrue(showsDetailsOf("Subject 1")) }

        fun share() = details.width / view.inner.width

        fun resize(width: Double) {
            robot.interact { view.scene.window.width = width }
            WaitForAsyncUtils.waitForFxEvents()
        }
        try {
            assertEquals(0.3, share(), 0.02)
            resize(500.0)
            assertEquals(0.3, share(), 0.02, "${details.width} of ${view.inner.width}")
            resize(800.0)
            assertEquals(0.3, share(), 0.02, "${details.width} of ${view.inner.width}")

            // Dragged by the user: that share is kept.
            robot.interact { view.inner.setDividerPositions(0.5) }
            WaitForAsyncUtils.waitForFxEvents()
            resize(560.0)
            assertEquals(0.5, share(), 0.02, "${details.width} of ${view.inner.width}")
            resize(800.0)
            assertEquals(0.5, share(), 0.02, "${details.width} of ${view.inner.width}")
        } finally {
            // The stage is shared by the tests.
            resize(800.0)
        }
    }

    @Test
    fun `says how many commits are selected, and when none is`(robot: FxRobot) {
        openAndExpand(robot)
        select(robot, 0, 3, 5)
        eventually { assertEquals("3 commits selected", placeholderShown()) }
        select(robot)
        eventually { assertEquals("No commit selected", placeholderShown()) }
        assertEquals(emptyList(), detailLoads)
    }

    @Test
    fun `drops the details of a commit that is no longer selected`(robot: FxRobot) {
        detailGates["h1"] = CountDownLatch(1)
        detailGates["h2"] = CountDownLatch(1)
        openAndExpand(robot)
        select(robot, 0)
        eventually { assertEquals("Loading…", placeholderShown()) }
        select(robot, 1)
        WaitForAsyncUtils.waitForFxEvents()

        detailGates.getValue("h1").countDown()
        eventually { assertEquals(listOf("h1"), detailLoads.toList()) }
        Thread.sleep(100)
        WaitForAsyncUtils.waitForFxEvents()
        robot.interact { assertEquals("Loading…", placeholderShown(), "h1's details are not shown") }

        detailGates.getValue("h2").countDown()
        eventually { assertTrue(showsDetailsOf("Subject 2")) }
    }

    @Test
    fun `says when the details cannot be read`(robot: FxRobot) {
        detailsOf = { null }
        openAndExpand(robot)
        select(robot, 4)
        eventually { assertEquals("The commit details could not be read.", placeholderShown()) }
    }

    @Test
    fun `a refresh that keeps the commit selected reads its details and refs again`(robot: FxRobot) {
        openAndExpand(robot)
        select(robot, 2)
        eventually { assertTrue(showsDetailsOf("Subject 3")) }
        refs = GitRefs(listOf(ref("refs/tags/new", GitRefKind.TAG, "h3")), null)

        robot.interact { view.refresh() }
        eventually {
            assertEquals(listOf("h3", "h3"), detailLoads.toList())
            assertEquals(listOf("new"), details.refs.children.map { (it as Label).text })
            assertTrue(showsDetailsOf("Subject 3"))
        }
    }

    @Test
    fun `the table keeps the focus while the selection changes`(robot: FxRobot) {
        openAndExpand(robot)
        assertSame<Node?>(view.table, view.scene.focusOwner)
        for (index in listOf(0, 1, 2, 7)) {
            select(robot, index)
            eventually { assertTrue(showsDetailsOf("Subject ${index + 1}")) }
            assertSame<Node?>(view.table, view.scene.focusOwner, "after selecting row $index")
        }
        select(robot, 1, 2)
        eventually { assertEquals("2 commits selected", placeholderShown()) }
        assertSame<Node?>(view.table, view.scene.focusOwner)
    }

    @Test
    fun `the message can be selected and copied`(robot: FxRobot) {
        openAndExpand(robot)
        select(robot, 0)
        eventually { assertTrue(showsDetailsOf("Subject 1")) }
        val subject = details.subject
        assertFalse(subject.isEditable)
        assertFalse(subject.isFocusTraversable, "not reached with Tab")

        // A click gives it the focus, so that Shortcut+C copies from it instead of the table.
        click(robot, subject)
        assertSame<Node?>(subject, view.scene.focusOwner)
        robot.interact {
            Clipboard.getSystemClipboard().clear()
            subject.selectRange(0, 7)
        }
        val mac = System.getProperty("os.name").startsWith("Mac")
        robot.interact {
            Event.fireEvent(subject, KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.C, false, !mac, false, mac))
        }
        assertEquals("Subject", clipboard())
        assertEquals("Subject 1", subject.text, "read-only")
    }

    private val someFiles =
        listOf(
            GitChangedFile(GitChange.MODIFIED, "src/main/kotlin/dev/One.kt"),
            GitChangedFile(GitChange.ADDED, "src/main/kotlin/dev/Two.kt"),
            GitChangedFile(GitChange.DELETED, "README.md"),
            GitChangedFile(GitChange.RENAMED, "docs/new.md", "docs/old.md"),
        )

    private val changes get() = view.changesPane

    /** The expanded tree as indented names, folders ending with `/`. */
    private fun shownTree(): List<String> {
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

    private fun filesPlaceholder() =
        if (changes.placeholder.isVisible && !changes.tree.isVisible) changes.placeholder.text else null

    /** The shown cells of the tree, top to bottom. */
    private fun treeCells(robot: FxRobot): List<TreeCell<*>> =
        robot
            .lookup(".git-changes-tree .tree-cell")
            .queryAllAs(TreeCell::class.java)
            .filter { !it.isEmpty && it.item != null }
            .sortedBy { it.index }

    /** The cell of the file or folder named [name]. */
    private fun treeCell(
        robot: FxRobot,
        name: String,
    ): TreeCell<*> = treeCells(robot).single { (it.item as ChangeNode).name == name }

    /** Selects the file or folder named [name] in the tree. */
    private fun selectFile(
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

    private fun pressInTree(
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

    private fun status(robot: FxRobot) = robot.lookup(".git-log-status").queryAs(Label::class.java).text

    private fun showFilesOfFirstCommit(robot: FxRobot) {
        openAndExpand(robot)
        select(robot, 0)
        eventually { assertTrue(shownTree().isNotEmpty()) }
    }

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
    fun `a stale changed-files result is dropped`(robot: FxRobot) {
        fileGates["h1"] = CountDownLatch(1)
        openAndExpand(robot)
        filesOf = { hash -> listOf(GitChangedFile(GitChange.MODIFIED, "$hash.txt")) }
        select(robot, 0)
        eventually { assertEquals("Loading…", filesPlaceholder()) }
        select(robot, 1)
        WaitForAsyncUtils.waitForFxEvents()

        fileGates.getValue("h1").countDown()
        eventually { assertEquals(listOf("h2.txt"), shownTree()) }
        assertEquals(listOf("h1", "h2"), fileLoads.map { it.first })
    }

    @Test
    fun `a refresh that keeps the commit selected reads its files again and keeps the selected file`(robot: FxRobot) {
        showFilesOfFirstCommit(robot)
        selectFile(robot, "Two.kt")
        filesOf = { someFiles + GitChangedFile(GitChange.ADDED, "added.txt") }

        robot.interact { view.refresh() }

        eventually {
            assertEquals(2, fileLoads.size)
            assertEquals("5 files", changes.header.text)
        }
        robot.interact { assertEquals("src/main/kotlin/dev/Two.kt", changes.selectedFile?.path) }
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
        Thread.sleep(100)
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
        Thread.sleep(100)
        WaitForAsyncUtils.waitForFxEvents()
        robot.interact { assertEquals("", status(robot)) }

        diffGate = CountDownLatch(1)
        pressInTree(robot, KeyCode.ENTER)
        select(robot, 1)
        checkNotNull(diffGate).countDown()
        eventually { assertEquals(2, diffLoads.size) }
        Thread.sleep(100)
        WaitForAsyncUtils.waitForFxEvents()
        robot.interact { assertEquals("", status(robot), "another commit selected") }
    }

    @Test
    fun `the details show while the changed files are still read`(robot: FxRobot) {
        fileGates["h1"] = CountDownLatch(1)
        openAndExpand(robot)
        select(robot, 0)

        eventually { assertTrue(showsDetailsOf("Subject 1")) }
        robot.interact { assertEquals("Loading…", filesPlaceholder()) }
        fileGates.getValue("h1").countDown()
        eventually { assertTrue(shownTree().isNotEmpty()) }
    }

    @Test
    fun `a refresh of the same commit keeps collapsed folders collapsed`(robot: FxRobot) {
        showFilesOfFirstCommit(robot)
        selectFile(robot, "docs")
        pressInTree(robot, KeyCode.ENTER)
        robot.interact {
            assertEquals(listOf("docs/", "src/main/kotlin/dev/", "  One.kt", "  Two.kt", "README.md"), shownTree())
        }

        robot.interact { view.refresh() }
        eventually { assertEquals(2, fileLoads.size) }
        WaitForAsyncUtils.waitForFxEvents()
        robot.interact {
            assertEquals(listOf("docs/", "src/main/kotlin/dev/", "  One.kt", "  Two.kt", "README.md"), shownTree())
        }

        // Another commit: everything expanded.
        select(robot, 1)
        eventually { assertEquals(6, shownTree().size) }
    }

    private val bar get() = view.filterBar

    /** Fires a key press at [node], with the shortcut key (Ctrl, Cmd on macOS) if [shortcut]. */
    private fun press(
        robot: FxRobot,
        node: Node,
        code: KeyCode,
        shortcut: Boolean = false,
    ) {
        val mac = System.getProperty("os.name").startsWith("Mac")
        robot.interact {
            Event.fireEvent(
                node,
                KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, shortcut && !mac, false, shortcut && mac),
            )
        }
        WaitForAsyncUtils.waitForFxEvents()
    }

    /** The texts of the items a filter popup lists, in order. */
    private fun items(popup: FilterPopup) = popup.list.items.map { it.text }

    /** Clicks the list cell of [popup] showing [text]. */
    private fun clickItem(
        robot: FxRobot,
        popup: FilterPopup,
        text: String,
        clickCount: Int = 1,
    ) {
        WaitForAsyncUtils.waitForFxEvents()
        val cell =
            popup.list
                .lookupAll(".list-cell")
                .filterIsInstance<ListCell<*>>()
                .single { (it.item as? FilterItem)?.text == text }
        // A double click: a first click, then one with a click count of 2.
        for (count in 1..clickCount) click(robot, cell, count)
    }

    @Test
    fun `typing filters the commits after a pause, Enter at once, Escape clears, Refresh keeps it`(robot: FxRobot) {
        openAndExpand(robot)
        val prompt = bar.text.promptText

        val (status, loadsThen) =
            WaitForAsyncUtils
                .asyncFx<Pair<Animation.Status, Int>> {
                    bar.text.text = "Subject 12"
                    bar.debounce.status to filters.size
                }.get()
        assertEquals(Animation.Status.RUNNING, status, "waiting for the pause")
        assertEquals(1, loadsThen, "no reload yet")
        eventually { assertEquals(Animation.Status.STOPPED, bar.debounce.status) }
        eventually { assertEquals(GitLogFilter(text = "Subject 12"), filters.last()) }
        eventually { assertEquals(listOf("Subject 12", "Subject 120", "Subject 121"), shownSubjects(robot).take(3)) }
        assertEquals("Text or hash", prompt)

        robot.interact { bar.text.text = "  Subject 7 " }
        press(robot, bar.text, KeyCode.ENTER)
        eventually { assertEquals(GitLogFilter(text = "Subject 7"), filters.last()) }
        Thread.sleep(500)
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(1, filters.count { it.text == "Subject 7" }, "Enter applied it, the pause adds nothing")

        robot.interact { view.refresh() }
        eventually { assertEquals(2, filters.count { it.text == "Subject 7" }) }

        var escapes = 0
        robot.interact { view.addEventHandler(KeyEvent.KEY_PRESSED) { if (it.code == KeyCode.ESCAPE) escapes++ } }
        press(robot, bar.text, KeyCode.ESCAPE)
        assertEquals("", bar.text.text)
        eventually { assertEquals(GitLogFilter(), filters.last()) }
        assertEquals(0, escapes, "consumed while there is a text")
        press(robot, bar.text, KeyCode.ESCAPE)
        assertEquals(1, escapes, "an empty field lets it through")
        eventually { assertEquals(500, view.table.items.size) }
    }

    @Test
    fun `Regex and Match Case reload at once with a text, and an empty result says no commits match`(robot: FxRobot) {
        openAndExpand(robot)
        assertEquals("Regex", bar.regex.tooltip.text)
        assertEquals("Match Case", bar.matchCase.tooltip.text)

        robot.interact { bar.regex.isSelected = true }
        Thread.sleep(400)
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(1, filters.size, "nothing to match without a text")

        robot.interact { bar.text.text = "subject 5" }
        press(robot, bar.text, KeyCode.ENTER)
        eventually { assertEquals(GitLogFilter(text = "subject 5", regex = true), filters.last()) }
        eventually { assertEquals("Subject 5", shownSubjects(robot).first()) }

        robot.interact { bar.matchCase.isSelected = true }
        eventually { assertEquals(GitLogFilter(text = "subject 5", regex = true, matchCase = true), filters.last()) }
        eventually { assertEquals("No commits match the filters.", placeholder(robot)) }
    }

    @Test
    fun `rows have no graph under a text filter, but keep their ref labels`(robot: FxRobot) {
        history = listOf(commit("m", "b", "f"), commit("f", "a"), commit("b", "a"), commit("a"))
        refs = GitRefs(listOf(ref("refs/heads/main", GitRefKind.LOCAL_BRANCH, "m")), null)
        openAndExpand(robot)
        eventually { assertTrue(graphCells(robot).any { it.item?.graph?.width == 2 }) }

        robot.interact { bar.text.text = "Subject" }
        press(robot, bar.text, KeyCode.ENTER)

        eventually {
            val cells = graphCells(robot).filter { it.item != null }
            assertEquals(4, cells.size)
            assertTrue(cells.all { it.item.graph == GraphRow.NONE })
            assertTrue(cells.all { it.graph.width == 0.0 && drawn(it.graph).isEmpty() })
            val merge = cells.single { it.item.commit.hash == "m" }
            assertTrue(merge.labels.isVisible)
            assertTrue(merge.subject.boundsInParent.minX < LANE_WIDTH, "the subject starts at the left")
        }
        val dots =
            graphCells(robot).filter { it.item != null }.flatMap { cell ->
                cell.graph.childrenUnmodifiable
                    .filterIsInstance<Circle>()
                    .filter { "git-graph-dot" in it.styleClass }
            }
        assertTrue(dots.none { it.isVisible })
    }

    @Test
    fun `the branch popup lists the refs, checking one filters the log, the button tells and clears`(robot: FxRobot) {
        refs =
            GitRefs(
                someRefs.refs + ref("refs/heads/feature", GitRefKind.LOCAL_BRANCH, "h5"),
                "refs/heads/main",
            )
        reachable = mapOf("refs/heads/feature" to setOf("h5", "h6", "h7"), "HEAD" to setOf("h1", "h2"))
        openAndExpand(robot)
        val branch = bar.branch
        assertEquals("Branch", branch.label.text)
        assertFalse(branch.clear.isVisible || branch.clear.isManaged)

        robot.interact { branch.button.fire() }
        val popup = branch.popup
        assertTrue(popup.popup.isShowing)
        assertSame<Node?>(popup.search, popup.popup.scene.focusOwner)
        assertEquals(
            listOf("HEAD", "feature", "main", "origin/main", "origin/old", "v0.9", "v1.0"),
            items(popup),
        )
        assertTrue(GIT_LOG_CSS in popup.content.stylesheets)

        clickItem(robot, popup, "feature")
        eventually { assertEquals(listOf("refs/heads/feature"), filters.last().revisions) }
        eventually { assertEquals(listOf("h5", "h6", "h7"), view.table.items.map { it.commit.hash }) }
        assertEquals("Branch: feature", branch.label.text)
        assertTrue(branch.clear.isVisible && branch.clear.isManaged)
        assertTrue(popup.popup.isShowing, "stays open")

        robot.interact { popup.list.selectionModel.select(0) }
        press(robot, popup.list, KeyCode.SPACE)
        eventually { assertEquals(listOf("HEAD", "refs/heads/feature"), filters.last().revisions) }
        assertEquals("Branch: HEAD +1", branch.label.text)

        // Typing narrows the list; Enter toggles the one item shown.
        robot.interact { popup.search.text = "FEAT" }
        assertEquals(listOf("feature"), items(popup))
        press(robot, popup.search, KeyCode.ENTER)
        eventually { assertEquals(listOf("HEAD"), filters.last().revisions) }
        assertEquals("Branch: HEAD", branch.label.text)

        // Several shown, one named exactly: Enter toggles it, both ways.
        robot.interact { popup.search.text = "main" }
        assertEquals(listOf("main", "origin/main"), items(popup))
        press(robot, popup.search, KeyCode.ENTER)
        eventually { assertEquals(listOf("HEAD", "refs/heads/main"), filters.last().revisions) }
        press(robot, popup.search, KeyCode.ENTER)
        eventually { assertEquals(listOf("HEAD"), filters.last().revisions) }

        // A double click toggles once.
        robot.interact { popup.search.text = "" }
        clickItem(robot, popup, "v0.9", clickCount = 2)
        eventually { assertEquals(listOf("HEAD", "refs/tags/v0.9"), filters.last().revisions) }
        clickItem(robot, popup, "v0.9")
        eventually { assertEquals(listOf("HEAD"), filters.last().revisions) }

        press(robot, popup.search, KeyCode.ESCAPE)
        assertFalse(popup.popup.isShowing)
        robot.interact { branch.button.fire() }
        assertEquals("HEAD", items(popup).first(), "checked first")
        assertEquals("", popup.search.text)
        robot.interact { popup.hide() }

        click(robot, branch.clear)
        eventually { assertEquals(GitLogFilter(), filters.last()) }
        assertEquals("Branch", branch.label.text)
        assertFalse(branch.clear.isVisible || branch.clear.isManaged)
    }

    @Test
    fun `Down in a popup's search field moves into its list`(robot: FxRobot) {
        refs = someRefs
        openAndExpand(robot)
        robot.interact { bar.branch.button.fire() }
        val popup = bar.branch.popup

        press(robot, popup.search, KeyCode.DOWN)

        assertSame<Node?>(popup.list, popup.popup.scene.focusOwner)
        assertEquals(0, popup.list.selectionModel.selectedIndex)
        robot.interact { popup.hide() }
    }

    @Test
    fun `the user popup lists me and the loaded authors, and takes a typed name with Enter`(robot: FxRobot) {
        userName = "Me Myself"
        history = commits(3) + commits(1, "x").map { it.copy(author = "alice") }
        openAndExpand(robot)
        val user = bar.user

        robot.interact { user.button.fire() }
        val popup = user.popup
        eventually { assertEquals(listOf("me (Me Myself)", "alice", "Author 1", "Author 2", "Author 3"), items(popup)) }
        assertEquals(listOf(repo), userNameLoads)

        clickItem(robot, popup, "me (Me Myself)")
        eventually { assertEquals(listOf("Me Myself"), filters.last().users) }
        assertEquals("User: me", user.label.text)
        eventually { assertEquals("No commits match the filters.", placeholder(robot)) }

        robot.interact { popup.search.text = "Author 2" }
        press(robot, popup.search, KeyCode.ENTER)
        eventually { assertEquals(listOf("Me Myself", "Author 2"), filters.last().users) }

        robot.interact { popup.search.text = " Somebody Else " }
        assertEquals(emptyList(), items(popup))
        press(robot, popup.search, KeyCode.ENTER)
        eventually { assertEquals(listOf("Me Myself", "Author 2", "Somebody Else"), filters.last().users) }
        assertEquals("User: me +2", user.label.text)
        assertEquals("", popup.search.text)
        assertEquals("Somebody Else", items(popup)[2], "checked first")
        eventually { assertEquals(listOf("Subject 2"), shownSubjects(robot)) }

        robot.interact { popup.hide() }
        robot.interact { user.button.fire() }
        assertTrue("alice" in items(popup), "the user filter hides authors, the list keeps them")
        assertEquals(1, userNameLoads.size, "read once per folder")
        robot.interact { popup.hide() }
    }

    @Test
    fun `the path popup lists HEAD's tree and takes a typed path`(robot: FxRobot) {
        treePaths = listOf(GitTreePath("src", folder = true), GitTreePath("src/a.txt", folder = false))
        openAndExpand(robot)
        val path = bar.path

        robot.interact { path.button.fire() }
        val popup = path.popup
        eventually { assertEquals(listOf("src", "src/a.txt"), items(popup)) }
        assertEquals(listOf(repo), pathLoads)
        assertTrue(popup.list.items.all { it.icon != null })

        robot.interact { popup.search.text = "./docs/" }
        press(robot, popup.search, KeyCode.ENTER)
        eventually { assertEquals(listOf("docs"), filters.last().paths) }
        assertEquals("Path: docs", path.label.text)

        robot.interact { popup.search.text = "src" }
        assertEquals(listOf("src", "src/a.txt"), items(popup))
        press(robot, popup.search, KeyCode.ENTER)
        eventually { assertEquals(listOf("src", "docs"), filters.last().paths) }
        assertEquals("Path: src +1", path.label.text)

        robot.interact { popup.hide() }
        robot.interact { path.button.fire() }
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(1, pathLoads.size, "not read again for a filter change")
        robot.interact { popup.hide() }
    }

    @Test
    fun `the graph options set the order, first parent only and no merges`(robot: FxRobot) {
        openAndExpand(robot)
        assertEquals("Graph Options", bar.options.tooltip.text)
        assertEquals(
            listOf("Sort by Date", "Sort Topologically", null, "First Parent Only", "No Merges"),
            bar.options.items.map { it.text },
        )
        assertTrue(bar.dateOrder.isSelected)

        // As the menu does when an item is clicked.
        robot.interact { bar.topoOrder.isSelected = true }
        eventually { assertEquals(GitLogFilter(order = GitLogOrder.TOPOLOGICAL), filters.last()) }
        assertFalse(bar.dateOrder.isSelected)

        robot.interact { bar.firstParent.isSelected = true }
        eventually { assertTrue(filters.last().firstParent) }
        robot.interact { bar.noMerges.isSelected = true }
        eventually { assertTrue(filters.last().noMerges && filters.last().firstParent) }
        eventually { assertTrue(view.table.items.all { it.graph == GraphRow.NONE }) }

        assertTrue(bar.options.pseudoClassStates.any { it.pseudoClassName == "on" })
        assertEquals("Graph Options: Sort Topologically, First Parent Only, No Merges", bar.options.tooltip.text)
        // The :on style applies with the next pulse.
        WaitForAsyncUtils.waitForFxEvents()
        val marked = (
            bar.options.background.fills
                .last()
                .fill
        )

        robot.interact {
            bar.dateOrder.isSelected = true
            bar.firstParent.isSelected = false
        }
        eventually { assertEquals(GitLogOrder.DATE, filters.last().order) }
        assertEquals("Graph Options: No Merges", bar.options.tooltip.text)
        robot.interact { bar.noMerges.isSelected = false }
        eventually { assertEquals(GitLogFilter(), filters.last()) }
        assertFalse(bar.options.pseudoClassStates.any { it.pseudoClassName == "on" })
        assertEquals("Graph Options", bar.options.tooltip.text)
        WaitForAsyncUtils.waitForFxEvents()
        assertNotEquals(
            marked,
            bar.options.background
                ?.fills
                ?.lastOrNull()
                ?.fill,
            "the marker shows",
        )
    }

    @Test
    fun `Shortcut+F in the log body focuses the text field, Down there goes to the table`(robot: FxRobot) {
        openAndExpand(robot)
        var accelerated = 0
        // Key presses that reach the scene; consumed ones do not.
        val reached = mutableListOf<KeyCode>()
        robot.interact {
            view.scene.addEventHandler(KeyEvent.KEY_PRESSED) { reached += it.code }
            view.scene.accelerators[KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN)] =
                Runnable { accelerated++ }
            bar.text.text = "Subject 9"
        }

        for (node in listOf(view.table, changes.tree, details)) {
            robot.interact { node.requestFocus() }
            press(robot, node, KeyCode.F, shortcut = true)
            assertSame<Node?>(bar.text, view.scene.focusOwner)
            assertEquals("Subject 9", bar.text.selectedText)

            press(robot, bar.text, KeyCode.DOWN)
            assertSame<Node?>(view.table, view.scene.focusOwner)
        }
        assertEquals(emptyList(), reached, "both consumed")
        assertEquals(0, accelerated, "no accelerator of the window gets it")
    }

    @Test
    fun `opening another folder resets the filters`(robot: FxRobot) {
        refs = someRefs
        treePaths = listOf(GitTreePath("src", folder = true))
        openAndExpand(robot)
        robot.interact {
            bar.text.text = "Subject 1"
            bar.regex.isSelected = true
            bar.matchCase.isSelected = true
            bar.topoOrder.isSelected = true
            bar.firstParent.isSelected = true
            bar.noMerges.isSelected = true
            bar.branch.button.fire()
        }
        clickItem(robot, bar.branch.popup, "main")
        robot.interact { bar.path.button.fire() }
        eventually { assertEquals(listOf("src"), items(bar.path.popup)) }
        clickItem(robot, bar.path.popup, "src")
        eventually { assertEquals(listOf("src"), filters.last().paths) }
        assertTrue(bar.path.popup.popup.isShowing)

        otherHistory = commits(3, "o")
        robot.interact { view.open(Path.of("/other")) }

        assertFalse(bar.path.popup.popup.isShowing)
        assertEquals(GitLogFilter(), bar.filter)
        assertEquals("", bar.text.text)
        assertFalse(bar.regex.isSelected || bar.matchCase.isSelected)
        assertTrue(bar.dateOrder.isSelected)
        assertFalse(bar.firstParent.isSelected || bar.noMerges.isSelected)
        assertEquals(listOf("Branch", "User", "Path"), listOf(bar.branch, bar.user, bar.path).map { it.label.text })
        eventually { assertEquals(listOf("o1", "o2", "o3"), view.table.items.map { it.commit.hash }) }
        assertEquals(GitLogFilter(), filters.last())
        Thread.sleep(400)
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(GitLogFilter(), filters.last(), "no late text from before")
    }

    @Test
    fun `the filter controls are reached with Tab and open their popup or menu with the keyboard`(robot: FxRobot) {
        refs = someRefs
        openAndExpand(robot)
        robot.interact { bar.focusText() }

        fun tab() = press(robot, checkNotNull(view.scene.focusOwner), KeyCode.TAB)

        val order = mutableListOf<Node?>(view.scene.focusOwner)
        repeat(6) {
            tab()
            order += view.scene.focusOwner
        }
        val expected =
            listOf<Node>(bar.text, bar.regex, bar.matchCase, bar.branch.button, bar.user.button, bar.path.button)
        assertEquals<List<Node?>>(expected + bar.options, order)

        // Space on Branch opens its popup; checking a branch shows its clear button, which comes next.
        robot.interact { bar.branch.button.requestFocus() }
        press(robot, bar.branch.button, KeyCode.SPACE)
        robot.interact {
            val release = KeyEvent(KeyEvent.KEY_RELEASED, "", "", KeyCode.SPACE, false, false, false, false)
            Event.fireEvent(bar.branch.button, release)
        }
        WaitForAsyncUtils.waitForFxEvents()
        val popup = bar.branch.popup
        assertTrue(popup.popup.isShowing)
        robot.interact { popup.search.text = "HEAD" }
        press(robot, popup.search, KeyCode.ENTER)
        press(robot, popup.search, KeyCode.ESCAPE)
        assertFalse(popup.popup.isShowing)
        robot.interact { bar.branch.button.requestFocus() }
        tab()
        assertSame<Node?>(bar.branch.clear, view.scene.focusOwner)
        tab()
        assertSame<Node?>(bar.user.button, view.scene.focusOwner)

        robot.interact { bar.options.requestFocus() }
        press(robot, bar.options, KeyCode.DOWN)
        assertTrue(bar.options.isShowing, "the menu opens with the keyboard")
        robot.interact { bar.options.hide() }
    }

    @Test
    fun `a user name or tree read for a folder opened before is dropped`(robot: FxRobot) {
        userName = "Me Myself"
        treePaths = listOf(GitTreePath("src", folder = true))
        val other = Path.of("/other")
        otherHistory = commits(3, "o")
        userNameGates[repo] = CountDownLatch(1)
        pathGates[repo] = CountDownLatch(1)
        userNameGates[other] = CountDownLatch(1)
        pathGates[other] = CountDownLatch(1)
        openAndExpand(robot)
        robot.interact { bar.user.button.fire() }
        robot.interact { bar.path.button.fire() }

        robot.interact { view.open(other) }
        userNameGates.getValue(repo).countDown()
        pathGates.getValue(repo).countDown()
        eventually { assertEquals(listOf(repo), userNameLoads.toList()) }
        eventually { assertEquals(listOf(repo), pathLoads.toList()) }
        WaitForAsyncUtils.waitForFxEvents()

        // The reads for the other folder are still waiting: the popups show nothing of the first one.
        robot.interact { bar.user.button.fire() }
        assertFalse(items(bar.user.popup).any { it.startsWith("me") }, "${items(bar.user.popup)}")
        robot.interact {
            bar.user.popup.hide()
            bar.path.button.fire()
        }
        assertEquals(emptyList(), items(bar.path.popup))
        robot.interact { bar.path.popup.hide() }
    }

    @Test
    fun `the tree is read again after a refresh, not after a filter change`(robot: FxRobot) {
        treePaths = listOf(GitTreePath("src", folder = true))
        openAndExpand(robot)
        robot.interact { bar.path.button.fire() }
        eventually { assertEquals(listOf("src"), items(bar.path.popup)) }
        clickItem(robot, bar.path.popup, "src")
        eventually { assertEquals(listOf("src"), filters.last().paths) }
        robot.interact { bar.path.popup.hide() }

        robot.interact { bar.path.button.fire() }
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(1, pathLoads.size, "a filter change keeps the tree")
        robot.interact { bar.path.popup.hide() }

        robot.interact { view.refresh() }
        robot.interact { bar.path.button.fire() }
        eventually { assertEquals(2, pathLoads.size) }
        robot.interact { bar.path.popup.hide() }
    }

    @Test
    fun `a click on a button of the bar leaves the focus where it was`(robot: FxRobot) {
        refs = someRefs
        openAndExpand(robot)
        robot.interact {
            bar.text.requestFocus()
            bar.text.text = "fix"
            bar.text.positionCaret(2)
        }

        click(robot, bar.regex)

        assertTrue(bar.regex.isSelected)
        assertSame<Node?>(bar.text, view.scene.focusOwner)
        assertEquals(2, bar.text.caretPosition)
        assertEquals("", bar.text.selectedText, "typing goes on where it was")

        robot.interact { view.table.requestFocus() }
        click(robot, bar.branch.button)
        assertTrue(bar.branch.popup.popup.isShowing)
        press(robot, bar.branch.popup.search, KeyCode.ESCAPE)
        assertFalse(bar.branch.popup.popup.isShowing)
        assertSame<Node?>(view.table, view.scene.focusOwner)

        // Only the press: a real mouse's release goes to the menu it opened, a fired one would close it again. Checked
        // and closed at once: the menu closes by itself when other test JVMs on the display take the window's focus.
        var opened = false
        robot.interact {
            Event.fireEvent(
                bar.options,
                MouseEvent(
                    MouseEvent.MOUSE_PRESSED,
                    1.0,
                    1.0,
                    1.0,
                    1.0,
                    MouseButton.PRIMARY,
                    1,
                    false,
                    false,
                    false,
                    false,
                    true,
                    false,
                    false,
                    true,
                    false,
                    false,
                    null,
                ),
            )
            opened = bar.options.isShowing
            bar.options.hide()
        }
        WaitForAsyncUtils.waitForFxEvents()
        assertTrue(opened)
        assertSame<Node?>(view.table, view.scene.focusOwner)

        // Focused with Tab, a button keeps the focus.
        robot.interact { bar.matchCase.requestFocus() }
        click(robot, bar.matchCase)
        assertSame<Node?>(bar.matchCase, view.scene.focusOwner)
    }

    private val branches get() = view.branchesPane

    /** HEAD and main on h1, branches in folders, two remotes (one far down) and two tags. */
    private val branchRefs =
        GitRefs(
            listOf(
                ref("HEAD", GitRefKind.HEAD, "h1"),
                ref("refs/heads/main", GitRefKind.LOCAL_BRANCH, "h1"),
                ref("refs/heads/feature/a", GitRefKind.LOCAL_BRANCH, "h5"),
                ref("refs/heads/feature/b", GitRefKind.LOCAL_BRANCH, "h7"),
                ref("refs/heads/team/fix/x", GitRefKind.LOCAL_BRANCH, "h9"),
                ref("refs/remotes/origin/main", GitRefKind.REMOTE_BRANCH, "h1"),
                ref("refs/remotes/origin/old", GitRefKind.REMOTE_BRANCH, "h40"),
                ref("refs/remotes/upstream/far", GitRefKind.REMOTE_BRANCH, "h800"),
                ref("refs/tags/v1.0", GitRefKind.TAG, "h1"),
                ref("refs/tags/v0.9", GitRefKind.TAG, "h3"),
            ),
            "refs/heads/main",
        )

    /** The tree as shown: the expanded nodes indented, groups and folders ending with `/`. */
    private fun shownBranches(): List<String> = WaitForAsyncUtils.asyncFx<List<String>> { branchLines() }.get()

    /** [shownBranches], on the JavaFX thread. */
    private fun branchLines(): List<String> {
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
    private fun branchItem(path: String): TreeItem<BranchNode> {
        fun find(item: TreeItem<BranchNode>): TreeItem<BranchNode>? =
            if (item.value?.path == path) item else item.children.firstNotNullOfOrNull(::find)
        return checkNotNull(find(branches.tree.root)) { path }
    }

    /** Selects the nodes at [paths] in the tree, as with the mouse and Shift/Ctrl. */
    private fun selectBranches(
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
    private fun clickBranch(
        robot: FxRobot,
        cell: TreeCell<*>,
        clickCount: Int = 1,
    ) {
        val point = WaitForAsyncUtils.asyncFx<javafx.geometry.Point2D> { cell.localToScene(cell.width - 10, 5.0) }.get()
        click(robot, cell, clickCount, point.x, point.y)
    }

    private fun branchCell(path: String): TreeCell<*> =
        branches.tree
            .lookupAll(".tree-cell")
            .filterIsInstance<TreeCell<*>>()
            .single { (it.item as? BranchNode)?.path == path }

    private fun openBranches(robot: FxRobot) {
        refs = branchRefs
        openAndExpand(robot)
        eventually {
            assertTrue(
                branches.tree.root.children
                    .isNotEmpty(),
            )
        }
    }

    /** The indices of the table's rows in view, the first and last possibly cut off. */
    private fun visibleRows(): IntRange {
        val flow = view.table.lookup(".virtual-flow") as VirtualFlow<*>
        return flow.firstVisibleCell.index..flow.lastVisibleCell.index
    }

    @Test
    fun `the branches pane shows HEAD, local and remote branches and tags left of the commits, Tags collapsed`(
        robot: FxRobot,
    ) {
        openBranches(robot)

        assertEquals(
            listOf(
                "HEAD (Current Branch)",
                "Local/",
                "  feature/",
                "    a",
                "    b",
                "  team/fix/",
                "    x",
                "  main",
                "Remote/",
                "  origin/",
                "    main",
                "    old",
                "  upstream/",
                "    far",
                "Tags/",
            ),
            shownBranches(),
        )
        robot.interact {
            assertEquals(listOf<Node>(branches, view.inner), view.body.items)
            assertEquals(view.body.width * 0.2, branches.width, 10.0, "the left 20 %")
            assertEquals(view.inner.width * 0.3, details.width, 10.0, "the details keep their 30 %")
            assertTrue(branches.localToScene(0.0, 0.0).x < view.table.localToScene(0.0, 0.0).x)
            assertEquals(bar.height, branches.bar.height, 0.5, "the search lines up with the filter bar")
            assertEquals(bar.localToScene(0.0, 0.0).y, branches.bar.localToScene(0.0, 0.0).y, 0.5)
            assertTrue(bar.branchesToggle.isSelected)
            assertEquals("Branches", bar.branchesToggle.tooltip.text)
            assertSame<Node?>(bar.branchesToggle, bar.children.first())
            assertTrue(bar.branchesToggle.isFocusTraversable)
            assertEquals("Search branches", branches.search.promptText)
            assertTrue(GIT_LOG_CSS in view.stylesheets)
        }
        robot.interact {
            fun icon(cell: TreeCell<*>) = (cell.graphic as javafx.scene.Parent).childrenUnmodifiable[0]

            fun name(cell: TreeCell<*>) = (cell.graphic as javafx.scene.Parent).childrenUnmodifiable[1] as Label
            val main = branchCell("Local/main")
            assertTrue("git-branch-current" in name(main).styleClass, "the current branch in bold")
            assertTrue("git-ref-head" in icon(main).styleClass)
            assertEquals("refs/heads/main", main.tooltip.text)
            val feature = branchCell("Local/feature/a")
            assertFalse("git-branch-current" in name(feature).styleClass)
            assertTrue("git-ref-local" in icon(feature).styleClass)
            assertTrue("git-ref-remote" in icon(branchCell("Remote/origin/old")).styleClass)
            assertTrue("git-ref-head" in icon(branchCell("HEAD")).styleClass)
            assertEquals("HEAD", branchCell("HEAD").tooltip.text)
            assertNull(branchCell("Local").tooltip)
        }

        // Tags open on demand.
        robot.interact { branchItem("Tags").isExpanded = true }
        assertEquals(listOf("Tags/", "  v0.9", "  v1.0"), shownBranches().takeLast(3))
        robot.interact {
            branchItem("Local").isExpanded = false
            branchItem("Remote").isExpanded = false
        }
        eventually {
            val tag = branchCell("Tags/v1.0")
            assertTrue("git-ref-tag" in ((tag.graphic as javafx.scene.Parent).childrenUnmodifiable[0]).styleClass)
        }
    }

    @Test
    fun `the branches pane says while loading, without refs and for a detached HEAD`(robot: FxRobot) {
        gate = CountDownLatch(1)
        refs = GitRefs(emptyList(), null)
        robot.interact {
            view.open(repo)
            view.expanded.set(true)
        }
        assertEquals("Loading…", branches.placeholder.text)
        assertTrue(branches.placeholder.isVisible)
        gate?.countDown()
        gate = null
        eventually { assertEquals("No branches yet.", branches.placeholder.text) }
        assertFalse(branches.tree.isVisible)

        refs = GitRefs(listOf(ref("HEAD", GitRefKind.HEAD, "abcdef0123456789")), null)
        robot.interact { view.refresh() }
        eventually { assertEquals(listOf("HEAD (detached at abcdef01)"), shownBranches()) }
        assertTrue(branches.tree.isVisible)
        assertFalse(branches.placeholder.isVisible)
    }

    @Test
    fun `the toggle hides and shows the branches pane, keeping its share, and takes the focus out of it`(
        robot: FxRobot,
    ) {
        openBranches(robot)
        robot.interact { branches.tree.requestFocus() }

        robot.interact { bar.branchesToggle.isSelected = false }
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(listOf<Node>(view.inner), view.body.items)
        assertNull(branches.scene)
        assertSame<Node?>(view.table, view.scene.focusOwner)
        robot.interact { assertEquals(view.body.width, view.inner.width, 1.0) }

        robot.interact { bar.branchesToggle.isSelected = true }
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(listOf<Node>(branches, view.inner), view.body.items)
        robot.interact { assertEquals(view.body.width * 0.2, branches.width, 10.0) }
        assertSame<Node?>(view.table, view.scene.focusOwner)

        // Dragged, hidden and shown again, and resized: the share stays.
        robot.interact { view.body.setDividerPositions(0.35) }
        WaitForAsyncUtils.waitForFxEvents()
        robot.interact { bar.branchesToggle.isSelected = false }
        robot.interact { bar.branchesToggle.isSelected = true }
        WaitForAsyncUtils.waitForFxEvents()
        try {
            robot.interact { assertEquals(view.body.width * 0.35, branches.width, 10.0) }
            robot.interact { view.scene.window.width = 600.0 }
            WaitForAsyncUtils.waitForFxEvents()
            robot.interact { assertEquals(view.body.width * 0.35, branches.width, 10.0) }
        } finally {
            // The stage is shared by the tests.
            robot.interact { view.scene.window.width = 800.0 }
            WaitForAsyncUtils.waitForFxEvents()
        }

        // A click on the toggle leaves the focus where it was.
        robot.interact { bar.text.requestFocus() }
        click(robot, bar.branchesToggle)
        assertFalse(bar.branchesToggle.isSelected)
        assertSame<Node?>(bar.text, view.scene.focusOwner)
        click(robot, bar.branchesToggle)
        assertTrue(bar.branchesToggle.isSelected)
        assertSame<Node?>(bar.text, view.scene.focusOwner)
    }

    @Test
    fun `searching narrows the tree with every node expanded, Escape clears it, Down goes into the tree`(
        robot: FxRobot,
    ) {
        openBranches(robot)
        robot.interact { branchItem("Local/feature").isExpanded = false }

        // Applied after a pause, not on every keystroke.
        val all = shownBranches()
        var unchanged = false
        var waiting = false
        // In one step: checked right after typing, well within the pause.
        robot.interact {
            branches.search.text = "FEAT"
            unchanged = branchLines() == all
            waiting = branches.debounce.status == Animation.Status.RUNNING
        }
        assertTrue(unchanged, "not yet")
        assertTrue(waiting)
        eventually {
            assertEquals(listOf("Local/", "  feature/", "    a", "    b"), shownBranches(), "open while searching")
        }
        robot.interact { branches.search.text = "v1" }
        eventually { assertEquals(listOf("Tags/", "  v1.0"), shownBranches(), "Tags open while searching") }
        robot.interact { branches.search.text = "origin/m" }
        eventually { assertEquals(listOf("Remote/", "  origin/", "    main"), shownBranches()) }

        robot.interact { branches.search.text = "nothing" }
        eventually { assertTrue(branches.placeholder.isVisible) }
        assertEquals("No matching branches", branches.placeholder.text)
        assertFalse(branches.tree.isVisible)

        // Escape clears the text, and the collapsed nodes are collapsed again.
        val reached = mutableListOf<KeyCode>()
        robot.interact { view.scene.addEventHandler(KeyEvent.KEY_PRESSED) { reached += it.code } }
        press(robot, branches.search, KeyCode.ESCAPE)
        assertEquals("", branches.search.text)
        assertEquals(emptyList(), reached, "consumed")
        assertTrue(branches.tree.isVisible)
        assertEquals(listOf("HEAD (Current Branch)", "Local/", "  feature/", "  team/fix/"), shownBranches().take(4))
        assertEquals("Tags/", shownBranches().last())
        press(robot, branches.search, KeyCode.ESCAPE)
        assertEquals(listOf(KeyCode.ESCAPE), reached, "not consumed when empty")

        // Down moves into the tree and selects the first branch, which shows its commit.
        robot.interact {
            branches.search.requestFocus()
            branches.search.text = "old"
        }
        press(robot, branches.search, KeyCode.DOWN)
        assertSame<Node?>(branches.tree, view.scene.focusOwner)
        assertEquals("refs/remotes/origin/old", branches.selectedRef?.name)
        eventually {
            assertEquals(
                "h40",
                view.table.selectionModel.selectedItem
                    ?.commit
                    ?.hash,
            )
        }

        // Shortcut+F in the pane focuses the search, elsewhere the log's text filter.
        press(robot, branches.tree, KeyCode.F, shortcut = true)
        assertSame<Node?>(branches.search, view.scene.focusOwner)
        assertEquals("old", branches.search.selectedText)
        robot.interact { branches.search.positionCaret(1) }
        press(robot, branches.search, KeyCode.F, shortcut = true)
        assertSame<Node?>(branches.search, view.scene.focusOwner)
        assertEquals("old", branches.search.selectedText)
        press(robot, view.table, KeyCode.F, shortcut = true)
        assertSame<Node?>(bar.text, view.scene.focusOwner)

        // Enter too, without waiting for the pause.
        robot.interact {
            branches.search.requestFocus()
            branches.search.text = "fix"
        }
        press(robot, branches.search, KeyCode.ENTER)
        assertSame<Node?>(branches.tree, view.scene.focusOwner)
        assertEquals("refs/heads/team/fix/x", branches.selectedRef?.name)
    }

    @Test
    fun `selecting a branch selects its commit and scrolls to it, the focus staying in the tree`(robot: FxRobot) {
        openBranches(robot)
        robot.interact { branches.tree.requestFocus() }

        clickBranch(robot, branchCell("Local/feature/b"))
        assertEquals("refs/heads/feature/b", branches.selectedRef?.name)
        eventually {
            assertEquals(
                listOf(6),
                view.table.selectionModel.selectedIndices
                    .toList(),
            )
        }
        assertSame<Node?>(branches.tree, view.scene.focusOwner)
        eventually { assertTrue(showsDetailsOf("Subject 7")) }
        val top = WaitForAsyncUtils.asyncFx<Int> { visibleRows().first }.get()
        assertEquals(0, top, "in view already: not scrolled")

        clickBranch(robot, branchCell("Remote/origin/old"))
        eventually {
            assertEquals(
                listOf(39),
                view.table.selectionModel.selectedIndices
                    .toList(),
            )
        }
        eventually {
            val rows = visibleRows()
            assertTrue(39 > rows.first && 39 < rows.last, "$rows")
        }
        assertSame<Node?>(branches.tree, view.scene.focusOwner)

        // Groups, folders and several branches do not navigate.
        clickBranch(robot, branchCell("Local"))
        selectBranches(robot, "Local/team/fix")
        selectBranches(robot, "Local/main", "Local/feature/a")
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(
            listOf(39),
            view.table.selectionModel.selectedIndices
                .toList(),
        )

        // The keyboard moves through the branches.
        selectBranches(robot, "Local/feature/a")
        eventually {
            assertEquals(
                listOf(4),
                view.table.selectionModel.selectedIndices
                    .toList(),
            )
        }
        press(robot, branches.tree, KeyCode.DOWN)
        eventually {
            assertEquals(
                listOf(6),
                view.table.selectionModel.selectedIndices
                    .toList(),
            )
        }
        assertEquals(listOf(0 to 500), loads.toList())
    }

    @Test
    fun `a navigation cut off by a refresh goes on after it`(robot: FxRobot) {
        openBranches(robot)
        gate = CountDownLatch(1)

        // The second page waits; the refresh starts a new run, which drops it.
        selectBranches(robot, "Remote/upstream/far")
        robot.interact { view.refresh() }
        gate?.countDown()
        gate = null

        eventually {
            assertEquals(
                listOf(799),
                view.table.selectionModel.selectedIndices
                    .toList(),
            )
        }
        assertEquals("", status(robot))
    }

    @Test
    fun `a branch further down loads pages until its commit is there`(robot: FxRobot) {
        openBranches(robot)

        selectBranches(robot, "Remote/upstream/far")

        eventually {
            assertEquals(
                listOf(799),
                view.table.selectionModel.selectedIndices
                    .toList(),
            )
        }
        assertEquals(listOf(0 to 500, 500 to 500), loads.toList().take(2))
        eventually {
            val rows = visibleRows()
            assertTrue(799 in rows.first + 1..<rows.last, "$rows")
        }
        assertEquals("", status(robot))
    }

    @Test
    fun `a branch beyond 10,000 commits, out of the log or hidden by the filters is told in the header`(
        robot: FxRobot,
    ) {
        history = commits(10_200)
        refs =
            GitRefs(
                branchRefs.refs +
                    listOf(
                        ref("refs/heads/deep", GitRefKind.LOCAL_BRANCH, "h10100"),
                        ref("refs/heads/lost", GitRefKind.LOCAL_BRANCH, "nowhere"),
                    ),
                "refs/heads/main",
            )
        openAndExpand(robot)
        eventually {
            assertTrue(
                branches.tree.root.children
                    .isNotEmpty(),
            )
        }

        selectBranches(robot, "Local/deep")
        eventually { assertEquals("deep is more than 10,000 commits down.", status(robot)) }
        assertEquals(10_000, view.table.items.size)

        // Not further down: the log is complete without it.
        history = commits(1200)
        robot.interact { view.refresh() }
        eventually { assertEquals(500, view.table.items.size) }
        selectBranches(robot, "Local/lost")
        eventually { assertEquals("lost is not in the log.", status(robot)) }
        assertEquals(1200, view.table.items.size)

        selectBranches(robot, "Local/main")
        eventually {
            assertEquals(
                listOf(0),
                view.table.selectionModel.selectedIndices
                    .toList(),
            )
        }
        assertEquals("", status(robot), "cleared by the next selection")

        robot.interact { bar.text.text = "Subject 12" }
        press(robot, bar.text, KeyCode.ENTER)
        eventually { assertEquals("Subject 12", filters.last().text) }
        eventually { assertEquals(12, view.table.items.size) }
        selectBranches(robot, "Local/feature/a")
        eventually { assertEquals("feature/a is hidden by the filters.", status(robot)) }
    }

    @Test
    fun `double-click, Enter and Filter Log by Branch filter the log by the selected branches`(robot: FxRobot) {
        reachable =
            mapOf(
                "refs/heads/feature/a" to setOf("h5", "h6"),
                "refs/heads/feature/b" to setOf("h7"),
                "HEAD" to setOf("h1", "h2"),
            )
        openBranches(robot)
        robot.interact { branches.tree.requestFocus() }

        val cell = branchCell("Local/feature/a")
        clickBranch(robot, cell)
        clickBranch(robot, cell, 2)
        eventually { assertEquals(listOf("refs/heads/feature/a"), filters.last().revisions) }
        eventually { assertEquals(listOf("h5", "h6"), view.table.items.map { it.commit.hash }) }
        assertEquals("Branch: feature/a", bar.branch.label.text)
        assertTrue(bar.branch.clear.isVisible && bar.branch.clear.isManaged)
        robot.interact { bar.branch.button.fire() }
        assertEquals("feature/a", items(bar.branch.popup).first(), "checked first")
        robot.interact {
            val checked =
                bar.branch.popup.list
                    .lookupAll(".list-cell")
                    .filterIsInstance<ListCell<*>>()
                    .single { (it.item as? FilterItem)?.text == "feature/a" }
                    .lookup(".check-box") as javafx.scene.control.CheckBox
            assertTrue(checked.isSelected)
            bar.branch.popup.hide()
        }

        // Enter with several selected: all of them, replacing the filter.
        selectBranches(robot, "HEAD", "Local/feature/b")
        assertEquals("Filter Log by 2 Branches", branches.filterItem.text)
        press(robot, branches.tree, KeyCode.ENTER)
        eventually { assertEquals(listOf("HEAD", "refs/heads/feature/b"), filters.last().revisions) }
        assertEquals("Branch: HEAD +1", bar.branch.label.text)

        // The context menu's item, for one branch.
        selectBranches(robot, "Local/feature/a")
        assertEquals("Filter Log by Branch", branches.filterItem.text)
        assertFalse(branches.filterItem.isDisable)
        val loadsBefore = filters.size
        robot.interact { branches.filterItem.fire() }
        eventually { assertEquals(listOf("refs/heads/feature/a"), filters.last().revisions) }
        assertEquals(loadsBefore + 1, filters.size, "applied once")

        // Enter on a folder opens or closes it.
        selectBranches(robot, "Local/feature")
        assertTrue(branches.filterItem.isDisable)
        assertTrue(branches.copyNameItem.isDisable)
        press(robot, branches.tree, KeyCode.ENTER)
        assertFalse(branchItem("Local/feature").isExpanded)
        press(robot, branches.tree, KeyCode.ENTER)
        assertTrue(branchItem("Local/feature").isExpanded)
        assertEquals(listOf("refs/heads/feature/a"), filters.last().revisions)
    }

    @Test
    fun `Copy Name copies the short names of the selected branches`(robot: FxRobot) {
        openBranches(robot)
        assertEquals(
            listOf("Filter Log by Branch", null, "Copy Name"),
            branches.tree.contextMenu.items
                .map { it.text },
        )

        selectBranches(robot, "Remote/origin/old", "HEAD", "Local/feature/b")
        assertFalse(branches.copyNameItem.isDisable)
        robot.interact { branches.copyNameItem.fire() }
        assertEquals("HEAD\nfeature/b\norigin/old", clipboard())
    }

    @Test
    fun `a refresh keeps the selected branch and collapsed folders, opening a folder clears search and selection`(
        robot: FxRobot,
    ) {
        openBranches(robot)
        selectBranches(robot, "Local/feature/b")
        eventually {
            assertEquals(
                listOf(6),
                view.table.selectionModel.selectedIndices
                    .toList(),
            )
        }
        robot.interact {
            branchItem("Local/team/fix").isExpanded = false
            branchItem("Tags").isExpanded = true
            // Another commit, chosen in the table: an unchanged branch does not take the selection back.
            view.table.selectionModel.clearAndSelect(2)
        }
        refs = GitRefs(branchRefs.refs + ref("refs/heads/new", GitRefKind.LOCAL_BRANCH, "h2"), "refs/heads/main")

        robot.interact { view.refresh() }

        eventually { assertTrue("  new" in shownBranches()) }
        assertEquals("refs/heads/feature/b", branches.selectedRef?.name)
        assertFalse(branchItem("Local/team/fix").isExpanded)
        assertTrue(branchItem("Tags").isExpanded)
        assertEquals(
            listOf(2),
            view.table.selectionModel.selectedIndices
                .toList(),
            "not navigated again",
        )

        // A moved branch: followed only while the user is still on it.
        fun moveFeatureB(to: String) {
            refs =
                GitRefs(
                    branchRefs.refs.map { if (it.name == "refs/heads/feature/b") it.copy(commit = to) else it },
                    "refs/heads/main",
                )
            robot.interact { view.refresh() }
            eventually { assertEquals(to, branches.selectedRef?.commit) }
            WaitForAsyncUtils.waitForFxEvents()
        }

        fun selectedRows() =
            WaitForAsyncUtils
                .asyncFx<List<Int>> {
                    view.table.selectionModel.selectedIndices
                        .toList()
                }.get()

        // Another commit selected in the table: it stays.
        moveFeatureB("h9")
        assertEquals(listOf(2), selectedRows())

        // Its old commit still selected: the new one is.
        robot.interact { view.table.selectionModel.clearAndSelect(8) }
        moveFeatureB("h11")
        eventually { assertEquals(listOf(10), selectedRows()) }

        // Not while the pane is hidden.
        robot.interact { bar.branchesToggle.isSelected = false }
        moveFeatureB("h13")
        assertEquals(listOf(10), selectedRows())
        robot.interact { bar.branchesToggle.isSelected = true }

        // Opening a folder while expanded says "Loading…" until its refs are there.
        gate = CountDownLatch(1)
        robot.interact { view.open(repo) }
        assertEquals("Loading…", branches.placeholder.text)
        assertTrue(branches.placeholder.isVisible)
        gate?.countDown()
        gate = null

        robot.interact {
            branches.search.text = "feat"
            bar.branchesToggle.isSelected = false
            view.open(repo)
            bar.branchesToggle.isSelected = true
        }
        eventually {
            assertTrue(
                branches.tree.root.children
                    .isNotEmpty(),
            )
        }
        assertEquals("", branches.search.text)
        assertTrue(branches.tree.selectionModel.isEmpty)
        assertFalse(branchItem("Tags").isExpanded, "collapsed again")

        robot.interact {
            bar.branchesToggle.isSelected = false
            view.open(repo)
        }
        assertFalse(bar.branchesToggle.isSelected, "open keeps the pane hidden")
        assertEquals(listOf<Node>(view.inner), view.body.items)
    }

    @Test
    fun `a narrow filter bar shrinks the text field, not the buttons, and ellipsizes a long branch`(robot: FxRobot) {
        openBranches(robot)

        fun shown(label: Label) = (label.lookup(".text") as Text).text

        robot.interact {
            assertTrue(bar.width < bar.prefWidth(-1.0), "narrow: ${bar.width} of ${bar.prefWidth(-1.0)}")
            assertTrue(bar.search.width < bar.search.prefWidth(-1.0) - 1, "${bar.search.width}")
            assertTrue(bar.search.width >= 100 - 0.5, "${bar.search.width}")
            for (button in listOf(bar.branch, bar.user, bar.path)) {
                assertEquals(button.prefWidth(-1.0), button.width, 0.5, button.label.text)
                assertEquals(button.label.text, shown(button.label))
            }
        }

        robot.interact { bar.setBranches(listOf("refs/heads/a-very-long-branch-name-that-does-not-fit")) }
        eventually {
            assertTrue(bar.branch.label.width <= 160.5, "${bar.branch.label.width}")
            assertTrue(shown(bar.branch.label).endsWith("..."), shown(bar.branch.label))
        }
        robot.interact {
            for (button in listOf(bar.user, bar.path)) {
                assertEquals(button.prefWidth(-1.0), button.width, 0.5, button.label.text)
                assertEquals(button.label.text, shown(button.label))
            }
        }
    }
}
