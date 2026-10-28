package dev.sluide.todos.markdown

import javafx.beans.binding.Bindings
import javafx.scene.Cursor
import javafx.scene.control.Tooltip
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.input.MouseButton
import javafx.scene.text.Text
import javafx.scene.text.TextFlow
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.HardLineBreak
import org.commonmark.node.HtmlInline
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Image as MdImage
import org.commonmark.node.Text as MdText

/** Builds the inline content of one paragraph or heading into a [TextFlow]; the styles of nested inlines stack. */
internal class InlineBuilder(
    private val context: MarkdownContext,
) {
    /** A [TextFlow] with the inline children of [block]. */
    fun flow(
        block: Node,
        vararg styleClasses: String,
    ): TextFlow {
        val flow = TextFlow()
        flow.styleClass.addAll(styleClasses)
        append(flow, block, emptyList())
        return flow
    }

    private fun append(
        flow: TextFlow,
        parent: Node,
        styles: List<String>,
    ) {
        var child = parent.firstChild
        while (child != null) {
            appendNode(flow, child, styles)
            child = child.next
        }
    }

    private fun appendNode(
        flow: TextFlow,
        node: Node,
        styles: List<String>,
    ) {
        when (node) {
            is MdText -> flow.children.add(text(node.literal, styles))
            is Emphasis -> append(flow, node, styles + "em")
            is StrongEmphasis -> append(flow, node, styles + "strong")
            is Strikethrough -> append(flow, node, styles + "strike")
            is Code -> flow.children.add(text(node.literal, styles + "code"))
            is SoftLineBreak -> flow.children.add(text(" ", styles))
            is HardLineBreak -> flow.children.add(text("\n", styles))
            is HtmlInline -> flow.children.add(text(node.literal, styles))
            is Link -> link(flow, node, styles)
            is MdImage -> image(flow, node)
            is TaskListItemMarker -> Unit
            else -> append(flow, node, styles)
        }
    }

    private fun text(
        literal: String,
        styles: List<String>,
    ): Text =
        Text(literal).apply {
            styleClass.add("text")
            styleClass.addAll(styles)
        }

    /** A link is styled text (so it wraps and keeps emphasis); a primary click acts on its target. */
    private fun link(
        flow: TextFlow,
        node: Link,
        styles: List<String>,
    ) {
        val href = node.destination.orEmpty()
        val before = flow.children.size
        append(flow, node, styles + "link")
        if (flow.children.size == before) flow.children.add(text(href, styles + "link"))
        flow.children.drop(before).forEach { part ->
            part.cursor = Cursor.HAND
            Tooltip.install(part, Tooltip(href))
            part.setOnMouseClicked { event ->
                if (event.button == MouseButton.PRIMARY) open(href)
            }
        }
    }

    private fun open(href: String) {
        when (val target = linkTarget(href, context.projectFolder)) {
            is LinkTarget.Web -> context.openWebPage(target.uri)
            is LinkTarget.File -> context.openFile(target.path)
            LinkTarget.None -> Unit
        }
    }

    /** Shows the alt text until the image is loaded; then the image, no wider than itself or its text flow. */
    private fun image(
        flow: TextFlow,
        node: MdImage,
    ) {
        val alt = plainText(node)
        val altText = text(alt.ifEmpty { node.destination.orEmpty() }, listOf("image-alt"))
        flow.children.add(altText)
        val source = imageSource(node.destination.orEmpty(), context.projectFolder) ?: return
        val image = Image(source, true)
        val show = {
            val index = flow.children.indexOf(altText)
            if (index >= 0 && !image.isError && image.progress >= 1.0) {
                val view =
                    ImageView(image).apply {
                        isPreserveRatio = true
                        fitWidthProperty().bind(
                            Bindings.createDoubleBinding(
                                { minOf(image.width, flow.width.coerceAtLeast(1.0)) },
                                image.widthProperty(),
                                flow.widthProperty(),
                            ),
                        )
                    }
                flow.children[index] = view
            }
        }
        image.progressProperty().addListener { _, _, _ -> show() }
        show()
    }

    /** The text of [node] without markup. */
    private fun plainText(node: Node): String {
        val out = StringBuilder()

        fun walk(n: Node) {
            var child = n.firstChild
            while (child != null) {
                when (child) {
                    is MdText -> out.append(child.literal)
                    is Code -> out.append(child.literal)
                    is SoftLineBreak, is HardLineBreak -> out.append(' ')
                    else -> walk(child)
                }
                child = child.next
            }
        }
        walk(node)
        return out.toString()
    }
}
