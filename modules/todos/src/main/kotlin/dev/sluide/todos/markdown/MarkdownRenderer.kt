package dev.sluide.todos.markdown

import javafx.geometry.Pos
import javafx.scene.control.CheckBox
import javafx.scene.control.Label
import javafx.scene.control.Separator
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.scene.text.Text
import javafx.scene.text.TextFlow
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.LinkReferenceDefinition
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser
import java.net.URI
import java.nio.file.Path

/** What the rendered Markdown needs from its surroundings. */
internal class MarkdownContext(
    /** The project folder that local links and images resolve in; null for shared TODOs. */
    val projectFolder: Path?,
    val openWebPage: (URI) -> Unit,
    val openFile: (Path) -> Unit,
    /** Called with the new source when a task checkbox is clicked; null makes the checkboxes read-only. */
    val sourceChanged: ((String) -> Unit)?,
)

private const val STYLESHEET = "/dev/sluide/todos/todos.css"

private val parser: Parser =
    Parser
        .builder()
        .extensions(
            listOf(
                StrikethroughExtension.create(),
                TaskListItemsExtension.create(),
                AutolinkExtension.create(),
            ),
        ).includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
        .build()

/** Renders Markdown into JavaFX controls, without a WebView (see the module's ADR). FX thread. */
internal class MarkdownRenderer(
    private val context: MarkdownContext,
) {
    /** A [VBox] (style class `todo-markdown`) with the blocks of [source]. */
    fun render(source: String): Region {
        val root = VBox()
        root.styleClass.add("todo-markdown")
        root.stylesheets.add(
            MarkdownRenderer::class.java
                .getResource(STYLESHEET)
                ?.toExternalForm()
                .orEmpty(),
        )
        val inlines = InlineBuilder(context)
        val state = SourceState(source)
        blocks(parser.parse(source), root, state, inlines)
        return root
    }

    private fun blocks(
        parent: Node,
        into: VBox,
        state: SourceState,
        inlines: InlineBuilder,
    ) {
        var child = parent.firstChild
        while (child != null) {
            block(child, state, inlines)?.let(into.children::add)
            child = child.next
        }
    }

    private fun block(
        node: Node,
        state: SourceState,
        inlines: InlineBuilder,
    ): javafx.scene.Node? =
        when (node) {
            is Paragraph -> {
                inlines.flow(node)
            }

            is Heading -> {
                inlines.flow(node, "heading", "h${node.level}")
            }

            is BulletList -> {
                list(node, state, inlines, null)
            }

            is OrderedList -> {
                list(node, state, inlines, node.markerStartNumber ?: 1, node.markerDelimiter ?: ".")
            }

            is BlockQuote -> {
                VBox().also { box ->
                    box.styleClass.add("quote")
                    blocks(node, box, state, inlines)
                }
            }

            is FencedCodeBlock -> {
                codeBlock(node.literal)
            }

            is IndentedCodeBlock -> {
                codeBlock(node.literal)
            }

            is TaskListItemMarker -> {
                null
            }

            is LinkReferenceDefinition -> {
                null
            }

            is ThematicBreak -> {
                Separator()
            }

            is HtmlBlock -> {
                TextFlow(Text(node.literal.trimEnd('\n')).apply { styleClass.add("text") })
            }

            else -> {
                VBox().also { blocks(node, it, state, inlines) }
            }
        }

    private fun codeBlock(literal: String): TextFlow =
        TextFlow(Text(literal.trimEnd('\n')).apply { styleClass.addAll("text", "code") }).apply {
            styleClass.add("code-block")
        }

    private fun list(
        list: Node,
        state: SourceState,
        inlines: InlineBuilder,
        start: Int?,
        delimiter: String = ".",
    ): VBox {
        val box = VBox()
        box.styleClass.add("list")
        var number = start ?: 0
        var item = list.firstChild
        while (item != null) {
            if (item is ListItem) {
                box.children.add(
                    listItem(item, state, inlines, start?.let { "${number++}$delimiter" } ?: "•"),
                )
            }
            item = item.next
        }
        return box
    }

    private fun listItem(
        item: ListItem,
        state: SourceState,
        inlines: InlineBuilder,
        bullet: String,
    ): HBox {
        val content = VBox()
        content.styleClass.add("item-content")
        content.minWidth = 0.0
        blocks(item, content, state, inlines)
        val task = item.firstChild as? TaskListItemMarker
        val marker: javafx.scene.Node =
            if (task != null) checkBox(task, state) else Label(bullet).apply { styleClass.add("bullet") }
        val row = HBox(marker, content)
        row.styleClass.add("item")
        row.alignment = Pos.TOP_LEFT
        HBox.setHgrow(content, Priority.ALWAYS)
        return row
    }

    private fun checkBox(
        task: TaskListItemMarker,
        state: SourceState,
    ): CheckBox {
        val offset = markerOffset(task, state.original)
        val changed = context.sourceChanged
        return CheckBox().apply {
            styleClass.add("task")
            isSelected = task.isChecked
            isDisable = changed == null || offset < 0
            setOnAction {
                val next = toggleTask(state.current, offset)
                if (next != null && changed != null) {
                    state.current = next
                    changed(next)
                } else {
                    isSelected = !isSelected
                }
            }
        }
    }
}

/** The source of one render: [original] as rendered, [current] with the task checkboxes toggled so far. */
private class SourceState(
    val original: String,
) {
    var current = original
}

/**
 * The index of the `[` of the marker of the task item [task] in [source]; -1 if it cannot be found. The marker has no
 * source span of its own (commonmark 0.30), so it is searched from the start of its list item up to the item's text.
 */
private fun markerOffset(
    task: TaskListItemMarker,
    source: String,
): Int {
    val item = task.parent ?: return -1
    val start = item.sourceSpans.firstOrNull()?.inputIndex ?: return -1
    val end =
        task.next
            ?.sourceSpans
            ?.firstOrNull()
            ?.inputIndex
            ?: (start + item.sourceSpans.sumOf { it.length })
    var at = source.indexOf('[', start)
    while (at in 0..end) {
        // An escaped bracket is text, not a marker (the extension still draws a checkbox for it).
        if (source.getOrNull(at - 1) != '\\' && toggleTask(source, at) != null) return at
        at = source.indexOf('[', at + 1)
    }
    return -1
}
