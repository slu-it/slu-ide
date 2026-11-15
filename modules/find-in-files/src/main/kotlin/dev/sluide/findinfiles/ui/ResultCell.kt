package dev.sluide.findinfiles.ui

import javafx.beans.value.ObservableValue
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.OverrunStyle
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.text.Text
import javafx.scene.text.TextFlow
import java.nio.file.Path

/** Style class of the matches overlaid in the preview (a background, as the find bar's). */
internal const val HIT_CLASS = "find-in-files-hit"

/** Style class of the highlighted part of a result line (its fill). */
internal const val HIT_TEXT_CLASS = "find-in-files-hit-text"

/** Style class of the plain `Text` of a snippet (a `Text` has no `.text` class to colour it in dark). */
internal const val SNIPPET_CLASS = "find-in-files-text"

private const val LINE_NUMBER_WIDTH = 44.0

/**
 * A row of the result list: a file header (icon, name, dimmed folder, count) or a matching line (dimmed line number,
 * the snippet with its hits). [severalRoots] says whether headers name their root.
 */
internal class ResultCell(
    private val icon: (Path) -> Node?,
    private val severalRoots: () -> Boolean,
    private val editorFont: ObservableValue<String>,
) : ListCell<ResultRow>() {
    init {
        styleClass += "find-in-files-cell"
        // No width of its own: a long row is cut instead of making the list scroll sideways.
        prefWidth = 0.0
    }

    override fun updateItem(
        item: ResultRow?,
        empty: Boolean,
    ) {
        super.updateItem(item, empty)
        text = null
        graphic =
            when {
                empty || item == null -> null
                item is ResultRow.Header -> headerRow(item)
                item is ResultRow.Line -> lineRow(item)
                else -> null
            }
    }

    private fun headerRow(row: ResultRow.Header): Node {
        val parts = headerParts(row.hits, severalRoots())
        // A long folder gives way first, cut at its start so the folders nearest the file stay; name and count never
        // shrink.
        val name =
            Label(parts.name).apply {
                styleClass += "find-in-files-name"
                minWidth = Region.USE_PREF_SIZE
            }
        val folder =
            Label(parts.folder).apply {
                styleClass += "find-in-files-dim"
                textOverrun = OverrunStyle.LEADING_ELLIPSIS
                minWidth = 0.0
            }
        val count =
            Label(parts.count).apply {
                styleClass += "find-in-files-dim"
                minWidth = Region.USE_PREF_SIZE
            }
        val children = listOfNotNull(icon(row.hits.file), name, folder, count)
        return HBox(6.0, *children.toTypedArray()).apply { alignment = Pos.CENTER_LEFT }
    }

    private fun lineRow(row: ResultRow.Line): Node {
        val number =
            Label((row.hit.line + 1).toString()).apply {
                styleClass += "find-in-files-dim"
                alignment = Pos.CENTER_RIGHT
                styleProperty().bind(editorFont)
                minWidth = LINE_NUMBER_WIDTH
                prefWidth = LINE_NUMBER_WIDTH
            }
        val flow =
            TextFlow(
                *snippetSegments(row.hit.snippet, row.hit.highlights)
                    .map { segment ->
                        Text(segment.text).apply { styleClass += if (segment.hit) HIT_TEXT_CLASS else SNIPPET_CLASS }
                    }.toTypedArray(),
            ).apply {
                minWidth = Region.USE_PREF_SIZE
                styleProperty().bind(editorFont)
            }
        return HBox(10.0, number, flow).apply {
            alignment = Pos.CENTER_LEFT
            HBox.setHgrow(flow, Priority.NEVER)
        }
    }
}
