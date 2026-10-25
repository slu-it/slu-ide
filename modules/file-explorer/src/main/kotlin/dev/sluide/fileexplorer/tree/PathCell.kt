package dev.sluide.fileexplorer.tree

import dev.sluide.fileexplorer.FileMarker
import dev.sluide.fileexplorer.FileMarkers
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
) : TreeCell<Path>() {
    private val pathTooltip = Tooltip()
    private val icon = FontIcon().apply { iconSizeProperty().bind(fileIconSize) }
    private val iconUpdater = InvalidationListener { updateIcon() }

    // The name is a label in the graphic, so the markers can follow it; it looks like the cell's own text unless
    // a marker colours it.
    private val name =
        Label().apply {
            fontProperty().bind(this@PathCell.fontProperty())
            textFillProperty().bind(this@PathCell.textFillProperty())
        }
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
        dropTarget.addListener(WeakInvalidationListener(dropTargetUpdater))
        cutChanges.addListener(WeakInvalidationListener(cutUpdater))
        selectedProperty().addListener { _ -> updateNameColour() }
        // Folders open and close, and readability is checked later or reset, without a call to updateItem.
        treeItemProperty().addListener { _, old, new ->
            (old as? FileTreeItem)?.let {
                it.expandedProperty().removeListener(iconUpdater)
                it.readable.removeListener(iconUpdater)
            }
            (new as? FileTreeItem)?.let {
                it.expandedProperty().addListener(iconUpdater)
                it.readable.addListener(iconUpdater)
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
        val labelled = (treeItem as? FileTreeItem)?.label
        text = if (empty || item == null) null else labelled ?: (item.fileName ?: item).toString()
        name.text = text
        tooltip = if (labelled != null && item != null) pathTooltip.also { it.text = item.toString() } else null
        updateIcon()
        updateMarkers()
        updateDropTarget()
        updateCut()
    }

    private fun updateDropTarget() {
        pseudoClassStateChanged(DROP_TARGET, !isEmpty && treeItem != null && treeItem === dropTarget.value)
    }

    private fun updateCut() {
        val path = item
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
        val fileIcon = treeItem.fileIcon(canOpenFile)
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
        name.styleClass.removeAll(nameStyleClasses)
        if (marker == null || isSelected) {
            name.textFillProperty().bind(textFillProperty())
        } else {
            name.textFillProperty().unbind()
            name.styleClass += marker.nameStyleClass
        }
    }
}

private const val FILE_MARKER_CLASS = "file-marker"
private val DROP_TARGET = PseudoClass.getPseudoClass("drop-target")
private val CUT = PseudoClass.getPseudoClass("cut")
private val nameStyleClasses = FileMarker.entries.map(FileMarker::nameStyleClass)

// Bubble radius relative to the icon size, and the gap between bubbles in pixels.
private const val BUBBLE_SCALE = 0.2
private const val BUBBLE_GAP = 3.0
