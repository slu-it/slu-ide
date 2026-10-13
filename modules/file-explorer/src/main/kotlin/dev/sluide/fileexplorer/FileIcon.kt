package dev.sluide.fileexplorer

import org.kordamp.ikonli.Ikon
import org.kordamp.ikonli.materialdesign2.MaterialDesignC
import org.kordamp.ikonli.materialdesign2.MaterialDesignF
import org.kordamp.ikonli.materialdesign2.MaterialDesignL
import java.nio.file.Path
import kotlin.io.path.extension

/**
 * The icons of the file explorer, from Material Design Icons (ADR 0012). Each has a style class that colours it in
 * `file-explorer.css`. A new file type is an entry here, its extensions in [fileIconFor] and a colour in the CSS.
 */
enum class FileIcon(
    val ikon: Ikon,
    val styleClass: String,
) {
    FOLDER(MaterialDesignF.FOLDER, "file-icon-folder"),
    FOLDER_OPEN(MaterialDesignF.FOLDER_OPEN, "file-icon-folder"),
    TEXT(MaterialDesignF.FILE_DOCUMENT_OUTLINE, "file-icon-text"),
    BINARY(MaterialDesignF.FILE_QUESTION_OUTLINE, "file-icon-binary"),
    KOTLIN(MaterialDesignL.LANGUAGE_KOTLIN, "file-icon-kotlin"),
    JAVA(MaterialDesignL.LANGUAGE_JAVA, "file-icon-java"),
    PYTHON(MaterialDesignL.LANGUAGE_PYTHON, "file-icon-python"),
    JAVASCRIPT(MaterialDesignL.LANGUAGE_JAVASCRIPT, "file-icon-javascript"),
    TYPESCRIPT(MaterialDesignL.LANGUAGE_TYPESCRIPT, "file-icon-typescript"),
    HTML(MaterialDesignL.LANGUAGE_HTML5, "file-icon-html"),
    JSON(MaterialDesignC.CODE_JSON, "file-icon-json"),
    XML(MaterialDesignF.FILE_XML_BOX, "file-icon-xml"),

    // Material Design Icons has no YAML icon.
    YAML(MaterialDesignC.CODE_BRACES, "file-icon-yaml"),
    MARKDOWN(MaterialDesignL.LANGUAGE_MARKDOWN, "file-icon-markdown"),
}

/** Icons by lower-case extension; the source types match the code viewers in `modules/file-viewers`. */
private val iconsByExtension: Map<String, FileIcon> =
    buildMap {
        fun put(
            icon: FileIcon,
            vararg extensions: String,
        ) = extensions.forEach { put(it, icon) }
        put(FileIcon.KOTLIN, "kt", "kts")
        put(FileIcon.JAVA, "java")
        put(FileIcon.PYTHON, "py", "pyw", "pyi")
        put(FileIcon.JAVASCRIPT, "js", "mjs", "cjs")
        put(FileIcon.TYPESCRIPT, "ts", "mts", "cts")
        put(FileIcon.HTML, "html", "htm", "xhtml")
        put(FileIcon.JSON, "json", "jsonc")
        put(FileIcon.XML, "xml", "xsd", "xsl", "xslt", "svg", "fxml")
        put(FileIcon.YAML, "yaml", "yml")
        put(FileIcon.MARKDOWN, "md", "markdown")
    }

/**
 * The icon for [path]: an open or closed folder, the icon of a known extension (case-insensitive), or else
 * [FileIcon.TEXT] or [FileIcon.BINARY] depending on [readable]. [readable] may read the file, so it is only called
 * for files without a known extension.
 */
fun fileIconFor(
    path: Path,
    directory: Boolean,
    expanded: Boolean,
    readable: () -> Boolean,
): FileIcon =
    when {
        directory -> if (expanded) FileIcon.FOLDER_OPEN else FileIcon.FOLDER
        else -> iconsByExtension[path.extension.lowercase()] ?: if (readable()) FileIcon.TEXT else FileIcon.BINARY
    }
