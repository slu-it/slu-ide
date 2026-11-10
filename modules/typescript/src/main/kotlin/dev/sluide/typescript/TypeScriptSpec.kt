package dev.sluide.typescript

import dev.sluide.languageservers.DocumentGate
import dev.sluide.languageservers.EnvironmentProblem
import dev.sluide.languageservers.LanguageServerSpec
import dev.sluide.languageservers.ServerCommand
import dev.sluide.languageservers.WorkspaceStatus
import dev.sluide.servertools.compareVersions
import org.eclipse.lsp4j.ConfigurationItem
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

private val EXTENSION_LANGUAGES =
    mapOf(
        "ts" to "typescript",
        "mts" to "typescript",
        "cts" to "typescript",
        "tsx" to "typescriptreact",
        "js" to "javascript",
        "mjs" to "javascript",
        "cjs" to "javascript",
        "jsx" to "javascriptreact",
    )

/** Files whose change can change which TypeScript governs the files below them. */
private val GATE_FILES = setOf("package.json", "pnpm-workspace.yaml", "deno.json", "deno.jsonc", "node_modules")

private val CONFIG_NAME = Regex("""(tsconfig|jsconfig).*\.json""")

private const val NEEDS_6 = "Code intelligence needs TypeScript 6 or later"

private const val NEWER = " · newer than the server"

/** The configuration answer that turns automatic type acquisition off (it would run `npm install` in the background). */
private val TYPE_ACQUISITION_OFF: Map<String, Any?> =
    mapOf(
        "tsserver" to mapOf("automaticTypeAcquisition" to mapOf("enabled" to false)),
        "disableAutomaticTypeAcquisition" to true,
    )

/**
 * TypeScript and JavaScript through the TypeScript 7 server (ADR 0161): one server per window, whose own tsconfig model
 * keeps the projects of a monorepo apart. A document is served only if the project it belongs to uses TypeScript 6 or
 * later, or none ([gate]). The server has no JS runtime and runs no project code, so it needs no trust check.
 *
 * @param stopAt where the walk for a file's TypeScript ends; `null`, the real value, is the filesystem root.
 */
