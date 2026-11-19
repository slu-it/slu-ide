package dev.sluide.texttypes

/** A type of text a file can hold: its [name] as users see it and its file [extension] without the dot. */
data class TextType(
    val name: String,
    /** `null` for [TextTypes.NO_EXTENSION], the name as typed. */
    val extension: String?,
)

/** The types a text can be saved as, in the order of a type picker; detection ties go to the earlier one. */
object TextTypes {
    val PLAIN_TEXT = TextType("Plain Text", "txt")
    val NO_EXTENSION = TextType("No extension", null)

    internal val MARKDOWN = TextType("Markdown", "md")
    internal val JSON = TextType("JSON", "json")
    internal val YAML = TextType("YAML", "yaml")
    internal val XML = TextType("XML", "xml")
    internal val HTML = TextType("HTML", "html")
    internal val SVG = TextType("SVG", "svg")
    internal val CSS = TextType("CSS", "css")
    internal val JAVASCRIPT = TextType("JavaScript", "js")
    internal val TYPESCRIPT = TextType("TypeScript", "ts")
    internal val KOTLIN = TextType("Kotlin", "kt")
    internal val KOTLIN_SCRIPT = TextType("Kotlin Script", "kts")
    internal val JAVA = TextType("Java", "java")
    internal val PYTHON = TextType("Python", "py")
    internal val SHELL_SCRIPT = TextType("Shell Script", "sh")
    internal val SQL = TextType("SQL", "sql")
    internal val HTTP_REQUEST = TextType("HTTP Request", "http")
    internal val TOML = TextType("TOML", "toml")
    internal val PROPERTIES = TextType("Properties", "properties")
    internal val CSV = TextType("CSV", "csv")
    internal val TSV = TextType("TSV", "tsv")
    internal val MERMAID = TextType("Mermaid", "mmd")
    internal val KQL = TextType("KQL", "kql")
    internal val BICEP = TextType("Bicep", "bicep")
    internal val DIFF = TextType("Diff", "diff")

    val all: List<TextType> =
        listOf(
            PLAIN_TEXT,
            MARKDOWN,
            JSON,
            YAML,
            XML,
            HTML,
            SVG,
            CSS,
            JAVASCRIPT,
            TYPESCRIPT,
            KOTLIN,
            KOTLIN_SCRIPT,
            JAVA,
            PYTHON,
            SHELL_SCRIPT,
            SQL,
            HTTP_REQUEST,
            TOML,
            PROPERTIES,
            CSV,
            TSV,
            MERMAID,
            KQL,
            BICEP,
            DIFF,
            NO_EXTENSION,
        )

    /** The type of [extension], with or without its leading dot, ignoring case; never [NO_EXTENSION]. */
    fun byExtension(extension: String): TextType? =
        extension.removePrefix(".").let { wanted -> all.firstOrNull { it.extension.equals(wanted, ignoreCase = true) } }
}
