package dev.sluide.todos.markdown

import javafx.application.Platform
import javafx.beans.binding.Bindings
import javafx.scene.Cursor
import javafx.scene.control.Tooltip
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
import javafx.scene.Node as FxNode
import org.commonmark.node.Image as MdImage
import org.commonmark.node.Text as MdText

/** Builds the inline content of one paragraph or heading into a [TextFlow]; the styles of nested inlines stack. */
internal class InlineBuilder(
    private val context: MarkdownContext,
) {
    /** The target of each part of a link, so that an image loaded into a link replaces its alt text as a link too. */
    private val linkTargets = HashMap<FxNode, String>()

    /** The tooltip of each part of a link, so that a refused image can add its reason to it. */
    private val linkTooltips = HashMap<FxNode, Tooltip>()

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

    /** A link is styled text (so it wraps and keeps emphasis); a primary click acts on its target and is consumed. */
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
            linkTargets[part] = href
            makeLink(part, href)
        }
    }

    private fun makeLink(
        part: FxNode,
        href: String,
    ) {
        part.cursor = Cursor.HAND
        val tooltip = Tooltip(href)
        linkTooltips[part] = tooltip
        Tooltip.install(part, tooltip)
        part.setOnMouseClicked { event ->
            if (event.button == MouseButton.PRIMARY) {
                open(href)
                // A double click on a link must not also edit its TODO card.
                event.consume()
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

    /**
     * Shows the alt text until the image is loaded off the FX thread (ADR 0175); then the image, no wider than itself
     * or its text flow. An image that cannot be shown keeps its alt text, dimmed, with the reason as tooltip.
     */
    private fun image(
        flow: TextFlow,
        node: MdImage,
    ) {
        val alt = plainText(node)
        val altText = text(alt.ifEmpty { node.destination.orEmpty() }, listOf("image-alt"))
        flow.children.add(altText)
        val source = imageSource(node.destination.orEmpty(), context.projectFolder) ?: return
        context.imageExecutor.execute {
            val loaded = loadImage(source, context.imageFetcher)
            Platform.runLater { show(flow, altText, loaded) }
        }
    }

    /** Replaces [altText] with the image; the result of a load is dropped when the alt text left the flow meanwhile. */
    private fun show(
        flow: TextFlow,
        altText: Text,
        loaded: LoadedImage,
    ) {
        val index = flow.children.indexOf(altText)
        if (index < 0) return
        val image = loaded.image
        if (image == null) {
            altText.styleClass.add("image-refused")
            val linkTooltip = linkTooltips[altText]
            if (linkTooltip != null) {
                linkTooltip.text = "${linkTooltip.text} — image ${loaded.reason}"
            } else {
                Tooltip.install(altText, Tooltip("Image ${loaded.reason}"))
            }
            return
        }
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
        linkTargets[altText]?.let { makeLink(view, it) }
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
