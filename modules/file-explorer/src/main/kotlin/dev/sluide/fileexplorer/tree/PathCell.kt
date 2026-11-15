package dev.sluide.fileexplorer.tree

import dev.sluide.fileexplorer.FileMarker
import dev.sluide.fileexplorer.FileMarkers
import dev.sluide.fileexplorer.search.NameQuery
import dev.sluide.fileicons.show
import javafx.beans.InvalidationListener
import javafx.beans.Observable
import javafx.beans.WeakInvalidationListener
import javafx.beans.value.ObservableValue
import javafx.css.PseudoClass
import javafx.geometry.Pos
import javafx.scene.control.ContentDisplay
import javafx.scene.control.Label
import javafx.scene.control.Tooltip
import javafx.scene.control.TreeCell
import javafx.scene.control.TreeItem
import javafx.scene.input.ContextMenuEvent
import javafx.scene.input.MouseButton
import javafx.scene.layout.HBox
import javafx.scene.shape.Circle
import org.kordamp.ikonli.javafx.FontIcon
import java.nio.file.Path

/**
 * One row of the explorer tree: the icon, the name and the marker bubbles after it. [markers] and [canOpenFile] are
 * asked whenever the row is shown; [markerChanges] invalidates when the answers of [markers] change. The row of
 * [dropTarget] has the pseudo-class `:drop-target`; rows of entries that were cut ([isCut], asked again when
 * [cutChanges] invalidates) have `:cut`.
 */
