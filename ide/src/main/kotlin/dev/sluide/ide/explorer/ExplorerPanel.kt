package dev.sluide.ide.explorer

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.backgroundtasks.BackgroundTasksView
import dev.sluide.fileexplorer.ExplorerBar
import dev.sluide.fileexplorer.FileExplorerView
import javafx.beans.property.BooleanProperty
import javafx.beans.property.SimpleBooleanProperty
import javafx.scene.Group
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.input.KeyCode
import javafx.scene.layout.BorderPane
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignC

private const val STRIPE_WIDTH = 26.0

/**
 * The left column of the main window (ADR 0104): expanded, the file explorer under its button [bar], and below it the
 * background tasks running for a while (ADR 0061), which take no room while there are none; collapsed, a stripe.
 */
internal class ExplorerPanel(
    bar: ExplorerBar,
    private val fileExplorer: FileExplorerView,
    backgroundTasks: BackgroundTasks,
) : BorderPane() {
    /** Whether the column shows the explorer; true at the start of every window. */
    val expanded: BooleanProperty = SimpleBooleanProperty(true)

    /** Called when the column collapses while the focus is in it, to hand the focus back (the window: the editor). */
    var returnFocus: () -> Unit = {}

    /** The collapsed column; a click anywhere on it expands. */
    val stripe: Node =
        VBox(
            FontIcon(MaterialDesignC.CHEVRON_RIGHT),
            Group(Label("Explorer").apply { rotate = -90.0 }),
        ).apply {
            styleClass += "explorer-stripe"
            isFocusTraversable = true
            setOnMouseClicked { expanded.set(true) }
            setOnKeyPressed {
                if (it.code == KeyCode.ENTER || it.code == KeyCode.SPACE) {
                    expanded.set(true)
                    it.consume()
                }
            }
        }

    val content: Region =
        VBox(bar, fileExplorer, BackgroundTasksView(backgroundTasks)).apply {
            VBox.setVgrow(fileExplorer, Priority.ALWAYS)
        }

    init {
        expanded.subscribe { old, new -> if (old != new) show(new) }
        show(true)
    }

    private fun show(expanding: Boolean) {
        if (!expanding && focusIsInside()) returnFocus()
        center = if (expanding) content else stripe
        // Expanded, the divider decides the width.
        prefWidth = if (expanding) USE_COMPUTED_SIZE else STRIPE_WIDTH
        if (expanding && scene != null) {
            // The new content must be laid out before it can take the focus.
            applyCss()
            layout()
            fileExplorer.requestFocus()
        }
    }

    private fun focusIsInside(): Boolean {
        var node = scene?.focusOwner
        while (node != null) {
            if (node === this) return true
            node = node.parent
        }
        return false
    }
}
