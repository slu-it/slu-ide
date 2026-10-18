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
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.text.Font
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

/**
 * Git's own [message] in a selectable, read-only area of monospace text, as git aligns it with tabs and spaces: as wide
 * as its longest line and as high as its lines, within limits, longer lines wrapped, more lines scrolled; not squeezed
 * by the dialog.
 */
internal fun gitMessage(message: String): TextArea =
    TextArea(message).apply {
        styleClass += "git-message"
        font = Font.font(MONOSPACED, Font.getDefault().size)
        isEditable = false
        isWrapText = true
        val widths = message.lines().map { line -> line.sumOf { if (it == '\t') TAB_COLUMNS else 1 } }
        val columns = (widths.maxOrNull() ?: 0).coerceIn(MESSAGE_COLUMNS_MIN, MESSAGE_COLUMNS_MAX)
        prefColumnCount = columns
        // Wrapped at words, a long line may take a row more than its characters fill.
        val rows = widths.sumOf { maxOf(1, (it + columns - 1) / columns) } + if (widths.any { it > columns }) 1 else 0
        prefRowCount = rows.coerceIn(MESSAGE_ROWS_MIN, MESSAGE_ROWS_MAX)
        minWidth = Region.USE_PREF_SIZE
        minHeight = Region.USE_PREF_SIZE
    }

/**
 * [gitMessage] in a pane of its own: the dialog pane pads its content (style class `content`), which would pad the
 * text inside the area instead and leave less room for it.
 */
private fun messagePane(message: String) = StackPane(gitMessage(message))

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
        dialogPane.content = messagePane(message)
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
 * Asks the question of [confirmation] before its operation runs, e.g. overwriting a branch that has commits nowhere
 * else: its button or Cancel, the default. Its details, if any, in an area that scrolls when they are many; without, no
 * empty content area between the question and the buttons. Not shown yet.
 */
internal fun refConfirmation(
    owner: Window?,
    confirmation: RefConfirmation,
): Alert =
    cancelFirst(Alert(Alert.AlertType.CONFIRMATION, null, confirmation.button, ButtonType.CANCEL), owner).apply {
        title = confirmation.title
        headerText = confirmation.question
        val details = confirmation.details
        dialogPane.content =
            if (details == null) {
                // The alert's own content label would leave an empty, padded band.
                Region().apply {
                    style = "-fx-padding: 0;"
                    minHeight = 0.0
                    prefHeight = 0.0
                    maxHeight = 0.0
                }
            } else {
                val lines = details.lines()
                // In a pane of its own: the dialog pane pads its content, which would pad the text inside the area.
                VBox(
                    TextArea(details).apply {
                        styleClass += "git-confirmation-details"
                        isEditable = false
                        prefColumnCount = lines.maxOf { it.length }.coerceIn(MESSAGE_COLUMNS_MIN, MESSAGE_COLUMNS_MAX)
                        prefRowCount = lines.size.coerceIn(DELETE_ROWS_MIN, DELETE_ROWS_MAX)
                    },
                )
            }
    }

internal val OVERWRITE_BUTTON = ButtonType("Overwrite", ButtonBar.ButtonData.OK_DONE)
internal val CHECKOUT_BUTTON = ButtonType("Check Out", ButtonBar.ButtonData.OK_DONE)
internal val RESET_BUTTON = ButtonType("Reset", ButtonBar.ButtonData.OK_DONE)
internal val COMMIT_AS_ON_DISK_BUTTON = ButtonType("Commit As on Disk", ButtonBar.ButtonData.OK_DONE)
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
        dialogPane.content = messagePane(message)
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
private const val MESSAGE_COLUMNS_MIN = 40
private const val MESSAGE_COLUMNS_MAX = 100
private const val TAB_COLUMNS = 8
private const val MONOSPACED = "Monospaced"
