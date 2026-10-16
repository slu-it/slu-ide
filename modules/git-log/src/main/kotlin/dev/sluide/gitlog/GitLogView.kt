package dev.sluide.gitlog

import dev.sluide.git.GitCommit
import dev.sluide.git.GitLog
import dev.sluide.git.GitLogPage
import javafx.application.Platform
import javafx.beans.binding.Bindings
import javafx.beans.property.BooleanProperty
import javafx.beans.property.ReadOnlyStringWrapper
import javafx.beans.property.SimpleBooleanProperty
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
 * commits of the repository given to [open] in a table, loaded in pages in the background as the user scrolls.
 *
 * [load] reads a page of commits (root, skip, limit) and runs on the thread `sluide-git-log`; its results are handled
 * through [dispatch], the JavaFX thread in the application.
 */
@Component
// One instance per IDE window (the "window" scope of the ide module, ADR 0014).
@Scope("window")
class GitLogView(
    load: (Path, Int, Int) -> GitLogPage?,
    dispatch: (Runnable) -> Unit,
) : BorderPane(),
    AutoCloseable {
    @Autowired
    constructor(gitLog: GitLog) : this(gitLog::commits, Platform::runLater)

    private val executor =
        Executors.newSingleThreadExecutor { Thread(it, "sluide-git-log").apply { isDaemon = true } }

    private val pages = GitLogPages(load, executor::execute, dispatch)

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

    /** The commits, newest first; several can be selected. */
    internal val table: TableView<GitCommit> =
        object : TableView<GitCommit>(pages.rows) {
            override fun createDefaultSkin() = PagingSkin(this, pages::shown)
        }.apply {
            styleClass += "git-log-table"
            selectionModel.selectionMode = SelectionMode.MULTIPLE
            columns.addAll(
                column("Subject", 600.0) { it.subject }.apply { minWidth = 80.0 },
                column("Author", 160.0) { it.author }.apply { minWidth = 50.0 },
                column("Date", 140.0) { dateFormat.format(it.authorTime) }.apply { minWidth = 50.0 },
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

    /** The table; a later version puts the details of the selected commit to its right. */
    private val body = SplitPane(table).apply { styleClass += "git-log-body" }

    init {
        styleClass += "git-log"
        stylesheets += checkNotNull(GitLogView::class.java.getResource("git-log.css")).toExternalForm()
        top = header
        pages.selectedHashes = { table.selectionModel.selectedItems.map { it.hash } }
        pages.select = { indices ->
            table.selectionModel.clearSelection()
            indices.forEach(table.selectionModel::select)
        }
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
                .map { pages.rows[it].hash }
        if (hashes.isEmpty()) return
        Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString(hashes.joinToString("\n")) })
    }

    override fun close() {
        pages.close()
        executor.shutdownNow()
    }

    private fun column(
        title: String,
        width: Double,
        value: (GitCommit) -> String,
    ) = TableColumn<GitCommit, String>(title).apply {
        prefWidth = width
        // Sorting would mix the order the pages are loaded in.
        isSortable = false
        setCellValueFactory { ReadOnlyStringWrapper(value(it.value)).readOnlyProperty }
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

/** Reports the last visible row after every layout and scroll, so the next page is loaded in time. */
private class PagingSkin(
    table: TableView<GitCommit>,
    private val shown: (Int) -> Unit,
) : TableViewSkin<GitCommit>(table) {
    // Called by the super constructor: uses `shown` only later, in layouts and scrolls.
    override fun createVirtualFlow(): VirtualFlow<TableRow<GitCommit>> =
        object : VirtualFlow<TableRow<GitCommit>>() {
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
        val resize = features as TableView.ResizeFeatures<GitCommit>
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
