package dev.sluide.gitlog.operations

import dev.sluide.git.GitMergeMode
import javafx.scene.control.CheckBox
import javafx.scene.control.RadioButton
import javafx.scene.control.TextArea
import javafx.scene.control.ToggleGroup
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.scene.text.Font

/** How to merge, as the merge options were chosen (ADR 0105): [message] `null` for git's own. */
internal data class MergeChoice(
    val mode: GitMergeMode,
    val message: String?,
    val autostash: Boolean,
)

/** Whether a merge in [mode] takes a message: not when it only fast-forwards or squashes. */
internal fun takesMessage(mode: GitMergeMode) = mode == GitMergeMode.FAST_FORWARD || mode == GitMergeMode.MERGE_COMMIT

/**
 * Whether a merge in [mode] may stash local changes: not a squash, after which git would keep them stashed until the
 * next commit (git 2.53); local changes the squash does not touch stay as they are anyway (ADR 0105).
 */
internal fun stashes(mode: GitMergeMode) = mode != GitMergeMode.SQUASH

/**
 * The message to give git for a merge in [mode] whose message area held [edited], prefilled with [default]: `null` (git
 * makes its own, with its configuration such as `merge.log`) unless the mode takes one and the text was changed other
 * than in leading or trailing blank lines or trailing spaces, and is not blank.
 */
internal fun mergeMessage(
    default: String,
    edited: String,
    mode: GitMergeMode,
): String? = edited.takeIf { takesMessage(mode) && canChangeMessage(default, it) }

/**
 * The text of the message area when its default message goes from [oldDefault] to [newDefault] (the Pull dialog's
 * branch changed, ADR 0106): the new default, unless the user changed the [text] (as [mergeMessage] tells it).
 */
internal fun updatedMessage(
    oldDefault: String,
    newDefault: String,
    text: String,
): String = if (canChangeMessage(oldDefault, text)) text else newDefault

/**
 * The options of a merge (ADR 0105): Fast-forward if possible (at first), Always create a merge commit, Fast-forward
 * only and Squash, the message (prefilled with [defaultMessage], in a monospace font; disabled where the mode takes
 * none) and, [withAutostash], "Stash local changes and restore them afterwards" (checked). In the Merge dialog, and in
 * the Pull dialog below its Merge choice, which disables the [node] as a whole, has a stash checkbox of its own and
 * changes the [defaultMessage] with the branch chosen (ADR 0106).
 * The stash checkbox is disabled and unchecked while Squash is selected; another mode brings the choice back.
 */
internal class MergeOptions(
    private var defaultMessage: String,
    withAutostash: Boolean = true,
) {
    /** The modes; the Pull dialog follows the choice. */
    internal val modes = ToggleGroup()

    val fastForward = mode("Fast-forward if possible", GitMergeMode.FAST_FORWARD)
    val mergeCommit = mode("Always create a merge commit", GitMergeMode.MERGE_COMMIT)
    val fastForwardOnly = mode("Fast-forward only", GitMergeMode.FAST_FORWARD_ONLY)
    val squash = mode("Squash", GitMergeMode.SQUASH)

    val message =
        TextArea(defaultMessage).apply {
            styleClass += "git-merge-message"
            font = Font.font(MONOSPACED, Font.getDefault().size)
            isWrapText = false
            prefRowCount = MESSAGE_ROWS
            prefColumnCount = MESSAGE_COLUMNS
            // The dialog would otherwise squeeze it to one line.
            minHeight = Region.USE_PREF_SIZE
        }

    val autostash =
        CheckBox("Stash local changes and restore them afterwards").apply {
            styleClass += "git-merge-autostash"
            isSelected = true
        }

    /** The selected mode. */
    val mode: GitMergeMode get() = modes.selectedToggle?.userData as? GitMergeMode ?: GitMergeMode.FAST_FORWARD

    val node =
        VBox(SPACING, fastForward, mergeCommit, fastForwardOnly, squash, message).apply {
            styleClass += "git-merge-options"
            if (withAutostash) children += autostash
        }

    // Whether to stash as chosen, kept while a squash shows the box unchecked.
    private var stashChosen = true

    init {
        fastForward.isSelected = true
        modes.selectedToggleProperty().subscribe { _ ->
            message.isDisable = !takesMessage(mode)
            if (!autostash.isDisable) stashChosen = autostash.isSelected
            autostash.isDisable = !stashes(mode)
            autostash.isSelected = stashes(mode) && stashChosen
        }
    }

    /** Prefills the message with [message] from now on, replacing the text unless the user changed it. */
    fun defaultMessage(message: String) {
        this.message.text = updatedMessage(defaultMessage, message, this.message.text)
        defaultMessage = message
    }

    /** What was chosen: the mode, the message if it was changed and the mode takes one, and whether to stash. */
    fun value() =
        MergeChoice(mode, mergeMessage(defaultMessage, message.text, mode), autostash.isSelected && stashes(mode))

    private fun mode(
        text: String,
        mode: GitMergeMode,
    ) = RadioButton(text).apply {
        styleClass += "git-merge-${mode.name.lowercase().replace('_', '-')}"
        toggleGroup = modes
        userData = mode
    }

    private companion object {
        const val SPACING = 6.0
        const val MESSAGE_ROWS = 4
        const val MESSAGE_COLUMNS = 60
    }
}
