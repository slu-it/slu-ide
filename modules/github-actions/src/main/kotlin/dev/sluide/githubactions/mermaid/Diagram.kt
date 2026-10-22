package dev.sluide.githubactions.mermaid

import dev.sluide.githubactions.model.UsesReference

/**
 * A flowchart as a tree of [Element]s, before it is written as Mermaid text ([MermaidWriter]). Labels hold escaped
 * lines ([MermaidLabels.label]), joined with `<br/>` when written; ids are generated (`t0`, `j3`, `j3s2`).
 */
internal data class Diagram(
    val elements: List<Element>,
    val links: List<Link>,
)

internal sealed interface Element {
    val id: String
}

internal enum class Shape { BOX, STADIUM }

/** The Mermaid classes of the diagram, stroke-only so that light and dark themes both work. */
internal enum class Style(
    val cssClass: String,
    val definition: String,
) {
    /** A `uses:` drawn as a black box. */
    BLACKBOX("blackbox", "stroke-width:3px"),

    /** A reference that could not be followed. */
    UNRESOLVED("unresolved", "stroke-dasharray: 5 5"),

    /** A reference whose target could not be read. */
    FAILED("failed", "stroke:#d9534f,stroke-width:2px"),

    /** A reference back to a workflow or action already being expanded. */
    CYCLE("cycle", "stroke:#f0ad4e,stroke-width:2px,stroke-dasharray: 2 2"),

    /** Where expanding stopped at a limit. */
    LIMIT("limit", "stroke-dasharray: 1 4"),
}

internal data class Node(
    override val id: String,
    val label: List<String>,
    val shape: Shape = Shape.BOX,
    val style: Style? = null,
) : Element

/**
 * A `uses:` [reference]. Without [expansion] it is drawn as a [Style.BLACKBOX] box with [label]; a later step may
 * resolve it into the subgraph of what it refers to, drawn in its place under the same [id].
 */
internal data class ReferenceNode(
    override val id: String,
    val label: List<String>,
    val reference: UsesReference,
    val expansion: Cluster? = null,
) : Element

/** A subgraph with an escaped [title], its [children] and the [links] between them, laid out top to bottom. */
internal data class Cluster(
    override val id: String,
    val title: String,
    val children: List<Element>,
    val links: List<Link> = emptyList(),
) : Element

/** An edge; [label] is escaped. Dotted edges are conditional. */
internal data class Link(
    val from: String,
    val to: String,
    val label: String? = null,
    val dotted: Boolean = false,
)
