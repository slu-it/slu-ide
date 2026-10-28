package dev.sluide.gitlog.operations

import dev.sluide.git.GitMergeMode
import dev.sluide.git.defaultMergeMessage
import dev.sluide.gitlog.ui.GIT_LOG_CSS
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.RadioButton
import javafx.scene.control.ToggleGroup
import javafx.scene.layout.HBox
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.stage.Window
import javafx.util.StringConverter

internal val PULL_BUTTON = ButtonType("Pull", ButtonBar.ButtonData.OK_DONE)

/** Says why nothing can be pulled ([text]) over the Push dialog ([owner]), which covers the header (ADR 0106). */
internal fun pullRefusedAlert(
    owner: Window?,
    text: String,
): Alert =
    Alert(Alert.AlertType.INFORMATION).apply {
        owner?.let(::initOwner)
        title = "Pull"
        headerText = text
        // The alert's own content label would leave an empty, padded band.
        dialogPane.content =
            Region().apply {
                minHeight = 0.0
                prefHeight = 0.0
                maxHeight = 0.0
            }
    }

/**
 * How to pull, as the Pull dialog was answered (ADR 0106): from the remote-tracking branch [ref] (a full refname),
 * merged in [mode] with [message] (`null`: git's own) or rebased (`mode == null`), local changes stashed if
 * [autostash].
 */
internal data class PullChoice(
    val ref: String,
    val mode: GitMergeMode?,
    val message: String?,
    val autostash: Boolean,
)

/**
 * Asks how to pull into the current branch of [prompt] (ADR 0106): "From:" one of its remote branches (short names; the
 * preselected one, else none and Pull disabled), Merge with the options of a merge ([MergeOptions] without its stash
 * checkbox, disabled while Rebase is chosen, its message git's for the branch chosen unless edited) or Rebase (chosen
 * at first, every time), "Stash local changes and restore them afterwards" (checked; not for a squash), then Pull (the
 * default) or Cancel. The result is what was chosen.
 */
internal class PullDialog(
    owner: Window?,
    prompt: PullPrompt,
) : Dialog<PullChoice>() {
    val from =
        ComboBox<String>().apply {
            styleClass += "git-pull-from"
            items.setAll(prompt.remoteBranches)
            converter =
                object : StringConverter<String>() {
                    override fun toString(ref: String?) = ref?.removePrefix("refs/remotes/").orEmpty()

                    override fun fromString(text: String?) = null
                }
            value = prompt.preselected
        }

    private val ways = ToggleGroup()

    val merge = way("Merge", "git-pull-merge")
    val rebase = way("Rebase", "git-pull-rebase")

    val options = MergeOptions(prompt.preselected?.let { defaultMergeMessage(it, prompt.branch) }.orEmpty(), false)

    val autostash =
        CheckBox("Stash local changes and restore them afterwards").apply {
            styleClass += "git-pull-autostash"
            isSelected = true
        }

    // Whether to stash as chosen, kept while a squash shows the box unchecked.
    private var stashChosen = true

    init {
        owner?.let(::initOwner)
        title = "Pull"
        headerText = "Pull into '${prompt.branch}'"
        isResizable = true
        dialogPane.styleClass += "git-pull"
        dialogPane.stylesheets += GIT_LOG_CSS
        dialogPane.buttonTypes.setAll(PULL_BUTTON, ButtonType.CANCEL)
        options.node.padding = Insets(0.0, 0.0, 0.0, INDENT)
        dialogPane.content =
            VBox(
                SPACING,
                HBox(SPACING, Label("From:"), from).apply { alignment = Pos.CENTER_LEFT },
                merge,
                options.node,
                rebase,
                autostash,
            ).apply { styleClass += "git-pull-options" }
        rebase.isSelected = true
        options.node.disableProperty().bind(rebase.selectedProperty())
        ways.selectedToggleProperty().subscribe { _ -> updateStash() }
        options.modes.selectedToggleProperty().subscribe { _ -> updateStash() }
        from.valueProperty().subscribe { _, ref ->
            if (ref != null) options.defaultMessage(defaultMergeMessage(ref, prompt.branch))
        }
        val pull = dialogPane.lookupButton(PULL_BUTTON) as Button
        pull.isDefaultButton = true
        pull.disableProperty().bind(from.valueProperty().isNull)
        setResultConverter { button -> if (button == PULL_BUTTON) choice() else null }
        setOnShown { Platform.runLater(if (prompt.preselected == null) from::requestFocus else pull::requestFocus) }
    }

    /** What was chosen; `null` without a branch. */
    private fun choice(): PullChoice? {
        val ref = from.value ?: return null
        val chosen = options.value()
        val merging = merge.isSelected
        return PullChoice(
            ref,
            chosen.mode.takeIf { merging },
            chosen.message.takeIf { merging },
            autostash.isSelected && pullStashes(merging, chosen.mode),
        )
    }

    /** The stash checkbox follows Merge or Rebase and the merge mode ([pullStashes]), keeping the user's choice. */
    private fun updateStash() {
        if (!autostash.isDisable) stashChosen = autostash.isSelected
        val stashes = pullStashes(merge.isSelected, options.mode)
        autostash.isDisable = !stashes
        autostash.isSelected = stashes && stashChosen
    }

    private fun way(
        text: String,
        styleClass: String,
    ) = RadioButton(text).apply {
        this.styleClass += styleClass
        toggleGroup = ways
    }

    private companion object {
        const val SPACING = 8.0
        const val INDENT = 24.0
    }
}