@Component
class TypeScriptSpec internal constructor(
    override val installer: TypeScriptInstaller,
    private val settings: TypeScriptSettings,
    private val stopAt: Path?,
) : LanguageServerSpec {
    @Autowired
    constructor(installer: TypeScriptInstaller, settings: TypeScriptSettings) : this(installer, settings, null)

    override val id = "typescript"

    override val name = "TypeScript"

    override fun matches(file: Path): Boolean = extensionOf(file) in EXTENSION_LANGUAGES

    override fun languageId(file: Path): String = EXTENSION_LANGUAGES[extensionOf(file)] ?: "typescript"

    override fun command(): ServerCommand? = installer.command()

    /**
     * Automatic type acquisition off (otherwise the server runs `npm install types-registry@latest` and for @types packages in
     * the background, ADR 0161) in both sections the server asks; an empty object for everything else.
     */
    override fun configuration(item: ConfigurationItem): Any =
        if (item.section == "js/ts" || item.section == "typescript") TYPE_ACQUISITION_OFF else emptyMap<String, Any?>()

    override val gatesDocuments: Boolean get() = true

    /** A few stats and small reads, on the session's folder worker. */
    override fun gate(
        file: Path,
        projectFolder: Path,
    ): DocumentGate {
        val source = resolveTypeScript(file, stopAt)
        val watched = watchedOf(source)
        val problem = problemOf(source, projectFolder)
        return if (problem == null) DocumentGate(true, watched) else DocumentGate(false, watched, problem)
    }

    override fun affectsWorkspaceFolders(change: Path): Boolean = change.fileName?.toString() in GATE_FILES

    override fun keepsDiagnostics(file: Path): Boolean = file.fileName?.toString()?.let(CONFIG_NAME::matches) == true

    /** The nearest `tsconfig.json` or `jsconfig.json` from the file's folder up to [projectFolder]. */
    override fun configFiles(
        file: Path,
        projectFolder: Path,
    ): List<Path> {
        var folder = file.toAbsolutePath().normalize().parent
        val project = projectFolder.toAbsolutePath().normalize()
        while (folder != null && folder.startsWith(project)) {
            for (name in listOf("tsconfig.json", "jsconfig.json")) {
                val config = folder.resolve(name)
                if (Files.isRegularFile(config)) return listOf(config)
            }
            folder = folder.parent
        }
        return emptyList()
    }

    /**
     * A few stats and small reads, for the FX thread: it resolves the file again instead of asking the session's gate
     * answer, so it may briefly differ from the gate until the session asked again. The link names the folder only
     * inside the project folder (the explorer shows nothing else).
     */
    override fun workspaceStatus(
        file: Path,
        folder: Path?,
        projectFolder: Path,
    ): WorkspaceStatus? {
        val source = resolveTypeScript(file, stopAt)
        if (problemOf(source, projectFolder) != null) return null
        val custom = settings.currentPath().isNotEmpty()
        val server = installer.installed.get()?.version
        val serverLine =
            when {
                custom -> "Server: custom server (custom path)"
                server != null -> "Server: TypeScript $server (Thoreau's download)"
                else -> "Server: not installed"
            }
        return when (source) {
            is TypeScriptSource.Installed -> {
                val project = source.packageJson.parent.parent.parent
                val link = project.takeIf { it.startsWith(projectFolder) }
                val newer = !custom && server != null && compareVersions(source.version, server) > 0
                WorkspaceStatus(
                    "TypeScript project: ${label(project, projectFolder)} · TypeScript ${source.version}" +
                        if (newer) NEWER else "",
                    link,
                    "TypeScript ${source.version} from ${source.packageJson.parent}\n$serverLine",
                )
            }

            is TypeScriptSource.Declared -> {
                val project = source.packageJson.parent
                val link = project.takeIf { it.startsWith(projectFolder) }
                val range = source.resolvedRange ?: source.range
                WorkspaceStatus(
                    "TypeScript project: ${label(project, projectFolder)} · TypeScript $range (not installed)",
                    link,
                    "TypeScript $range declared in ${source.catalogFile ?: source.packageJson}\n$serverLine",
                )
            }

            else -> {
                val suffix =
                    when {
                        custom -> " · custom server"
                        server != null -> " · server $server"
                        else -> ""
                    }
                WorkspaceStatus(
                    "No TypeScript project$suffix",
                    null,
                    "No package.json or node_modules above this file declares TypeScript.\n$serverLine",
                )
            }
        }
    }

    override fun isIdentifier(name: String): Boolean = isEcmaScriptIdentifier(name)

    private fun problemOf(
        source: TypeScriptSource,
        projectFolder: Path,
    ): EnvironmentProblem? =
        when (source) {
            is TypeScriptSource.Loose -> null
            is TypeScriptSource.Deno -> denoProblem(source)
            is TypeScriptSource.Installed -> installedProblem(source, projectFolder)
            is TypeScriptSource.Declared -> declaredProblem(source, projectFolder)
        }

    private fun denoProblem(source: TypeScriptSource.Deno) =
        EnvironmentProblem(
            source.config.parent,
            "This is a Deno project (${source.config.fileName}); Thoreau doesn't run Deno's language server.",
            null,
        )

    private fun installedProblem(
        source: TypeScriptSource.Installed,
        projectFolder: Path,
    ): EnvironmentProblem? {
        val modules = source.packageJson.parent.parent
        val folder = modules.parent
        return when (verdictOf(source)) {
            Verdict.SUPPORTED -> {
                null
            }

            Verdict.TOO_OLD -> {
                EnvironmentProblem(
                    folder,
                    "This project uses TypeScript ${source.version} (${display(modules, projectFolder)}). $NEEDS_6.",
                    null,
                )
            }

            Verdict.UNDECIDABLE -> {
                EnvironmentProblem(
                    folder,
                    "Can't tell which TypeScript ${label(folder, projectFolder)} uses (\"${source.version}\" in " +
                        "${display(source.packageJson, projectFolder)}). $NEEDS_6; pin a range such as ^7.0.0.",
                    null,
                )
            }
        }
    }

    private fun declaredProblem(
        source: TypeScriptSource.Declared,
        projectFolder: Path,
    ): EnvironmentProblem? {
        val folder = source.packageJson.parent
        val json = display(source.packageJson, projectFolder)
        return when (verdictOf(source)) {
            Verdict.SUPPORTED -> {
                null
            }

            Verdict.TOO_OLD -> {
                val where = source.catalogFile.takeIf { source.resolvedRange != source.range } ?: source.packageJson
                EnvironmentProblem(
                    folder,
                    "This project declares TypeScript ${source.resolvedRange} in ${display(
                        where,
                        projectFolder,
                    )}. $NEEDS_6.",
                    null,
                )
            }

            Verdict.UNDECIDABLE -> {
                val catalog = source.catalogFile?.let { display(it, projectFolder) }
                val detail =
                    when {
                        source.resolvedRange == source.range -> ""
                        source.resolvedRange == null && catalog != null -> ", no typescript entry in $catalog"
                        source.resolvedRange == null -> ", no catalog found"
                        else -> ", \"${source.resolvedRange}\" in ${catalog ?: json}"
                    }
                EnvironmentProblem(
                    folder,
                    "Can't tell which TypeScript ${label(folder, projectFolder)} uses (\"${source.range}\" in " +
                        "$json$detail). $NEEDS_6; pin a range such as ^7.0.0.",
                    null,
                )
            }
        }
    }

    /**
     * Folders whose entries decide the answer: every consulted folder, its `node_modules`, `node_modules/typescript`
     * and the existing scope folders (the server needs file events for packages added there); the catalog's folder is among the consulted ones.
     */
    private fun watchedOf(source: TypeScriptSource): List<Path> {
        val watched = LinkedHashSet<Path>()
        for (folder in source.consulted) {
            val modules = folder.resolve("node_modules")
            watched.add(folder)
            watched.add(modules)
            watched.add(modules.resolve("typescript"))
            watched.addAll(scopesOf(modules))
        }
        return watched.toList()
    }

    private fun scopesOf(modules: Path): List<Path> =
        try {
            Files.newDirectoryStream(modules, "@*").use { entries -> entries.filter { Files.isDirectory(it) }.sorted() }
        } catch (_: Exception) {
            emptyList()
        }
}

