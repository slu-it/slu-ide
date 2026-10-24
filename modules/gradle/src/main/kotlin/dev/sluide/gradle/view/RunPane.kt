package dev.sluide.gradle.view

import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox

/**
 * The lower half of the Gradle side: the arguments of every run, the status line with Stop and Rerun, and the terminal
 * of the current or last run (a placeholder before the first). FX thread.
 */
internal class RunPane {
    /** Options added to every run of the view, e.g. `--info -x test`. */
    val arguments =
        TextField().apply {
            promptText = "Arguments, e.g. --info -x test"
            styleClass += "gradle-arguments"
            HBox.setHgrow(this, Priority.ALWAYS)
        }

    val status =
        Label().apply {
            styleClass += "gradle-status"
            minWidth = 0.0
            maxWidth = Double.MAX_VALUE
            HBox.setHgrow(this, Priority.ALWAYS)
        }

    val stop = Button("Stop").apply { isDisable = true }

    val rerun = Button("Rerun").apply { isDisable = true }

    private val placeholder =
        Label("The output of the tasks you run shows here.").apply { styleClass += "gradle-placeholder" }

    private val slot = StackPane(placeholder).apply { styleClass += "gradle-terminal-slot" }

    val node =
        BorderPane(slot).apply {
            styleClass += "gradle-run-pane"
            top =
                VBox(
                    HBox(arguments),
                    HBox(status, stop, rerun).apply { alignment = Pos.CENTER_LEFT },
                ).apply { styleClass += "gradle-run-bar" }
            minHeight = 0.0
        }

    /** The node shown in the terminal's place now. */
    val shownOutput: Node get() = slot.children.single()

    /** Shows [terminal] in place of the previous one. */
    fun showTerminal(terminal: Node) {
        slot.children.setAll(terminal)
    }

    /** Shows [text] in the status line, coloured by [style] (`gradle-status-…`), or plain. */
    fun showStatus(
        text: String,
        style: String?,
    ) {
        status.text = text
        status.styleClass.removeAll(STATUS_STYLES)
        style?.let { status.styleClass += it }
    }

    private companion object {
        val STATUS_STYLES =
            listOf("gradle-status-running", "gradle-status-finished", "gradle-status-failed", "gradle-status-stopped")
    }
}
