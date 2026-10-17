package dev.sluide.fileicons

import org.kordamp.ikonli.javafx.FontIcon

/** The stylesheet with the colours of the [FileIcon]s; add it to the scene graph that shows them. */
val FILE_ICONS_CSS: String =
    checkNotNull(FileIcon::class.java.getResource("file-icons.css")).toExternalForm()

private const val FILE_ICON_CLASS_PREFIX = "file-icon-"

/** Shows [icon]: its glyph, coloured by its style class instead of the one of the icon shown before. */
fun FontIcon.show(icon: FileIcon) {
    iconCode = icon.ikon
    styleClass.removeIf { it.startsWith(FILE_ICON_CLASS_PREFIX) }
    styleClass += icon.styleClass
}
