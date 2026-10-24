package dev.sluide.commandruns

import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.control.Tooltip
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox

/**
 * The run pane of a view that runs commands (ADR 0088): the arguments of every run (prompt [argumentsPrompt]), the
 * status line with Stop and Rerun, and the terminal of the current or last run ([placeholder] before the first). FX
 * thread.
 */
class RunPane(
    argumentsPrompt: String,
    placeholder: String,
) {
    /** Options added to every run of the view, e.g. `--info -x test`. */
    val arguments =
        TextField().apply {
            promptText = argumentsPrompt
            styleClass += "run-arguments"
            HBox.setHgrow(this, Priority.ALWAYS)
        }

    /** Takes the width the buttons leave; cut text shows whole in its tooltip. */
    val status =
        Label().apply {
            styleClass += "run-status"
            minWidth = 0.0
            maxWidth = Double.MAX_VALUE
            HBox.setHgrow(this, Priority.ALWAYS)
            val text = textProperty()
            tooltip = Tooltip().apply { textProperty().bind(text) }
        }

    val stop = Button("Stop").apply { isDisable = true }

    val rerun = Button("Rerun").apply { isDisable = true }

    init {
        // A long status line is cut, not the buttons.
        for (button in listOf(stop, rerun)) button.minWidth = Region.USE_PREF_SIZE
    }

    private val placeholderLabel = Label(placeholder).apply { styleClass += "run-placeholder" }

    private val slot = StackPane(placeholderLabel).apply { styleClass += "run-terminal-slot" }

    val node =
        BorderPane(slot).apply {
            styleClass += "run-pane"
            top =
                VBox(
                    HBox(arguments).apply { styleClass += "run-row" },
                    HBox(status, stop, rerun).apply {
                        styleClass += "run-row"
                        alignment = Pos.CENTER_LEFT
                    },
                ).apply { styleClass += "run-bar" }
            minHeight = 0.0
            stylesheets += checkNotNull(RunPane::class.java.getResource(CSS)).toExternalForm()
        }

    /** The node shown in the terminal's place now. */
    val shownOutput: Node get() = slot.children.single()

    /** Shows [terminal] in place of the previous one. */
    fun showTerminal(terminal: Node) {
        slot.children.setAll(terminal)
    }

    /** Shows [text] in the status line, coloured by [status], or plain. */
    fun showStatus(
        text: String,
        status: RunStatus?,
    ) {
        this.status.text = text
        this.status.styleClass.removeAll(STATUS_STYLES.values)
        status?.let { this.status.styleClass += STATUS_STYLES.getValue(it) }
    }

    internal companion object {
        const val CSS = "/dev/sluide/commandruns/run-pane.css"

        /** The style class of the status line per status: `run-status-running` and so on. */
        val STATUS_STYLES = RunStatus.entries.associateWith { "run-status-${it.name.lowercase()}" }
    }
}
