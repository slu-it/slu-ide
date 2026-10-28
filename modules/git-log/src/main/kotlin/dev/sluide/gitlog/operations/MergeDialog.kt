package dev.sluide.gitlog.operations

import dev.sluide.gitlog.ui.GIT_LOG_CSS
import javafx.application.Platform
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.stage.Window

internal val MERGE_BUTTON = ButtonType("Merge", ButtonBar.ButtonData.OK_DONE)

/**
 * Asks how to merge [source] into [branch] (short names; ADR 0105): the [options] of a merge, with [defaultMessage] as
 * git would write it, then Merge (the default) or Cancel. The result is what was chosen.
 */
internal class MergeDialog(
    owner: Window?,
    source: String,
    branch: String,
    defaultMessage: String,
) : Dialog<MergeChoice>() {
    val options = MergeOptions(defaultMessage)

    init {
        owner?.let(::initOwner)
        title = "Merge"
        headerText = "Merge '$source' into '$branch'"
        isResizable = true
        dialogPane.styleClass += "git-merge"
        dialogPane.stylesheets += GIT_LOG_CSS
        dialogPane.buttonTypes.setAll(MERGE_BUTTON, ButtonType.CANCEL)
        dialogPane.content = options.node
        val merge = dialogPane.lookupButton(MERGE_BUTTON) as Button
        merge.isDefaultButton = true
        setResultConverter { button -> if (button == MERGE_BUTTON) options.value() else null }
        setOnShown { Platform.runLater(merge::requestFocus) }
    }
}
