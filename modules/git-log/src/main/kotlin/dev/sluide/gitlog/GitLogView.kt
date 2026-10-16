package dev.sluide.gitlog

import dev.sluide.git.GitLog
import javafx.application.Platform
import javafx.beans.binding.Bindings
import javafx.beans.property.BooleanProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.beans.property.ReadOnlyStringWrapper
import javafx.beans.property.SimpleBooleanProperty
import javafx.collections.ListChangeListener
import javafx.event.EventTarget
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.ConstrainedColumnResizeBase
import javafx.scene.control.ContextMenu
import javafx.scene.control.Label
import javafx.scene.control.MenuItem
import javafx.scene.control.SelectionMode
import javafx.scene.control.SplitPane
import javafx.scene.control.TableColumn
import javafx.scene.control.TableRow
import javafx.scene.control.TableView
import javafx.scene.control.Tooltip
import javafx.scene.control.skin.TableViewSkin
import javafx.scene.control.skin.VirtualFlow
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.util.Callback
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignC
import org.kordamp.ikonli.materialdesign2.MaterialDesignR
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.concurrent.Executors

/**
 * The git log area of a window (ADR 0035): a header bar ("Git Log", a chevron, Refresh) and, while [expanded], the
 * commits of the repository given to [open] in a table, loaded in pages in the background as the user scrolls, with
 * the refs pointing at them, and the details of the selected commit to the right (ADR 0037).
 *
 * [source] reads the commits, refs and details on the thread `sluide-git-log`; its results are handled through
 * [dispatch], the JavaFX thread in the application.
 */
