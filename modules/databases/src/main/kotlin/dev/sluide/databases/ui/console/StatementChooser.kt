package dev.sluide.databases.ui.console

import dev.sluide.databases.sql.RunChoice
import dev.sluide.databases.sql.SqlStatement
import javafx.scene.Node
import javafx.scene.control.ListView
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.stage.Popup

/** The entries of the statement chooser and what each runs; no toolkit. */
internal class ChooserModel(
    private val choice: RunChoice.Choose,
) {
    /** The labels: the statement at the caret first, then all of them. */
    val labels: List<String> =
        listOf(
            "Statement at caret: ${firstLine(choice.atCaret.text)}",
            "All statements (${choice.all.size})",
        )

    /** The index selected when the chooser opens. */
    val defaultIndex = 0

    /** The statements the entry at [index] runs. */
    fun statements(index: Int): List<SqlStatement> = if (index == 1) choice.all else listOf(choice.atCaret)

    companion object {
        const val MAX_LABEL = 80

        /** The first line of [text], cut at [MAX_LABEL] characters with an ellipsis. */
        fun firstLine(text: String): String {
            val line =
                text
                    .trimStart()
                    .lineSequence()
                    .first()
                    .trimEnd()
            return if (line.length > MAX_LABEL) line.take(MAX_LABEL) + "…" else line
        }
    }
}

/**
 * The popup of Run with several statements and no selection: two entries below the caret. Enter or a click runs the
 * chosen one, Escape or a click elsewhere closes it.
 */
internal class StatementChooser(
    private val model: ChooserModel,
    private val onChoose: (List<SqlStatement>) -> Unit,
) {
    val list =
        ListView<String>().apply {
            styleClass += "statement-chooser"
            items.setAll(model.labels)
            selectionModel.select(model.defaultIndex)
            prefHeight = model.labels.size * ROW + 2.0
            prefWidth = 420.0
            addEventHandler(KeyEvent.KEY_PRESSED) { event ->
                when (event.code) {
                    KeyCode.ENTER -> {
                        event.consume()
                        choose()
                    }

                    KeyCode.ESCAPE -> {
                        event.consume()
                        popup.hide()
                    }

                    else -> {}
                }
            }
            addEventHandler(MouseEvent.MOUSE_CLICKED) { event ->
                if (event.button == MouseButton.PRIMARY && onFilledCell(event) &&
                    selectionModel.selectedIndex >= 0
                ) {
                    choose()
                }
            }
        }

    val popup: Popup =
        Popup().apply {
            isAutoHide = true
            isHideOnEscape = true
            content += list
        }

    /** Shows the popup at the screen position, owned by [owner]'s window. */
    fun show(
        owner: Node,
        x: Double,
        y: Double,
    ) {
        val window = owner.scene?.window ?: return
        popup.show(window, x, y)
        list.requestFocus()
    }

    private fun choose() {
        val index = list.selectionModel.selectedIndex.takeIf { it >= 0 } ?: model.defaultIndex
        popup.hide()
        onChoose(model.statements(index))
    }

    private companion object {
        const val ROW = 26
    }
}
