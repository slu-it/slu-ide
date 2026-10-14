package dev.sluide.textediting

import java.nio.file.Path

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

        /**
         * The style of [file] with content [text]: each value from `.editorconfig` if set there, else as detected
         * from [text], else from [DEFAULT]. Reads the `.editorconfig` files from disk, so call it when a file is
         * opened, not on every edit.
         */
        fun forFile(
            file: Path,
            text: String,
        ): IndentStyle {
            val configured = EditorConfigIndent.of(file)
            val detected = Indentation.detect(text)
            val size = configured.size ?: detected?.size ?: DEFAULT.size
            return IndentStyle(
                useTabs = configured.useTabs ?: detected?.useTabs ?: DEFAULT.useTabs,
                size = size,
                tabWidth = configured.tabWidth ?: size,
            )
        }
    }
}
