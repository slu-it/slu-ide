package dev.sluide.fileviewers

/**
 * Front matter at the start of a Markdown file: [language] `yaml` or `toml`; its content runs from [contentStart] to
 * [contentEnd] (the text between the marker lines, ending after the last content line's line break, empty for
 * `---\n---`); [end] is after the closing marker line including its line break (or the text length).
 */
data class FrontMatter(
    val language: String,
    val contentStart: Int,
    val contentEnd: Int,
    val end: Int,
)
