package dev.sluide.ide

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.backgroundtasks.BackgroundTasksView
import dev.sluide.fileexplorer.FileExplorerView
import javafx.beans.InvalidationListener
import javafx.beans.Observable
import javafx.scene.control.SplitPane
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox

/**
 * The left part of the main window: the file explorer, and below it the background tasks running for a while
 * (ADR 0061), which take no room while there are none.
 */
internal fun leftPane(
    fileExplorer: FileExplorerView,
    backgroundTasks: BackgroundTasks,
): VBox =
    VBox(fileExplorer, BackgroundTasksView(backgroundTasks)).apply {
        VBox.setVgrow(fileExplorer, Priority.ALWAYS)
    }

/**
 * Gives the explorer, the left part of [split], its start width ([FileExplorerView.startWidth]), but at most half of a
 * narrow window, at once or, before the window is laid out, as soon as it is.
 */
internal fun fitExplorer(
    split: SplitPane,
    fileExplorer: FileExplorerView,
) {
    // The position is that of the divider's middle. Views in the editor area may have split panes of their own.
    fun fit() {
        val divider = split.lookupAll(".split-pane-divider").firstOrNull { it.parent === split }
        val width = minOf(fileExplorer.startWidth(), split.width / 2)
        split.setDividerPositions((width + (divider?.prefWidth(-1.0) ?: 0.0) / 2) / split.width)
    }
    if (split.width > 0) {
        fit()
        return
    }
    split.widthProperty().addListener(
        object : InvalidationListener {
            override fun invalidated(observable: Observable) {
                if (split.width <= 0) return
                split.widthProperty().removeListener(this)
                fit()
            }
        },
    )
}
