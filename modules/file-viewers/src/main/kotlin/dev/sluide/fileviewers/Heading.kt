package dev.sluide.fileviewers

/** A heading of a Markdown document: its [level] (1 to 6), plain [text] and 0-based source [line]. */
internal data class Heading(
    val level: Int,
    val text: String,
    val line: Int,
)