@Component
// One instance per IDE window (the "window" scope of the ide module, ADR 0014).
@Scope("window")
class GitLogView internal constructor(
    source: GitLogSource,
    dispatch: (Runnable) -> Unit,
) : BorderPane(),
    AutoCloseable {
    @Autowired
    constructor(gitLog: GitLog) : this(GitLogCliSource(gitLog), Platform::runLater)

    private val executor =
        Executors.newSingleThreadExecutor { Thread(it, "sluide-git-log").apply { isDaemon = true } }

    private val pages = GitLogPages(source::page, source::refs, executor::execute, dispatch)

    private val details = CommitDetails(source::details, executor::execute, dispatch)

    /** Whether the commits are shown below the header; collapsed (`false`) at first. */
    val expanded: BooleanProperty = SimpleBooleanProperty(false)

    /**
     * Called when the log collapses while the focus is in it, to give the focus to what should have it instead (the
     * window's editor); by default nothing is focused on purpose.
     */
    var returnFocus: () -> Unit = {}

    private val chevron = FontIcon(MaterialDesignC.CHEVRON_RIGHT)

    private val refreshButton =
        Button(null, FontIcon(MaterialDesignR.REFRESH)).apply {
            styleClass += "git-log-refresh"
            tooltip = Tooltip("Refresh")
            isFocusTraversable = false
            setOnAction { refresh() }
        }

    /** The header bar; clicking it (but not Refresh) expands or collapses the log. */
    internal val header: HBox =
        HBox(
            chevron,
            Label("Git Log").apply { styleClass += "git-log-title" },
            // A failure while rows are shown; with no rows the table's placeholder says it.
            Label().apply {
                styleClass += "git-log-status"
                textProperty().bind(
                    Bindings.createStringBinding(
                        {
                            val state = pages.state.get()
                            if (state == GitLogState.FAILED && pages.rows.isNotEmpty()) state.message else ""
                        },
                        pages.state,
                        pages.rows,
                    ),
                )
            },
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            refreshButton,
        ).apply {
            styleClass += "git-log-header"
            alignment = Pos.CENTER_LEFT
            setOnMouseClicked { event ->
                if (event.button == MouseButton.PRIMARY && !refreshButton.contains(event.target)) {
                    expanded.set(!expanded.get())
                }
            }
        }

    private val dateFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())

    private val copyRevisionNumber = MenuItem("Copy Revision Number").apply { setOnAction { copyRevisionNumbers() } }

    /** The commits with their graph, newest first; several can be selected. */
    internal val table: TableView<GitLogRow> =
        object : TableView<GitLogRow>(pages.rows) {
            override fun createDefaultSkin() = PagingSkin(this, pages::shown)
        }.apply {
            styleClass += "git-log-table"
            selectionModel.selectionMode = SelectionMode.MULTIPLE
            columns.addAll(
                subjectColumn(),
                column("Author", 160.0) { it.commit.author }.apply { minWidth = 50.0 },
                column("Date", 140.0) { dateFormat.format(it.commit.authorTime) }.apply { minWidth = 50.0 },
            )
            columnResizePolicy = SubjectTakesTheRest
            placeholder = Label().apply { textProperty().bind(pages.state.map { it.message }) }
            contextMenu = ContextMenu(copyRevisionNumber)
            copyRevisionNumber.disableProperty().bind(Bindings.isEmpty(selectionModel.selectedItems))
            addEventHandler(KeyEvent.KEY_PRESSED) { event ->
                if (COPY.match(event)) {
                    copyRevisionNumbers()
                    event.consume()
                }
            }
        }

    /** The details of the selected commit. */
    internal val detailsPane = CommitDetailsPane(details.state)

    /** The table and, at first in the right 30 %, the details ([SharedSplit]). */
    internal val body = SharedSplit(table, detailsPane, 0.7).apply { styleClass += "git-log-body" }

    init {
        styleClass += "git-log"
        stylesheets += GIT_LOG_CSS
        top = header
        pages.selectedHashes = { table.selectionModel.selectedItems.map { it.commit.hash } }
        pages.select = { indices ->
            table.selectionModel.clearSelection()
            indices.forEach(table.selectionModel::select)
        }
        details.selection = { table.selectionModel.selectedItems.filterNotNull() }
        details.root = { pages.root }
        table.selectionModel.selectedItems.addListener(ListChangeListener { details.selectionChanged() })
        expanded.subscribe { isExpanded ->
            val focusInBody = body.contains(scene?.focusOwner)
            center = if (isExpanded) body else null
            chevron.iconCode = if (isExpanded) MaterialDesignC.CHEVRON_DOWN else MaterialDesignC.CHEVRON_RIGHT
            pages.visible = isExpanded
            if (isExpanded) {
                // The split pane adds the table to the scene with its skin, which CSS creates.
                body.applyCss()
                table.requestFocus()
            } else if (focusInBody) {
                returnFocus()
            }
        }
    }

    /** Shows the log of the repository at [root]; `null` shows nothing. Loads once (or as soon as) [expanded]. */
    fun open(root: Path?) = pages.open(root)

    /** Loads the first page again now if [expanded], else the next time it is expanded; keeps the selection by hash. */
    fun refresh() = pages.refresh()

    /** Copies the full hashes of the selected commits, one per line in table order. */
    private fun copyRevisionNumbers() {
        val hashes =
            table.selectionModel.selectedIndices
                .sorted()
                .map { pages.rows[it].commit.hash }
        if (hashes.isEmpty()) return
        Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString(hashes.joinToString("\n")) })
    }

    override fun close() {
        pages.close()
        details.close()
        detailsPane.close()
        executor.shutdownNow()
    }

    private fun column(
        title: String,
        width: Double,
        value: (GitLogRow) -> String,
    ) = TableColumn<GitLogRow, String>(title).apply {
        prefWidth = width
        // Sorting would mix the order the pages are loaded in.
        isSortable = false
        setCellValueFactory { ReadOnlyStringWrapper(value(it.value)).readOnlyProperty }
    }

    /** The subject with the commit graph left of it (ADR 0036) and the ref labels at its right end (ADR 0037). */
    private fun subjectColumn() =
        TableColumn<GitLogRow, GitLogRow>("Subject").apply {
            prefWidth = 600.0
            minWidth = 80.0
            isSortable = false
            setCellValueFactory { ReadOnlyObjectWrapper(it.value).readOnlyProperty }
            setCellFactory { GraphCell() }
        }

    private fun Node.contains(target: EventTarget?): Boolean {
        var node = target as? Node
        while (node != null) {
            if (node === this) return true
            node = node.parent
        }
        return false
    }
}

/**
 * Two panes side by side that keep their shares of the width when it changes: [share] for the first at first, then
 * wherever the divider is dragged to (while the window is open). A plain split pane would keep the pixel width of a
 * pane that does not resize with it, or split the change equally between panes that do.
 */
