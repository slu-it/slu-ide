package dev.sluide.python

import dev.sluide.languageservers.LanguageServerSpec
import dev.sluide.languageservers.ServerCommand
import org.eclipse.lsp4j.ConfigurationItem
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.util.Locale

/** The file extensions ty handles: sources, stubs and windowed scripts (scripts known only by a shebang are not). */
private val PYTHON_EXTENSIONS = setOf("py", "pyi", "pyw")

/**
 * Python through ty (ADR 0144). ty finds the project's virtual environment by itself (`.venv` in the project folder,
 * `VIRTUAL_ENV`, Conda, `python3` on the PATH) and runs no program of the project, so it needs no trust check.
 */
@Component
class TySpec(
    override val installer: TyInstaller,
) : LanguageServerSpec {
    override val id = "python"

    override val name = "Python"

    override fun matches(file: Path): Boolean =
        file.fileName
            ?.toString()
            ?.substringAfterLast('.', "")
            ?.lowercase(Locale.ROOT) in PYTHON_EXTENSIONS

    override fun languageId(file: Path) = "python"

    override fun command(): ServerCommand? = installer.command()

    /** Nothing configured: an empty object for every section (servers like Pyrefly reject `null`). */
    override fun configuration(item: ConfigurationItem): Any = emptyMap<String, Any?>()

    /** A Python identifier ([isPythonIdentifier]): ty renames to `foo bar`, `1abc` and `class` too. */
    override fun isIdentifier(name: String): Boolean = isPythonIdentifier(name)
}

/** Python's hard keywords, which no name may be; soft keywords (`match`, `case`, `type`, `_`) are names. */
private val PYTHON_KEYWORDS =
    setOf(
        "False",
        "None",
        "True",
        "and",
        "as",
        "assert",
        "async",
        "await",
        "break",
        "class",
        "continue",
        "def",
        "del",
        "elif",
        "else",
        "except",
        "finally",
        "for",
        "from",
        "global",
        "if",
        "import",
        "in",
        "is",
        "lambda",
        "nonlocal",
        "not",
        "or",
        "pass",
        "raise",
        "return",
        "try",
        "while",
        "with",
        "yield",
    )

/**
 * Whether [name] is a Python identifier, as `str.isidentifier()` decides, and no hard keyword: the first character a
 * letter (Unicode identifier start) or `_`, the others identifier parts (letters, digits, `_`, combining marks), no
 * characters identifiers ignore.
 */
internal fun isPythonIdentifier(name: String): Boolean {
    if (name.isEmpty() || name in PYTHON_KEYWORDS) return false
    val codePoints = name.codePoints().toArray()
    val first = codePoints.first()
    if (first != '_'.code && !Character.isUnicodeIdentifierStart(first)) return false
    return codePoints.all { Character.isUnicodeIdentifierPart(it) && !Character.isIdentifierIgnorable(it) }
}