internal class PathCell(
    private val fileIconSize: ObservableValue<Int>,
    markerChanges: Observable,
    private val markers: (path: Path, directory: Boolean) -> FileMarkers,
    private val canOpenFile: (Path) -> Boolean,
    private val onMenuRequested: (FileTreeItem?) -> Unit,
    private val open: (Path) -> Unit,
    private val dropTarget: ObservableValue<TreeItem<Path>?>,
    cutChanges: Observable,
    private val isCut: (Path) -> Boolean,
    /** [dev.sluide.librarycode.LibraryCodeSettings.readOnly]: the library folder icons are shown while it is true. */
    private val libraryReadOnly: ObservableValue<Boolean>,
    /** The query of the applied search; while there is one, the matching part of every name is highlighted. */
    private val searchQuery: () -> NameQuery?,
) : TreeCell<Path>() {
    private val pathTooltip = Tooltip()
    private val icon = FontIcon().apply { iconSizeProperty().bind(fileIconSize) }
    private val iconUpdater = InvalidationListener { updateIcon() }
    private val decorationUpdater =
        InvalidationListener {
            updateIcon()
            updateTooltip()
        }
    private val libraryTooltip = Tooltip()
    private val generatedFolderTooltip = Tooltip()

    // The name is a label in the graphic, so the markers can follow it; it looks like the cell's own text unless
    // a marker colours it.
    private val name = nameLabel()

    // While a search is applied the name is shown in three labels, the middle one highlighted; they look like [name].
    private val before = nameLabel()
    private val hit = nameLabel().apply { styleClass += listOf(NAME_PART_CLASS, SEARCH_HIT_CLASS) }
    private val after = nameLabel().apply { styleClass += NAME_PART_CLASS }
    private val nameLabels = listOf(name, before, hit, after)
    private val highlighted = HBox(0.0, before, hit, after).apply { alignment = Pos.CENTER_LEFT }
    private val bubbleRadius = fileIconSize.map { it * BUBBLE_SCALE }

    // One bubble per marker, reused; without markers the box takes no space.
    private val bubbleOf =
        FileMarker.entries.associateWith { marker ->
            Circle().apply {
                radiusProperty().bind(bubbleRadius)
                styleClass += listOf(FILE_MARKER_CLASS, marker.bubbleStyleClass)
            }
        }
    private val bubbles =
        HBox(BUBBLE_GAP).apply {
            alignment = Pos.CENTER_LEFT
            managedProperty().bind(visibleProperty())
        }
    private val content =
        HBox(icon, name, bubbles).apply {
            styleClass += "explorer-entry"
            alignment = Pos.CENTER_LEFT
            spacingProperty().bind(this@PathCell.graphicTextGapProperty())
        }
    private val markerUpdater = InvalidationListener { updateMarkers() }
    private val dropTargetUpdater = InvalidationListener { updateDropTarget() }
    private val cutUpdater = InvalidationListener { updateCut() }

    /** The marker colouring the name, if any; not shown on the selection bar, where it would be hard to read. */
    private var nameMarker: FileMarker? = null

    init {
        // The text stays set (accessibility, lookups by name) but only the graphic is shown.
        contentDisplay = ContentDisplay.GRAPHIC_ONLY
        markerChanges.addListener(WeakInvalidationListener(markerUpdater))
        libraryReadOnly.addListener(WeakInvalidationListener(decorationUpdater))
        dropTarget.addListener(WeakInvalidationListener(dropTargetUpdater))
        cutChanges.addListener(WeakInvalidationListener(cutUpdater))
        selectedProperty().addListener { _ -> updateNameColour() }
        // Folders open and close, and readability is checked later or reset, without a call to updateItem.
        treeItemProperty().addListener { _, old, new ->
            (old as? FileTreeItem)?.let {
                it.expandedProperty().removeListener(iconUpdater)
                it.readable.removeListener(iconUpdater)
                it.libraryKindProperty.removeListener(decorationUpdater)
                it.generatedReasonProperty.removeListener(decorationUpdater)
            }
            (new as? FileTreeItem)?.let {
                it.expandedProperty().addListener(iconUpdater)
                it.readable.addListener(iconUpdater)
                it.libraryKindProperty.addListener(decorationUpdater)
                it.generatedReasonProperty.addListener(decorationUpdater)
            }
        }
        addEventHandler(ContextMenuEvent.CONTEXT_MENU_REQUESTED) {
            if (!isEmpty) onMenuRequested(treeItem as? FileTreeItem)
        }
        setOnMouseClicked { event ->
            val path = item
            if (event.button == MouseButton.PRIMARY && event.clickCount == 2 && !isEmpty && path != null) {
                open(path)
            }
        }
    }

    override fun updateItem(
        item: Path?,
        empty: Boolean,
    ) {
        super.updateItem(item, empty)
        val entry = treeItem as? FileTreeItem
        text = if (empty || item == null) null else entry?.displayName ?: (item.fileName ?: item).toString()
        name.text = text
        updateHighlight()
        updateTooltip()
        updateIcon()
        updateMarkers()
        updateDropTarget()
        updateCut()
    }

    private fun nameLabel() =
        Label().apply {
            fontProperty().bind(this@PathCell.fontProperty())
            textFillProperty().bind(this@PathCell.textFillProperty())
        }

    // Shows [name], or the split name with the hit highlighted while the applied search matches this row's name.
    private fun updateHighlight() {
        val shown = text
        val range = shown?.let { highlightRange(it) }
        if (shown != null && range != null) {
            before.text = shown.substring(0, range.first)
            hit.text = shown.substring(range.first, range.last + 1)
            after.text = shown.substring(range.last + 1)
        }
        val wanted = if (range == null) name else highlighted
        val other = if (range == null) highlighted else name
        val index = content.children.indexOf(other)
        if (index >= 0) content.children[index] = wanted
    }

    private fun highlightRange(shown: String): IntRange? {
        val query = searchQuery() ?: return null
        val entry = treeItem as? FileTreeItem ?: return null
        val top = generateSequence<TreeItem<Path>>(entry) { it.parent }.firstOrNull { it.parent?.parent == null }
        val relative = top?.value?.relativize(entry.value) ?: return null
        return query.highlight(shown, relative.joinToString("/") { it.toString() })
    }

    private fun updateTooltip() {
        val entry = treeItem as? FileTreeItem
        val item: Path? = item
        if (isEmpty || item == null || entry == null) {
            tooltip = null
            return
        }
        val library = libraryTooltip(entry.libraryKind, libraryReadOnly.value, entry.parent !is FileTreeItem)
        val generated = generatedTooltip(entry.generatedReason)
        // Labelled and compacted rows show more or other than the name: the tooltip has the full path.
        val path = if (entry.label != null || entry.isCompacted) item.toString() else null
        val lines = listOfNotNull(path, library, generated)
        tooltip =
            when {
                library != null -> libraryTooltip.also { it.text = lines.joinToString("\n") }
                generated != null -> generatedFolderTooltip.also { it.text = lines.joinToString("\n") }
                path != null -> pathTooltip.also { it.text = path }
                else -> null
            }
    }

    private fun updateDropTarget() {
        pseudoClassStateChanged(DROP_TARGET, !isEmpty && treeItem != null && treeItem === dropTarget.value)
    }

    private fun updateCut() {
        // A compacted row is cut as its first folder.
        val path = (treeItem as? FileTreeItem)?.sourcePath ?: item
        pseudoClassStateChanged(CUT, !isEmpty && path != null && isCut(path))
    }

    /** A copy of this row's icon, same glyph, colour class and size, e.g. for a drag image; `null` without one. */
    internal fun iconCopy(): FontIcon? =
        if (graphic == null) {
            null
        } else {
            FontIcon().apply {
                iconCode = icon.iconCode
                styleClass.setAll(icon.styleClass)
                iconSize = icon.iconSize
            }
        }

    private fun updateIcon() {
        val treeItem: TreeItem<Path>? = treeItem
        if (isEmpty || treeItem !is FileTreeItem) {
            graphic = null
            return
        }
        val fileIcon = treeItem.fileIcon(libraryReadOnly.value, canOpenFile)
        icon.show(fileIcon)
        graphic = content
    }

    private fun updateMarkers() {
        val treeItem: TreeItem<Path>? = treeItem
        val shown =
            if (isEmpty || treeItem !is FileTreeItem) {
                FileMarkers.NONE
            } else {
                markers(treeItem.value, treeItem.isDirectory)
            }
        bubbles.children.setAll(shown.bubbles.distinct().map(bubbleOf::getValue))
        bubbles.isVisible = shown.bubbles.isNotEmpty()
        nameMarker = shown.name
        updateNameColour()
    }

    // CSS cannot set a bound property, so a coloured name is unbound from the cell's text fill.
    private fun updateNameColour() {
        val marker = nameMarker
        nameLabels.forEach { label ->
            label.styleClass.removeAll(nameStyleClasses)
            if (marker == null || isSelected) {
                label.textFillProperty().bind(textFillProperty())
            } else {
                label.textFillProperty().unbind()
                label.styleClass += marker.nameStyleClass
            }
        }
    }
}

private const val FILE_MARKER_CLASS = "file-marker"
private const val SEARCH_HIT_CLASS = "explorer-search-hit"
private const val NAME_PART_CLASS = "explorer-name-part"
private val DROP_TARGET = PseudoClass.getPseudoClass("drop-target")
private val CUT = PseudoClass.getPseudoClass("cut")
private val nameStyleClasses = FileMarker.entries.map(FileMarker::nameStyleClass)

// Bubble radius relative to the icon size, and the gap between bubbles in pixels.
private const val BUBBLE_SCALE = 0.2
private const val BUBBLE_GAP = 3.0
