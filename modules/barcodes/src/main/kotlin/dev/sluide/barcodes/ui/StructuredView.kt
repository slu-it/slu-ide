package dev.sluide.barcodes.ui

import dev.sluide.barcodes.payload.Payload
import dev.sluide.barcodes.payload.PayloadField
import dev.sluide.barcodes.payload.Payloads
import javafx.beans.value.ObservableValue
import javafx.geometry.HPos
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.scene.control.TextInputControl
import javafx.scene.control.Tooltip
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.layout.ColumnConstraints
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import org.kordamp.ikonli.javafx.FontIcon

/**
 * The structured view of the text (ADR 0174): the kind of what the text is (Wi-Fi, contact, link, ..) and its labeled
 * fields, each selectable with a Copy button; secret values are masked until the eye is toggled. It describes the text
 * whatever its source. [show] is called on every model change; the fields are built again, and so masked again, only
 * when the text changed. The owner puts [node] into its layout only while [show] returned true.
 */
internal class StructuredView {
    private var shownText: String? = null

    private val content = VBox(4.0).apply { padding = Insets(4.0, 0.0, 4.0, 0.0) }

    /** The scrolling container; its height is limited by [limitHeight]. */
    val node: ScrollPane =
        ScrollPane(content).apply {
            id = "barcodes-structured"
            styleClass += "barcodes-structured"
            isFitToWidth = true
            hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
            minHeight = 0.0
        }

    /** Limits the height to a third of [side]; the fields scroll beyond that. */
    fun limitHeight(side: ObservableValue<Number>) {
        node.maxHeightProperty().bind(side.map { it.toDouble() / 3 })
    }

    /** Shows what [text] is; returns whether there is anything to show (false for plain text). */
    fun show(text: String): Boolean {
        val payload = Payloads.parse(text)
        if (payload == null) {
            shownText = null
            content.children.clear()
            return false
        }
        if (text != shownText) {
            shownText = text
            content.children.setAll(build(payload))
        }
        return true
    }

    private fun build(payload: Payload): List<Node> {
        val heading = Label(payload.kind).apply { styleClass += "barcodes-structured-title" }
        val grid =
            GridPane().apply {
                hgap = 8.0
                vgap = 2.0
                columnConstraints.addAll(
                    ColumnConstraints().apply { halignment = HPos.LEFT },
                    ColumnConstraints().apply { hgrow = Priority.ALWAYS },
                    ColumnConstraints(),
                )
            }
        payload.fields.forEachIndexed { row, field ->
            val label = Label(field.label).apply { styleClass += "barcodes-field-label" }
            val value = valueField(field, row)
            grid.addRow(row, label, value, buttons(field, value, row))
        }
        return listOf(heading, grid)
    }

    /** A one-line field for a single-line value; a wrapping text area, at most 4 rows high, when it has line breaks. */
    private fun valueField(
        field: PayloadField,
        row: Int,
    ): TextInputControl {
        val shown = if (field.secret) MASK else field.value
        val multiLine = !field.secret && shown.any { it == '\n' || it == '\r' }
        val control: TextInputControl =
            if (multiLine) {
                TextArea(shown).apply {
                    isWrapText = true
                    prefRowCount = shown.lines().size.coerceIn(2, 4)
                }
            } else {
                TextField(shown)
            }
        return control.apply {
            id = "barcodes-field-$row"
            styleClass += "barcodes-field-value"
            isEditable = false
            maxWidth = Double.MAX_VALUE
        }
    }

    private fun buttons(
        field: PayloadField,
        value: TextInputControl,
        row: Int,
    ): HBox {
        val copy =
            iconButton("mdi2c-content-copy", "Copy", "barcodes-field-copy-$row") {
                Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString(field.value) })
            }
        val buttons = HBox(2.0).apply { alignment = Pos.CENTER_LEFT }
        if (field.secret) {
            var revealed = false
            val eye = iconButton("mdi2e-eye", "Show", "barcodes-field-eye-$row") {}
            eye.setOnAction {
                revealed = !revealed
                value.text = if (revealed) field.value else MASK
                eye.tooltip.text = if (revealed) "Hide" else "Show"
                (eye.graphic as FontIcon).iconLiteral = if (revealed) "mdi2e-eye-off" else "mdi2e-eye"
            }
            buttons.children += eye
        }
        buttons.children += copy
        return buttons
    }

    private fun iconButton(
        icon: String,
        tip: String,
        id: String,
        action: () -> Unit,
    ) = Button().apply {
        this.id = id
        graphic = FontIcon(icon)
        tooltip = Tooltip(tip)
        isFocusTraversable = false
        styleClass += "barcodes-field-button"
        setOnAction { action() }
    }

    internal companion object {
        const val MASK = "••••••••"
    }
}
