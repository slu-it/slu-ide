package dev.sluide.textediting

import org.ec4j.core.Resource
import org.ec4j.core.ResourcePropertiesService
import org.ec4j.core.parser.ParseException
import java.io.IOException
import java.nio.file.Path

/**
 * The indentation properties `.editorconfig` files set for a file; `null` where none is set. Missing values are
 * completed as the EditorConfig specification says: `indent_size = tab` and `indent_style = tab` without a size take
 * the tab width, and the tab width defaults to the indent size.
 */
internal data class EditorConfigIndent(
    val useTabs: Boolean? = null,
    val size: Int? = null,
    val tabWidth: Int? = null,
) {
    companion object {
        private val service = ResourcePropertiesService.default_()

        /**
         * The properties for [file], read from the `.editorconfig` files in its folder and above, up to one with
         * `root = true`. Unreadable or invalid files count as setting nothing.
         */
        fun of(file: Path): EditorConfigIndent {
            val properties =
                try {
                    service
                        .queryProperties(Resource.Resources.ofPath(file.toAbsolutePath(), Charsets.UTF_8))
                        .properties
                } catch (_: IOException) {
                    return EditorConfigIndent()
                } catch (_: ParseException) {
                    return EditorConfigIndent()
                }

            fun value(name: String) = properties[name]?.sourceValue?.trim()?.lowercase()

            val useTabs =
                when (value("indent_style")) {
                    "tab" -> true
                    "space" -> false
                    else -> null
                }
            val indentSize = value("indent_size")
            val explicitSize = indentSize?.toIntOrNull()?.takeIf { it > 0 }
            val tabWidth = value("tab_width")?.toIntOrNull()?.takeIf { it > 0 } ?: explicitSize
            val size = explicitSize ?: tabWidth.takeIf { indentSize == "tab" || useTabs == true }
            return EditorConfigIndent(useTabs, size, tabWidth)
        }
    }
}
