package dev.sluide.textediting

/**
 * How a file is indented: with tabs or with [size] spaces per level. [tabWidth] is the number of columns a tab
 * stands for, used to measure existing indentation.
 */
data class IndentStyle(
    val useTabs: Boolean,
    val size: Int,
    val tabWidth: Int = size,
) {
    init {
        require(size > 0 && tabWidth > 0) { "Indent size and tab width must be positive: $size, $tabWidth" }
    }

    /** The text of one indentation level. */
    val unit: String get() = if (useTabs) "\t" else " ".repeat(size)

    companion object {
        /** The style when nothing else is known: 4 spaces. */
        val DEFAULT = IndentStyle(useTabs = false, size = 4)
    }
}
