package dev.sluide.docker

import javafx.scene.Node
import javafx.scene.text.Text

/**
 * Whether the source editor under [this] paints a segment with a style class of a highlighter: the text area draws
 * each styled segment as a [Text] whose classes are the style spans' classes after its own `text`.
 */
internal fun Node.hasHighlightedText(): Boolean =
    lookupAll(".styled-text-area")
        .flatMap { it.lookupAll(".text") }
        .filterIsInstance<Text>()
        .any { text -> text.styleClass.any { it != "text" } }
