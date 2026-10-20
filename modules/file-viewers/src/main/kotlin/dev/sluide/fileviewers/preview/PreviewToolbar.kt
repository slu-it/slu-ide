package dev.sluide.fileviewers.preview

import javafx.beans.property.BooleanProperty
import javafx.beans.property.ObjectProperty
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.ToggleButton
import javafx.scene.control.ToggleGroup
import javafx.scene.control.Tooltip
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.web.WebView

/**
 * The toolbar of a [PreviewFileView]: the mode buttons ([buttons], one per [PreviewMode], in [modes]) on the right and
 * on the left what the [preview] has: the scripts toggle, the table of contents' button, the zoom controls, the fold
 * controls, the Copy Formatted button, and the Export button. Everything runs on the FX thread.
 */
internal class PreviewToolbar(
    preview: Preview,
    modes: ToggleGroup,
    mode: ObjectProperty<PreviewMode>,
    scripts: BooleanProperty,
    toc: TableOfContents?,
    export: ExportControls?,
    zoom: ZoomControls?,
    folds: FoldControls?,
    copy: CopyFormattedControls?,
    webView: WebView,
) {
    val buttons =
        PreviewMode.entries.map { mode ->
            ToggleButton(mode.label).apply {
                toggleGroup = modes
                userData = mode
                styleClass += "preview-mode"
                // A click leaves the focus where it is, e.g. in the preview, whose zoom keys keep working.
                isFocusTraversable = false
            }
        }

    val node: Node

    init {
        val extras =
            if (preview.offersScripts) {
                listOf(
                    ToggleButton("Scripts").apply {
                        styleClass += "preview-scripts"
                        tooltip = Tooltip("Run the page's scripts. They can read the files served with the page.")
                        selectedProperty().bindBidirectional(scripts)
                    },
                    Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                )
            } else if (toc != null) {
                listOfNotNull(toc.button, export?.button, Region().apply { HBox.setHgrow(this, Priority.ALWAYS) })
            } else if (zoom != null) {
                zoom.node.visibleProperty().bind(mode.map { it != PreviewMode.SOURCE })
                zoom.node.managedProperty().bind(zoom.node.visibleProperty())
                zoom.install(webView)
                listOfNotNull(zoom.node, export?.button, Region().apply { HBox.setHgrow(this, Priority.ALWAYS) })
            } else if (folds != null) {
                folds.node.visibleProperty().bind(mode.map { it != PreviewMode.SOURCE })
                folds.node.managedProperty().bind(folds.node.visibleProperty())
                copy?.node?.let { copyNode ->
                    copyNode.visibleProperty().bind(mode.map { it != PreviewMode.SOURCE })
                    copyNode.managedProperty().bind(copyNode.visibleProperty())
                }
                listOfNotNull(
                    folds.node,
                    copy?.node,
                    export?.button,
                    Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                )
            } else {
                emptyList()
            }
        node =
            HBox(*(extras + buttons).toTypedArray()).apply {
                styleClass += "preview-toolbar"
                alignment = Pos.CENTER_RIGHT
            }
    }
}
