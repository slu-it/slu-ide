package dev.sluide.previews.preview

import javafx.scene.control.Alert
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.stage.Window
import java.nio.file.Path

/**
 * Called by the page's script: `sluideBridge.open(href)` when a link is clicked, `sluideBridge.openNode(id)` when a
 * linked node of a diagram is clicked (`data-node-links`), `sluideBridge.zoomed` when the zoom changed. Public members
 * only, as the page's script reaches them by reflection.
 */
internal class PageBridge(
    private val openLink: (String) -> Unit,
    private val nodeClicked: (String) -> Unit,
    private val zoomChanged: (Double, Boolean) -> Unit,
) {
    fun open(href: String) = openLink(href)

    fun openNode(id: String) = nodeClicked(id)

    fun zoomed(
        scale: Double,
        fitted: Boolean,
    ) = zoomChanged(scale, fitted)
}

/** Asks, owned by [owner], whether to run the scripts of [file], which can read the files in [root]; blocks. */
internal fun showScriptsWarning(
    root: Path,
    file: Path,
    owner: Window?,
): Boolean {
    val run = ButtonType("Run Scripts", ButtonBar.ButtonData.OK_DONE)
    val alert =
        Alert(
            Alert.AlertType.WARNING,
            "The page's scripts can read the files in $root (except hidden ones) and send them to the web. " +
                "Run them only for pages you trust.",
            run,
            ButtonType.CANCEL,
        ).apply {
            owner?.let(::initOwner)
            title = "Run Scripts"
            headerText = "Run the scripts of ${file.fileName}?"
        }
    return alert.showAndWait().orElse(ButtonType.CANCEL) == run
}
