package dev.sluide.githubactions.mermaid

import dev.sluide.githubactions.SourceLocation
import dev.sluide.githubactions.model.UsesReference

/**
 * A flowchart as a tree of [Element]s, before it is written as Mermaid text ([MermaidWriter]). Labels hold escaped
 * lines ([MermaidLabels.label]), joined with `<br/>` when written; ids are generated (`t0`, `j3`, `j3s2`).
 */
internal data class Diagram(
    val elements: List<Element>,
    val links: List<Link>,
) {
    /** Where the boxes and subgraphs drawn come from, by id; an expanded reference by the line of its `uses:`. */
    fun locations(): Map<String, SourceLocation> =
        buildMap {
            fun collect(element: Element) {
                element.location?.let { put(element.id, it) }
                when (element) {
                    is Node -> {}

                    is ReferenceNode -> {
                        element.expansion?.children?.forEach(::collect)
                    }

                    is Cluster -> {
                        element.children.forEach(::collect)
                    }
                }
            }
            elements.forEach(::collect)
        }
}

/** A box or subgraph; [location] the line it comes from, if known. */
internal sealed interface Element {
    val id: String
    val location: SourceLocation?
}

internal enum class Shape { BOX, STADIUM, ROUNDED }

/** The direction a [Cluster] lays out its children in. */
internal enum class Direction { TB, LR }

/** The Mermaid classes of the diagram, stroke-only so that light and dark themes both work. */
internal enum class Style(
    val cssClass: String,
    val definition: String,
) {
    /** A `uses:` drawn as a black box. */
    BLACKBOX("blackbox", "stroke-width:3px"),

    /** A local reference whose target does not exist. */
    UNRESOLVED("unresolved", "stroke-dasharray: 5 5"),

    /** A local reference whose target could not be read or parsed, or lies outside the repository. */
    FAILED("failed", "stroke:#d9534f,stroke-width:2px"),

    /** A reference back to a workflow or action already being expanded. */
    CYCLE("cycle", "stroke:#f0ad4e,stroke-width:2px,stroke-dasharray: 2 2"),

    /** Where expanding stopped at a limit. */
    LIMIT("limit", "stroke-dasharray: 1 4"),

    /** The inputs a step or reusable workflow job is given: a thin dashed border. */
    INPUTS("inputs", "stroke-dasharray: 4 3,stroke-width:1px"),
}

internal data class Node(
    override val id: String,
    val label: List<String>,
    val shape: Shape = Shape.BOX,
    val style: Style? = null,
    override val location: SourceLocation? = null,
) : Element

/**
 * A `uses:` [reference]. Without [expansion] it is drawn as a box with [label] in [style] (a black box, or why it was
 * not followed); with one, that subgraph of what it refers to is drawn in its place under the same [id].
 */
internal data class ReferenceNode(
    override val id: String,
    val label: List<String>,
    val reference: UsesReference,
    val expansion: Cluster? = null,
    val style: Style = Style.BLACKBOX,
    override val location: SourceLocation? = null,
) : Element

/** A subgraph with an escaped [title], its [children] and the [links] between them, laid out in [direction]. */
internal data class Cluster(
    override val id: String,
    val title: String,
    val children: List<Element>,
    val links: List<Link> = emptyList(),
    override val location: SourceLocation? = null,
    val direction: Direction = Direction.TB,
) : Element

/** An edge; [label] is escaped. Dotted edges are conditional. */
internal data class Link(
    val from: String,
    val to: String,
    val label: String? = null,
    val dotted: Boolean = false,
)
