package dev.sluide.databases.ui.console

import dev.sluide.databases.connect.CommitMode
import dev.sluide.databases.ui.ConnectionEntry
import dev.sluide.databases.ui.DatabasesTabModel
import javafx.beans.InvalidationListener
import javafx.beans.binding.Bindings
import javafx.geometry.Orientation
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.Separator
import javafx.scene.control.ToggleButton
import javafx.scene.control.ToggleGroup
import javafx.scene.control.Tooltip
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import org.kordamp.ikonli.Ikon
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignC
import org.kordamp.ikonli.materialdesign2.MaterialDesignH
import org.kordamp.ikonli.materialdesign2.MaterialDesignP
import org.kordamp.ikonli.materialdesign2.MaterialDesignS
import org.kordamp.ikonli.materialdesign2.MaterialDesignU

/** What the toolbar's buttons do. */
internal class ConsoleActions(
    val run: () -> Unit,
    val cancel: () -> Unit,
    val commit: () -> Unit,
    val rollback: () -> Unit,
    val history: () -> Unit,
)

/** The toolbar over a console's editor: Run, Cancel, the transaction controls, History and the default schema. */
internal class ConsoleToolbar(
    private val entry: ConnectionEntry,
    private val model: DatabasesTabModel,
    actions: ConsoleActions,
    private val gate: RunGate = RunGate(),
) {
    private fun icon(
        ikon: Ikon,
        tip: String,
        action: () -> Unit,
    ) = Button().apply {
        graphic = FontIcon(ikon)
        tooltip = Tooltip(tip)
        isFocusTraversable = false
        setOnAction { action() }
    }

    val run = icon(MaterialDesignP.PLAY, "Run (Shortcut+Enter)", actions.run)
    val cancel = icon(MaterialDesignS.STOP, "Cancel (Shortcut+F2)", actions.cancel)
    val commit = icon(MaterialDesignC.CHECK_ALL, "Commit (Shortcut+Alt+Shift+Enter)", actions.commit)
    val rollback = icon(MaterialDesignU.UNDO_VARIANT, "Roll Back", actions.rollback)
    val history = icon(MaterialDesignH.HISTORY, "History (Shortcut+Alt+E)", actions.history)

    private val group = ToggleGroup()
    val auto = modeButton("Auto", CommitMode.AUTO)
    val manual = modeButton("Manual", CommitMode.MANUAL)

    private val schema =
        Label().apply {
            styleClass += "console-schema"
            textProperty().bind(entry.metadata.defaultSchema)
        }

    val node: HBox =
        HBox(
            4.0,
            run,
            cancel,
            separator(),
            // The mode toggle sits with the transaction buttons: mode controls belong to the transaction; the far-right
            // rule of docs/ui-conventions.md is for view-mode toggles.
            HBox(auto, manual).apply { styleClass += "console-mode" },
            commit,
            rollback,
            separator(),
            history,
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            schema,
        ).apply {
            styleClass += "console-toolbar"
            alignment = Pos.CENTER_LEFT
        }

    private var syncing = false
    private val modeListener = InvalidationListener { sync() }

    init {
        run.disableProperty().bind(entry.running.or(gate.busy))
        cancel.disableProperty().bind(entry.running.not())
        commit.disableProperty().bind(entry.pendingTransaction.not())
        rollback.disableProperty().bind(entry.pendingTransaction.not())
        schema.visibleProperty().bind(Bindings.isNotEmpty(entry.metadata.defaultSchema))
        sync()
        entry.commitMode.addListener(modeListener)
        group.selectedToggleProperty().addListener { _, _, selected ->
            if (syncing) return@addListener
            if (selected == null) {
                // A click on the selected button deselects it: keep the mode.
                sync()
                return@addListener
            }
            model.setCommitMode(entry, selected.userData as CommitMode)
            // Asked and cancelled, or still switching: the toggle shows what the session has.
            sync()
        }
    }

    private fun modeButton(
        title: String,
        mode: CommitMode,
    ) = ToggleButton(title).apply {
        toggleGroup = group
        userData = mode
        isFocusTraversable = false
        styleClass += "console-mode-button"
    }

    private fun separator() = Separator(Orientation.VERTICAL)

    /** The console is closed: lets go of the entry's properties, which outlive it. */
    fun dispose() {
        entry.commitMode.removeListener(modeListener)
        listOf(run, cancel, commit, rollback).forEach { it.disableProperty().unbind() }
        schema.textProperty().unbind()
        schema.visibleProperty().unbind()
    }

    /** Shows the entry's commit mode in the toggle. */
    private fun sync() {
        syncing = true
        try {
            (if (entry.commitMode.get() == CommitMode.AUTO) auto else manual).isSelected = true
        } finally {
            syncing = false
        }
    }
}
