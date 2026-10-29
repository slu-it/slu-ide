package dev.sluide.coloring.detect

import dev.sluide.coloring.color.CssColor

/** The kind of source file a line comes from; it decides which context rules [ColorFinder] applies. */
enum class SourceKind {
    STYLE,
    MARKUP,

    /** `.xml`: like markup, but 4 and 8 digit hex is not matched, as Android reads `#AARRGGBB` and CSS `#RRGGBBAA`. */
    XML,
    SCRIPT,

    /** Vue and Svelte single-file components: the markup, style and script rules together. */
    COMPONENT,
    ;

    companion object {
        private val byExtension =
            buildMap {
                "css scss sass less".split(' ').forEach { put(it, STYLE) }
                "html htm xhtml svg".split(' ').forEach { put(it, MARKUP) }
                "js mjs cjs ts mts cts jsx tsx".split(' ').forEach { put(it, SCRIPT) }
                put("xml", XML)
                "vue svelte".split(' ').forEach { put(it, COMPONENT) }
            }

        /** The kind for [fileName] by its last extension (case-insensitive), or `null` for other files. */
        fun of(fileName: String): SourceKind? {
            val dot = fileName.lastIndexOf('.')
            if (dot < 0) return null
            return byExtension[fileName.substring(dot + 1).lowercase()]
        }
    }
}

/** A color written in a line: [range] is the inclusive column range, [text] the source text it was parsed from. */
data class FoundColor(
    val range: IntRange,
    val text: String,
    val color: CssColor,
)
