package dev.sluide.gitlog

import javafx.application.Platform
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.scene.layout.VBox
import javafx.stage.Window

/** What the New Branch dialog asks for. */
internal data class NewBranch(
    val name: String,
    val checkout: Boolean,
    val overwrite: Boolean,
)

/** What the New Tag dialog asks for; a blank [message] makes a lightweight tag. */
internal data class NewTag(
    val name: String,
    val message: String,
)

internal val CREATE_BUTTON = ButtonType("Create", ButtonBar.ButtonData.OK_DONE)
internal val RENAME_BUTTON = ButtonType("Rename", ButtonBar.ButtonData.OK_DONE)
internal val DELETE_BUTTON = ButtonType("Delete", ButtonBar.ButtonData.OK_DONE)
internal val FORCE_DELETE_BUTTON = ButtonType("Force Delete", ButtonBar.ButtonData.OK_DONE)

/**
 * A dialog asking for the name of a branch or tag, starting with [initial] (selected), with the [confirm] button; while
 * [problem] finds something wrong with the name, the dialog shows it below the field and cannot be confirmed (an empty
 * problem disables it without a text). Further controls go below the [problemLabel].
 */
internal abstract class RefNameDialog<T>(
    owner: Window?,
    title: String,
    initial: String,
    private val confirm: ButtonType,
) : Dialog<T>() {
    val field =
        TextField(initial).apply {
            styleClass += "git-ref-name"
            prefColumnCount = NAME_COLUMNS
        }
    val problemLabel = Label().apply { styleClass += "git-ref-name-problem" }
    protected val content = VBox(SPACING, Label("Name:"), field, problemLabel)

    init {
        owner?.let(::initOwner)
        this.title = title
        headerText = null
        dialogPane.buttonTypes.setAll(confirm, ButtonType.CANCEL)
        dialogPane.content = content
        field.textProperty().addListener { _ -> check() }
        setResultConverter { button -> if (button == confirm) result() else null }
        setOnShown {
            // Later, since the enabled default button takes the focus once the dialog is shown.
            Platform.runLater {
                field.requestFocus()
                field.selectAll()
            }
        }
    }

    /** What is wrong with the current input, `null` if nothing. */
    protected abstract fun problem(): String?

    /** What to know about the current input if it has no [problem], e.g. what overwriting a branch does. */
    protected open fun note(): String? = null

    /** The answer for the current input. */
    protected abstract fun result(): T

    /** Shows the [problem] of the current input, else its [note]; the dialog can be confirmed only without a problem. */
    protected fun check() {
        val found = problem()
        problemLabel.text = found ?: note().orEmpty()
        dialogPane.lookupButton(confirm).isDisable = found != null
    }
}

/**
 * Asks for a new branch's name, whether to check it out (at first yes) and whether to overwrite an existing branch of
 * that name (at first no). [validate] finds what is wrong with a name for that overwrite choice, [note] what else to
 * know about it.
 */
internal class NewBranchDialog(
    owner: Window?,
    private val validate: (name: String, overwrite: Boolean) -> String?,
    private val note: (name: String, overwrite: Boolean) -> String? = { _, _ -> null },
) : RefNameDialog<NewBranch>(owner, "New Branch", "", CREATE_BUTTON) {
    val checkout =
        CheckBox("Checkout branch").apply {
            styleClass += "git-ref-checkout"
            isSelected = true
        }
    val overwrite = CheckBox("Overwrite existing branch").apply { styleClass += "git-ref-overwrite" }

    init {
        content.children += listOf(checkout, overwrite)
        overwrite.selectedProperty().addListener { _ -> check() }
        check()
    }

    override fun problem() = validate(field.text, overwrite.isSelected)

    override fun note() = note(field.text, overwrite.isSelected)

    override fun result() = NewBranch(field.text, checkout.isSelected, overwrite.isSelected)
}

/** Asks for a new tag's name and an optional message, which makes it an annotated tag. */
internal class NewTagDialog(
    owner: Window?,
    private val validate: (String) -> String?,
) : RefNameDialog<NewTag>(owner, "New Tag", "", CREATE_BUTTON) {
    val message =
        TextArea().apply {
            styleClass += "git-ref-message"
            prefRowCount = MESSAGE_ROWS
            prefColumnCount = NAME_COLUMNS
        }

    init {
        content.children += listOf(Label("Message (optional):"), message)
        check()
    }

    override fun problem() = validate(field.text)

    override fun result() = NewTag(field.text, message.text)
}

