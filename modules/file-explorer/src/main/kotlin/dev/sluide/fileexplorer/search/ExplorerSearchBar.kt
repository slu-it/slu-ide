package dev.sluide.fileexplorer.search

import dev.sluide.fileexplorer.tree.FileTreeItem
import javafx.beans.binding.Bindings
import javafx.beans.value.ObservableBooleanValue
import javafx.beans.value.ObservableValue
import javafx.geometry.Pos
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignM
import java.nio.file.Path

/**
 * The explorer's search row above the tree: a magnifier and the field (prompt "Search files"), and under it the
 * status line, which takes no room while it is empty. The field is disabled while the explorer has no roots ([hasRoots]).
 * Keys in the field: Escape clears the text, or moves the focus to the tree when it is empty; Down selects the first
 * match in row order and focuses the tree; Enter opens the first matching file ([open]), or selects the first match
 * when only folders match. Both wait for the results of the text typed so far ([ExplorerSearch.whenCurrent]). A thin layer over [search]; the matches are the [rows] whose entry (or a folder of their
 * chain) is in [ExplorerSearch.matches].
 */
internal class ExplorerSearchBar(
    val search: ExplorerSearch,
    status: ObservableValue<String>,
    hasRoots: ObservableBooleanValue,
    /** The rows of the tree that are shown, in order. */
    private val rows: () -> List<FileTreeItem>,
    private val select: (FileTreeItem) -> Unit,
    private val focusTree: () -> Unit,
    /** Opens the path if it is a file; returns whether it did. */
    private val open: (Path) -> Boolean,
) : VBox() {
    val field: TextField =
        TextField().apply {
            id = "explorer-search"
            // Like the bar's buttons, the field is no stop of the focus traversal: a window starts with the focus on the
            // tree (the first node taking it would be the field above), and so does the scene's fallback when the focus
            // owner leaves it. A click and Shortcut+F reach the field; Tab does not.
            isFocusTraversable = false
            promptText = "Search files"
            disableProperty().bind(Bindings.not(hasRoots))
            textProperty().addListener { _, _, text -> search.textChanged(text) }
            addEventFilter(KeyEvent.KEY_PRESSED, ::onKey)
        }

    val statusLabel: Label =
        Label().apply {
            id = "explorer-search-status"
            styleClass += "explorer-search-status"
            textProperty().bind(status)
            // The text wraps in the row's width instead of being cut off.
            isWrapText = true
            minWidth = 0.0
            maxWidth = Double.MAX_VALUE
            visibleProperty().bind(status.map { it.isNotEmpty() })
            managedProperty().bind(visibleProperty())
        }

    init {
        styleClass += "explorer-search"
        // The row is not inside the tree, which carries the stylesheet for its cells.
        stylesheets +=
            checkNotNull(
                ExplorerSearchBar::class.java.getResource("/dev/sluide/fileexplorer/file-explorer.css"),
            ).toExternalForm()
        HBox.setHgrow(field, Priority.ALWAYS)
        field.maxWidth = Double.MAX_VALUE
        children.setAll(
            HBox(FontIcon(MaterialDesignM.MAGNIFY), field).apply {
                styleClass += "explorer-search-field"
                alignment = Pos.CENTER_LEFT
            },
            statusLabel,
        )
    }

    /** Takes the focus and selects the whole text. */
    fun focusAndSelectAll() {
        field.requestFocus()
        field.selectAll()
    }

    /** The explorer shows other roots: the search forgot its text, the field follows without searching. */
    fun rootsChanged() {
        search.rootsChanged()
        field.clear()
    }

    private fun onKey(event: KeyEvent) {
        // Shift+Down and the like stay the field's own (selecting text).
        val plain = !(event.isShiftDown || event.isControlDown || event.isAltDown || event.isMetaDown)
        val handled =
            when {
                event.code == KeyCode.ESCAPE -> {
                    if (field.text.isNotEmpty()) field.clear() else focusTree()
                    true
                }

                event.code == KeyCode.DOWN && plain -> {
                    search.whenCurrent {
                        matchRows().firstOrNull()?.let(select)
                        focusTreeFromField()
                    }
                    true
                }

                event.code == KeyCode.ENTER && plain -> {
                    search.whenCurrent(::openFirstMatch)
                    true
                }

                else -> {
                    false
                }
            }
        if (handled) event.consume()
    }

    // A key waiting for the results may act after the user went elsewhere: the focus is taken only from the field.
    private fun focusTreeFromField() {
        if (field.isFocused) focusTree()
    }

    private fun openFirstMatch() {
        val matches = matchRows()
        val file = matches.firstOrNull { !it.isDirectory }
        if (file != null) {
            open(file.value)
        } else {
            matches.firstOrNull()?.let {
                select(it)
                focusTreeFromField()
            }
        }
    }

    /** The shown rows that are a match (a compacted row when any folder of its chain is one), in row order. */
    private fun matchRows(): List<FileTreeItem> {
        val matches = search.matches
        if (matches.isEmpty()) return emptyList()
        return rows().filter { row -> row.chain.any { it in matches } }
    }
}