private fun extensionOf(file: Path): String =
    file.fileName
        ?.toString()
        ?.substringAfterLast('.', "")
        ?.lowercase(Locale.ROOT)
        .orEmpty()

/** [path] relative to [project] when inside it, else absolute with the home folder shortened to `~`. */
private fun display(
    path: Path,
    project: Path,
): String {
    if (path.startsWith(project)) return project.relativize(path).toString().ifEmpty { "." }
    val home = Path.of(System.getProperty("user.home"))
    return if (path.startsWith(home)) "~/" + home.relativize(path) else path.toString()
}

/** A folder as the messages name it: relative to the project folder, the project folder itself by its name. */
private fun label(
    folder: Path,
    project: Path,
): String =
    if (folder == project) {
        project.fileName?.toString() ?: project.toString()
    } else {
        display(folder, project)
    }

/**
 * Whether [name] is an ECMAScript IdentifierName, optionally with a leading `#` (a private name): the first character
 * Unicode ID_Start, `$` or `_`, the others ID_Continue, `$`, ZWNJ or ZWJ. Reserved words are accepted: they are valid
 * property and method names, and the compiler reports their misuse as variable names.
 */
internal fun isEcmaScriptIdentifier(name: String): Boolean {
    val codePoints = name.removePrefix("#").codePoints().toArray()
    if (codePoints.isEmpty()) return false
    val first = codePoints.first()
    if (first != 0x24 && first != 0x5F && !Character.isUnicodeIdentifierStart(first)) return false
    return codePoints.all {
        it == 0x24 || it == 0x200C || it == 0x200D ||
            (Character.isUnicodeIdentifierPart(it) && !Character.isIdentifierIgnorable(it))
    }
}
