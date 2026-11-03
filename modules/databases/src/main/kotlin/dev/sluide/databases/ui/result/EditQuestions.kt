package dev.sluide.databases.ui.result

import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.stage.Window

/** The questions of the result grid that guard against losing data; Cancel is the default button. */
internal object EditQuestions {
    /** Asks [text] with [verb] and Cancel; true only for [verb]. */
    fun ask(
        owner: Window?,
        title: String,
        text: String,
        verb: String,
    ): Boolean {
        val yes = ButtonType(verb, ButtonBar.ButtonData.OK_DONE)
        val alert =
            Alert(Alert.AlertType.WARNING, text, yes, ButtonType.CANCEL).apply {
                if (owner != null) initOwner(owner)
                this.title = title
                headerText = null
            }
        (alert.dialogPane.lookupButton(yes) as Button).isDefaultButton = false
        (alert.dialogPane.lookupButton(ButtonType.CANCEL) as Button).isDefaultButton = true
        return alert.showAndWait().orElse(ButtonType.CANCEL) == yes
    }

    fun discard(
        owner: Window?,
        tab: String,
    ): Boolean = ask(owner, "Discard Edits", "Discard the unsubmitted edits of $tab?", "Discard Edits")

    fun delete(
        owner: Window?,
        count: Int,
        table: String,
    ): Boolean =
        ask(
            owner,
            "Delete Rows",
            "Delete $count ${if (count == 1) "row" else "rows"} from $table? They are deleted permanently.",
            "Delete",
        )
}