/** Asks for a branch's new name, starting with its [old] one. */
internal class RenameBranchDialog(
    owner: Window?,
    old: String,
    private val validate: (String) -> String?,
) : RefNameDialog<String>(owner, "Rename Branch", old, RENAME_BUTTON) {
    init {
        check()
    }

    override fun problem() = validate(field.text)

    override fun result(): String = field.text
}

/** Git's own [message] in a selectable, read-only area of monospace text, as git aligns it with tabs and spaces. */
internal fun gitMessage(message: String): TextArea =
    TextArea(message).apply {
        styleClass += "git-message"
        isEditable = false
        isWrapText = false
        prefRowCount = message.lines().size.coerceIn(MESSAGE_ROWS_MIN, MESSAGE_ROWS_MAX)
        prefColumnCount = MESSAGE_COLUMNS
    }

/** An error dialog titled [title] that says [header] above git's [message]; not shown yet. */
internal fun gitErrorDialog(
    owner: Window?,
    title: String,
    header: String,
    message: String,
): Alert =
    Alert(Alert.AlertType.ERROR).apply {
        owner?.let(::initOwner)
        this.title = title
        headerText = header
        dialogPane.content = gitMessage(message)
        dialogPane.stylesheets += GIT_LOG_CSS
    }

/**
 * Asks [question] before deleting refs, listing their [names] ("branch x", "tag v1") one per line in an area that
 * scrolls when they are many; Cancel is the default button and has the focus, so Enter deletes nothing. Not shown yet.
 */
internal fun deleteRefsConfirmation(
    owner: Window?,
    question: String,
    names: List<String>,
): Alert =
    cancelFirst(Alert(Alert.AlertType.CONFIRMATION, null, DELETE_BUTTON, ButtonType.CANCEL), owner).apply {
        headerText = question
        val list =
            TextArea(names.joinToString("\n")).apply {
                styleClass += "git-delete-names"
                isEditable = false
                prefColumnCount = NAME_COLUMNS
                prefRowCount = names.size.coerceIn(DELETE_ROWS_MIN, DELETE_ROWS_MAX)
            }
        // In a pane of its own: the dialog pane pads its content, which would pad the text inside the area.
        dialogPane.content = VBox(list)
    }

/**
 * Asks [question] before overwriting a branch that has commits nowhere else (Overwrite or Cancel, the default). Not
 * shown yet.
 */
internal fun overwriteConfirmation(
    owner: Window?,
    question: String,
): Alert =
    cancelFirst(Alert(Alert.AlertType.CONFIRMATION, null, OVERWRITE_BUTTON, ButtonType.CANCEL), owner).apply {
        title = "Overwrite Branch"
        headerText = question
    }

internal val OVERWRITE_BUTTON = ButtonType("Overwrite", ButtonBar.ButtonData.OK_DONE)
private const val DELETE_ROWS_MIN = 2
private const val DELETE_ROWS_MAX = 10

/**
 * Asks [question] before deleting branches with force that git refused to delete, with git's [message]; Cancel is the
 * default button. Not shown yet.
 */
internal fun forceDeleteConfirmation(
    owner: Window?,
    question: String,
    message: String,
): Alert =
    cancelFirst(Alert(Alert.AlertType.CONFIRMATION, null, FORCE_DELETE_BUTTON, ButtonType.CANCEL), owner).apply {
        headerText = question
        dialogPane.content = gitMessage(message)
        dialogPane.stylesheets += GIT_LOG_CSS
    }

/** [alert], titled Delete, with Cancel as the default button, focused once shown. */
private fun cancelFirst(
    alert: Alert,
    owner: Window?,
): Alert =
    alert.apply {
        owner?.let(::initOwner)
        title = "Delete"
        val cancel = dialogPane.lookupButton(ButtonType.CANCEL) as Button
        dialogPane.buttonTypes
            .filter { it != ButtonType.CANCEL }
            .forEach { (dialogPane.lookupButton(it) as Button).isDefaultButton = false }
        cancel.isDefaultButton = true
        // Later, since the dialog focuses a button of its own once shown.
        setOnShown { Platform.runLater(cancel::requestFocus) }
    }

private const val NAME_COLUMNS = 30
private const val SPACING = 6.0
private const val MESSAGE_ROWS = 5
private const val MESSAGE_ROWS_MIN = 2
private const val MESSAGE_ROWS_MAX = 12
private const val MESSAGE_COLUMNS = 72
