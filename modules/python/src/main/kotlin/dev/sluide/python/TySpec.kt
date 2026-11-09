package dev.sluide.python

import dev.sluide.languageservers.EnvironmentProblem
import dev.sluide.languageservers.LanguageServerSpec
import dev.sluide.languageservers.LspUris
import dev.sluide.languageservers.ProblemAction
import dev.sluide.languageservers.ServerCommand
import dev.sluide.languageservers.WorkspaceStatus
import org.eclipse.lsp4j.ConfigurationItem
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.util.Locale

/** Names of caches, tool folders and dot files that never hold a project or a venv. */
private val NOT_VENV_NAMES =
    setOf(
        "__pycache__",
        ".pytest_cache",
        ".ruff_cache",
        ".mypy_cache",
        ".git",
        ".idea",
        ".vscode",
        "node_modules",
        ".gitignore",
        ".gitattributes",
        ".editorconfig",
        ".DS_Store",
    )

/** The file extensions ty handles: sources, stubs and windowed scripts (scripts known only by a shebang are not). */
private val PYTHON_EXTENSIONS = setOf("py", "pyi", "pyw")

/**
 * Python through ty (ADR 0144). Every Python project (a folder with `uv.lock` or a virtual environment, nearest to the
 * file, up to the window folder) is a workspace folder of ty (ADR 0151, [projectRoot]); its virtual environment is
 * passed in [configuration], so that an inherited `VIRTUAL_ENV` does not win. Files outside any project fall back to
 * `VIRTUAL_ENV`, Conda or `python3` on the PATH. ty runs no program of the project, so it needs no trust check.
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

    /**
     * An empty object for every section (servers like Pyrefly reject `null`), except ty's own section of a workspace
     * folder with a virtual environment: its interpreter, `.venv` too, so that an inherited `VIRTUAL_ENV` does not
     * win over the project's own environment (ADR 0151). Runs on the LSP reader thread; small disk IO (stats).
     */
    override fun configuration(item: ConfigurationItem): Any {
        if (item.section == null || item.section == "ty") {
            val venv = item.scopeUri?.let(LspUris::toPath)?.let(::venvOf)
            if (venv != null) {
                return mapOf("configuration" to mapOf("environment" to mapOf("python" to venv.toString())))
            }
        }
        return emptyMap<String, Any?>()
    }

    override fun workspaceFolder(
        file: Path,
        projectFolder: Path,
    ): Path? = projectRoot(file, projectFolder)

    override fun watchedFolders(folder: Path): List<Path> = venvOf(folder)?.let(::sitePackages) ?: emptyList()

    /**
     * Files that decide a project root or venv, and names without extension or like `.venv` (maybe a venv folder),
     * except the caches and tool folders and dot files that are known not to be one.
     */
    override fun affectsWorkspaceFolders(change: Path): Boolean {
        val name = change.fileName?.toString() ?: return false
        if (name == "uv.lock" || name == "pyproject.toml" || name == "pyvenv.cfg") return true
        if (name in NOT_VENV_NAMES) return false
        return '.' !in name.removePrefix(".")
    }

    /** Small disk IO (stats, reading `pyvenv.cfg`), for the FX thread. */
    override fun workspaceStatus(
        folder: Path?,
        projectFolder: Path,
    ): WorkspaceStatus? {
        if (folder == null) {
            return WorkspaceStatus(
                "No Python project · default Python",
                null,
                "No uv.lock or virtual environment above this file in $projectFolder; " +
                    "ty uses VIRTUAL_ENV, Conda or python3 on the PATH.",
            )
        }
        val label =
            if (folder == projectFolder) {
                projectFolder.fileName?.toString() ?: projectFolder.toString()
            } else {
                projectFolder.relativize(folder).toString()
            }
        val venv = venvOf(folder)
        val details =
            if (venv == null) {
                "no virtual environment"
            } else {
                listOfNotNull(venv.fileName.toString(), pythonVersion(venv)?.let { "Python $it" })
                    .joinToString(" · ")
            }
        return WorkspaceStatus(
            "Python project: $label · $details",
            folder,
            "Project: $folder\nVirtual environment: ${venv ?: "none"}",
        )
    }

    /** Small disk IO (stats), for the FX thread. */
    override fun environmentProblem(
        file: Path,
        projectFolder: Path,
    ): EnvironmentProblem? {
        val folder = missingVenvFolder(file, projectFolder) ?: return null
        val name = folder.fileName?.toString() ?: folder.toString()
        return EnvironmentProblem(
            folder,
            "No virtual environment for $name, so its packages cannot be resolved.",
            ProblemAction("Run uv sync", "uv sync ($name)", "uv sync"),
        )
    }

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
