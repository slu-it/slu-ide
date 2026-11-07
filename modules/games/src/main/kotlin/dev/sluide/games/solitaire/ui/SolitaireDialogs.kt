package dev.sluide.games.solitaire.ui

import dev.sluide.games.solitaire.Deals
import javafx.scene.control.Alert
import javafx.scene.control.Alert.AlertType
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar.ButtonData
import javafx.scene.control.ButtonType
import javafx.scene.control.TextInputDialog
import javafx.stage.Window

/** The deal number typed, if it is an integer in 1..[Deals.MAX_NUMBER] (spaces around it are ignored). */
internal fun parseDealNumber(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 1..Deals.MAX_NUMBER }

/** The questions of the Solitaire tab as real dialogs, owned by the window of [owner]. */
internal class SolitaireDialogs(
    private val owner: () -> Window?,
) {
    /** Asks with a [verb] button and Cancel (the default); true for the verb. */
    fun confirm(
        title: String,
        header: String,
        verb: String,
        content: String,
    ): Boolean {
        val confirm = ButtonType(verb, ButtonData.OK_DONE)
        val alert =
            Alert(AlertType.CONFIRMATION, content, confirm, ButtonType.CANCEL).apply {
                this.title = title
                headerText = header
                owner()?.let { initOwner(it) }
            }
        (alert.dialogPane.lookupButton(confirm) as Button).isDefaultButton = false
        (alert.dialogPane.lookupButton(ButtonType.CANCEL) as Button).isDefaultButton = true
        return alert.showAndWait().orElse(ButtonType.CANCEL) == confirm
    }

    /** Asks for a deal number; null when cancelled. OK stays disabled until the text is a valid number. */
    fun askDealNumber(): Int? {
        val play = ButtonType("Play", ButtonData.OK_DONE)
        val dialog =
            TextInputDialog().apply {
                title = "Deal Number"
                headerText = "Play which deal? (1–1,000,000)"
                dialogPane.buttonTypes.setAll(play, ButtonType.CANCEL)
                owner()?.let { initOwner(it) }
            }
        val ok = dialog.dialogPane.lookupButton(play)
        ok.isDisable = true
        dialog.editor.textProperty().addListener { _, _, text -> ok.isDisable = parseDealNumber(text) == null }
        return dialog.showAndWait().map { parseDealNumber(it) }.orElse(null)
    }

    /** Offers the next winnable deal [alternative] when [number] cannot be shown to be winnable. */
    fun askAlternative(
        number: Int,
        alternative: Int,
    ): Boolean =
        confirm(
            "Deal Number",
            "Deal #$number cannot be shown to be winnable.",
            "Play",
            "Play deal #$alternative instead?",
        )
}