internal class SharedSplit(
    first: Node,
    second: Node,
    share: Double,
) : SplitPane(first, second) {
    /** The first pane's share of the width; follows the divider when it is dragged. */
    var share = share
        private set

    private var laidOutWidth = -1.0
    private var layingOut = false

    init {
        // The skin would spread a change of the width equally over the panes that resize with it; with none it keeps
        // the divider position set before each layout at a new width.
        SplitPane.setResizableWithParent(first, false)
        SplitPane.setResizableWithParent(second, false)
        setDividerPositions(share)
        // Moved outside a layout: dragged (or set). Within one the skin only fits it to the panes' minimum widths.
        dividers.single().positionProperty().subscribe { position -> if (!layingOut) this.share = position.toDouble() }
    }

    override fun layoutChildren() {
        layingOut = true
        try {
            if (width != laidOutWidth) setDividerPositions(share)
            super.layoutChildren()
        } finally {
            layingOut = false
        }
        laidOutWidth = width
    }
}

/** Reports the last visible row after every layout and scroll, so the next page is loaded in time. */
private class PagingSkin(
    table: TableView<GitLogRow>,
    private val shown: (Int) -> Unit,
) : TableViewSkin<GitLogRow>(table) {
    // Called by the super constructor: uses `shown` only later, in layouts and scrolls.
    override fun createVirtualFlow(): VirtualFlow<TableRow<GitLogRow>> =
        object : VirtualFlow<TableRow<GitLogRow>>() {
            override fun layoutChildren() {
                super.layoutChildren()
                report()
            }

            override fun scrollPixels(delta: Double): Double = super.scrollPixels(delta).also { report() }

            private fun report() {
                val index = lastVisibleCell?.index ?: return
                if (index >= 0) shown(index)
            }
        }
}

/**
 * Keeps the columns as wide as the table: a resized table widens or narrows only the first column (the subject), so
 * the others keep their widths ([fitColumnWidths]); dragging a column's edge resizes the next column as well, and the
 * new widths of the others become the widths they return to.
 */
private object SubjectTakesTheRest :
    ConstrainedColumnResizeBase(),
    Callback<TableView.ResizeFeatures<*>, Boolean> {
    override fun call(features: TableView.ResizeFeatures<*>): Boolean {
        @Suppress("UNCHECKED_CAST")
        val resize = features as TableView.ResizeFeatures<GitLogRow>
        val columns = resize.table.visibleLeafColumns
        if (columns.isEmpty()) return false
        if (resize.column != null) {
            val resized = TableView.CONSTRAINED_RESIZE_POLICY_FLEX_NEXT_COLUMN.call(resize)
            // Wanted widths are kept as preferred widths; setting them does not change the current ones.
            columns.drop(1).forEach { it.prefWidth = it.width }
            return resized
        }
        val others = columns.drop(1)
        val widths =
            fitColumnWidths(
                resize.contentWidth,
                columns.first().minWidth,
                others.map { it.prefWidth },
                others.map { it.minWidth },
            )
        columns.zip(widths).forEach { (column, width) -> resize.setColumnWidth(column, width) }
        return true
    }
}

/**
 * The widths of a first column that takes the rest of [contentWidth] and of the [others] (their wanted widths), whose
 * minimum widths are [otherMinWidths]. The others keep their widths while the first column can be at least
 * [firstMinWidth] wide; below that they shrink proportionally towards their minimum widths, so the last column stays
 * in view. Narrower than all minimum widths together, every column is at its minimum and the last ones are cut off at
 * the right (a constrained table has no horizontal scroll bar).
 */
internal fun fitColumnWidths(
    contentWidth: Double,
    firstMinWidth: Double,
    others: List<Double>,
    otherMinWidths: List<Double>,
): List<Double> {
    val wanted = others.sum()
    if (contentWidth - wanted >= firstMinWidth) return listOf(contentWidth - wanted) + others
    val shrinkable = others.zip(otherMinWidths).sumOf { (width, min) -> maxOf(0.0, width - min) }
    val excess = wanted - (contentWidth - firstMinWidth)
    val share = if (shrinkable > 0) minOf(1.0, excess / shrinkable) else 1.0
    val shrunk = others.zip(otherMinWidths).map { (width, min) -> maxOf(min, width - maxOf(0.0, width - min) * share) }
    return listOf(maxOf(firstMinWidth, contentWidth - shrunk.sum())) + shrunk
}

private val COPY = KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN)
