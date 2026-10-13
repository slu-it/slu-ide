package dev.sluide.settings

import javafx.scene.text.Font
import javafx.scene.text.FontPosture
import javafx.scene.text.FontWeight

/** The styles offered for fonts; nearly every font family has these four. */
enum class FontStyle(
    val label: String,
    val bold: Boolean,
    val italic: Boolean,
) {
    REGULAR("Regular", false, false),
    BOLD("Bold", true, false),
    ITALIC("Italic", false, true),
    BOLD_ITALIC("Bold Italic", true, true),
    ;

    override fun toString() = label
}

/** A font choice: family, size in pixels and style. */
data class FontSpec(
    val family: String,
    val size: Double,
    val style: FontStyle = FontStyle.REGULAR,
) {
    init {
        require(family.isNotBlank()) { "Font family must not be blank" }
        require(size > 0) { "Font size must be positive: $size" }
    }

    /** The font itself; needs the JavaFX toolkit. */
    fun toFont(): Font =
        Font.font(
            family,
            if (style.bold) FontWeight.BOLD else FontWeight.NORMAL,
            if (style.italic) FontPosture.ITALIC else FontPosture.REGULAR,
            size,
        )

    /** The font as inline CSS, for controls whose text should inherit it (e.g. `node.style = spec.toCss()`). */
    fun toCss(): String =
        "-fx-font-family: \"${family.replace("\"", "")}\"; " +
            "-fx-font-size: ${formatSize()}px; " +
            "-fx-font-weight: ${if (style.bold) "bold" else "normal"}; " +
            "-fx-font-style: ${if (style.italic) "italic" else "normal"};"

    private fun formatSize() = if (size % 1.0 == 0.0) size.toInt().toString() else size.toString()
}

/** The parts of the IDE whose font can be set separately. */
enum class FontArea(
    val label: String,
    internal val key: String,
) {
    EXPLORER("File Explorer", "explorer"),
    EDITOR("Editor", "editor"),
    TERMINAL("Terminal", "terminal"),
}
