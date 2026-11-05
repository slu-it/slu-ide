package dev.sluide.previews.preview

import javafx.beans.binding.Bindings
import javafx.beans.property.BooleanProperty
import javafx.beans.property.ObjectProperty
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.ToggleButton
import javafx.scene.control.ToggleGroup
import javafx.scene.control.Tooltip
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.web.WebView

/**
 * The toolbar of a [PreviewFileView]: the mode buttons ([buttons], one per [PreviewMode], in [modes]) on the right and
 * on the left what the [preview] has: the scripts toggle, the table of contents' button, the zoom controls of an image
 * or diagram that [zooms][Preview.zooms], the fold controls and the Copy Formatted button. Items the source's editor
 * extensions add ([contributed]) sit right after them, in every mode. On the right, before the mode buttons, come the
 * preview's own [actions][Preview.actions] ([actionButtons]), the Export button and, for every other preview, its
 * [zoom] controls (a document's, ADR 0083). Actions, Export and zoom show like the zoom controls on the left only
 * while the preview shows, so not in Source. Everything runs on the FX thread.
 */
internal class PreviewToolbar(
    preview: Preview,
    modes: ToggleGroup,
    mode: ObjectProperty<PreviewMode>,
    scripts: BooleanProperty,
    toc: TableOfContents?,
    export: ExportControls?,
    zoom: ZoomControls,
    folds: FoldControls?,
    copy: CopyFormattedControls?,
    webView: WebView,
    softWrap: Node,
    contributed: ObservableList<Node> = FXCollections.emptyObservableList(),
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

    val actionButtons =
        preview.actions.map { action ->
            Button(action.label).apply {
                styleClass += "preview-action"
                tooltip = Tooltip(action.tooltip)
                isFocusTraversable = false
                disableProperty().bind(action.disabled)
                visibleProperty().bind(mode.map { it != PreviewMode.SOURCE })
                managedProperty().bind(visibleProperty())
                setOnAction { action.run() }
                HBox.setMargin(this, Insets(0.0, 6.0, 0.0, 0.0))
            }
        }

    /**
     * The items editor extensions added to the source ([contributed]), after the preview's own left controls. A group of
     * its own, set apart by a wider gap; without items it takes no room.
     */
    private val contributedBox =
        HBox().apply {
            styleClass += "preview-contributed"
            alignment = Pos.CENTER_LEFT
            Bindings.bindContent(children, contributed)
            visibleProperty().bind(Bindings.isNotEmpty(children))
            managedProperty().bind(visibleProperty())
            HBox.setMargin(this, Insets(0.0, 0.0, 0.0, GROUP_GAP))
        }

    val node: Node

    init {
        val spacer = Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }
        export?.button?.let {
            it.visibleProperty().bind(mode.map { m -> m != PreviewMode.SOURCE })
            it.managedProperty().bind(it.visibleProperty())
        }
        softWrap.visibleProperty().bind(mode.map { it != PreviewMode.PREVIEW })
        softWrap.managedProperty().bind(softWrap.visibleProperty())
        val extras =
            listOf<Node>(softWrap) +
                if (preview.offersScripts) {
                    listOf(
                        ToggleButton("Scripts").apply {
                            styleClass += "preview-scripts"
                            tooltip = Tooltip("Run the page's scripts. They can read the files served with the page.")
                            selectedProperty().bindBidirectional(scripts)
                        },
                        contributedBox,
                    )
                } else if (toc != null) {
                    listOf(toc.button, contributedBox)
                } else if (preview.zooms) {
                    listOf(zoom.node, contributedBox)
                } else if (folds != null) {
                    folds.node.visibleProperty().bind(mode.map { it != PreviewMode.SOURCE })
                    folds.node.managedProperty().bind(folds.node.visibleProperty())
                    copy?.node?.let { copyNode ->
                        copyNode.visibleProperty().bind(mode.map { it != PreviewMode.SOURCE })
                        copyNode.managedProperty().bind(copyNode.visibleProperty())
                    }
                    listOfNotNull(folds.node, copy?.node, contributedBox)
                } else {
                    listOf(contributedBox)
                }
        zoom.node.visibleProperty().bind(mode.map { it != PreviewMode.SOURCE })
        zoom.node.managedProperty().bind(zoom.node.visibleProperty())
        zoom.install(webView)
        // A document's zoom sits on the right: the left holds the preview's own buttons.
        val documentZoom =
            if (preview.zooms) {
                emptyList()
            } else {
                HBox.setMargin(zoom.node, Insets(0.0, 6.0, 0.0, 0.0))
                listOf(zoom.node)
            }
        val right = actionButtons + listOfNotNull(export?.button) + documentZoom + buttons
        node =
            HBox(*(extras + spacer + right).toTypedArray()).apply {
                styleClass += "preview-toolbar"
                alignment = Pos.CENTER_RIGHT
            }
    }

    private companion object {
        /** Between groups of left controls, e.g. the table of contents and Read Aloud; twice the gap within a group. */
        const val GROUP_GAP = 12.0
    }
}
