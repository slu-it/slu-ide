package dev.sluide.typescript

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import tools.jackson.core.json.JsonReadFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path

/*
 * Which TypeScript version governs a TS/JS file and whether Thoreau serves it (TypeScript 6 or later only). Pure
 * file-system functions without caching: a few stats and small reads per folder, `node_modules` is never walked. Every
 * IO or parse error counts as "absent" for that file and the walk continues; so does a package.json that exists but
 * cannot be parsed.
 */

sealed interface TypeScriptSource {
    /** The folders inspected, nearest first, up to where the walk stopped (for file watches). */
    val consulted: List<Path>

    /** `node_modules/typescript/package.json` has a version. */
    data class Installed(
        val version: String,
        val packageJson: Path,
        override val consulted: List<Path>,
    ) : TypeScriptSource

    /**
     * A package.json declares `typescript`; [range] as written (`catalog:`...), [resolvedRange] what it resolves to
     * (null: unresolvable), [catalogFile] the catalog source consulted (also when it has no entry). [consulted] then also lists the folders up
     * to the catalog's.
     */
    data class Declared(
        val range: String,
        val packageJson: Path,
        val resolvedRange: String?,
        val catalogFile: Path?,
        override val consulted: List<Path>,
    ) : TypeScriptSource

    /** A `deno.json(c)` governs the file; Deno has its own TypeScript, there is no version to read. */
    data class Deno(
        val config: Path,
        override val consulted: List<Path>,
    ) : TypeScriptSource

    /** Nothing found: a loose file. */
    data class Loose(
        override val consulted: List<Path>,
    ) : TypeScriptSource
}

private val BOM = 0xFEFF.toChar().toString()
private const val MAX_CONFIG_BYTES = 8L * 1024 * 1024
private val mapper =
    JsonMapper
        .builder()
        .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS, JsonReadFeature.ALLOW_TRAILING_COMMA)
        .build()

/**
 * Installed and Declared are decided by their version; Loose is SUPPORTED; Deno is UNDECIDABLE (it is not a version,
 * the spec decides what Deno projects get).
 */
fun verdictOf(source: TypeScriptSource): Verdict =
    when (source) {
        is TypeScriptSource.Installed -> versionVerdict(source.version)
        is TypeScriptSource.Declared -> source.resolvedRange?.let { rangeVerdict(it) } ?: Verdict.UNDECIDABLE
        is TypeScriptSource.Deno -> Verdict.UNDECIDABLE
        is TypeScriptSource.Loose -> Verdict.SUPPORTED
    }

/**
 * Which TypeScript governs [file]. The walk starts at the file's folder, or for a file inside `node_modules` (go to
 * definition into a dependency) at the folder that contains the outermost `node_modules` segment, the consuming
 * project, so a dependency's own package.json or nested `node_modules` never decide. It goes up to the filesystem
 * root; [stopAt] (normalized here, inclusive) ends it earlier, for tests. In each folder the first hit wins:
 * installed typescript, a package.json declaring it, a deno.json(c).
 */
fun resolveTypeScript(
    file: Path,
    stopAt: Path? = null,
): TypeScriptSource {
    val stop = stopAt?.toAbsolutePath()?.normalize()
    val consulted = mutableListOf<Path>()
    var folder = startFolder(file.toAbsolutePath().normalize())
    while (folder != null) {
        consulted.add(folder)
        val seen = consulted.toList()
        installedVersion(folder)?.let { (json, version) -> return TypeScriptSource.Installed(version, json, seen) }
        declaredRange(folder)?.let { (json, range) ->
            val catalog = resolveCatalog(range, folder, stop)
            val folders = seen + (catalog?.folders.orEmpty().filter { it !in seen })
            return TypeScriptSource.Declared(range, json, catalog?.value, catalog?.file, folders)
        }
        listOf("deno.json", "deno.jsonc").map { folder.resolve(it) }.firstOrNull { isFile(it) }?.let {
            return TypeScriptSource.Deno(it, seen)
        }
        if (folder == stop) break
        folder = folder.parent
    }
    return TypeScriptSource.Loose(consulted)
}

private fun startFolder(file: Path): Path? {
    val outermost = file.indexOfFirst { it.toString() == "node_modules" }
    if (outermost < 0) return file.parent
    val root = file.root
    return if (outermost == 0) root else root?.resolve(file.subpath(0, outermost)) ?: file.subpath(0, outermost)
}

private fun installedVersion(folder: Path): Pair<Path, String>? {
    val json = folder.resolve("node_modules").resolve("typescript").resolve("package.json")
    val version = readJson(json)?.get("version")?.takeIf { it.isString }?.asString()
    return version?.takeIf { it.isNotBlank() }?.let { json to it }
}

private fun declaredRange(folder: Path): Pair<Path, String>? {
    val json = folder.resolve("package.json")
    val root = readJson(json) ?: return null
    for (section in listOf("devDependencies", "dependencies")) {
        val range =
            root
                .get(section)
                ?.get("typescript")
                ?.takeIf { it.isString }
                ?.asString()
        if (range != null) return json to range
    }
    return null
}

/** [value] null: no entry; [file] is the catalog source; [folders] the folders looked at, from the declaring one up. */
private class CatalogResult(
    val value: String?,
    val file: Path?,
    val folders: List<Path>,
)

/**
 * [range] itself unless it is a `catalog:` reference. Then the nearest folder (from the declaring one up) with a
 * pnpm-workspace.yaml or a package.json holding a catalog is the only source: no entry there means null, outer sources
 * are never consulted.
 */
private fun resolveCatalog(
    range: String,
    from: Path,
    stop: Path?,
): CatalogResult? {
    val spec = range.trim()
    if (!spec.startsWith("catalog:")) return CatalogResult(range, null, emptyList())
    val name = spec.removePrefix("catalog:").trim().ifEmpty { "default" }
    val visited = mutableListOf<Path>()
    var folder: Path? = from
    while (folder != null) {
        visited.add(folder)
        val yaml = folder.resolve("pnpm-workspace.yaml").takeIf { isFile(it) }
        val json = folder.resolve("package.json")
        val root = readJson(json)?.takeIf { hasCatalog(it) }
        if (yaml != null || root != null) {
            val fromYaml = yaml?.let { pnpmCatalogEntry(it, name) }
            val fromJson = root?.let { jsonCatalogEntry(it, name) }
            val value = fromYaml ?: fromJson
            val source = if (fromYaml != null || root == null) yaml else json
            return CatalogResult(value, source, visited)
        }
        if (folder == stop) break
        folder = folder.parent
    }
    return CatalogResult(null, null, visited)
}

private fun hasCatalog(root: JsonNode): Boolean =
    listOfNotNull(root, root.get("workspaces")?.takeIf { it.isObject }).any { it.has("catalog") || it.has("catalogs") }

/** Bun and Yarn: `catalog`/`catalogs` at the top or under `workspaces`. */
private fun jsonCatalogEntry(
    root: JsonNode,
    name: String,
): String? {
    val nodes = listOfNotNull(root, root.get("workspaces")?.takeIf { it.isObject })
    for (node in nodes) {
        val catalog =
            if (name == "default") {
                node.get("catalog") ?: node.get("catalogs")?.get("default")
            } else {
                node.get("catalogs")?.get(name)
            }
        val value = catalog?.get("typescript")?.takeIf { it.isString }?.asString()
        if (value != null) return value
    }
    return null
}

private fun pnpmCatalogEntry(
    yaml: Path,
    name: String,
): String? {
    val text = readText(yaml) ?: return null
    return try {
        val root = Yaml(SafeConstructor(LoaderOptions())).load<Any?>(text) as? Map<*, *> ?: return null
        val catalogs = root["catalogs"] as? Map<*, *>
        val catalog = if (name == "default") root["catalog"] ?: catalogs?.get("default") else catalogs?.get(name)
        (catalog as? Map<*, *>)?.get("typescript")?.toString()
    } catch (_: Exception) {
        null
    }
}

private fun isFile(path: Path): Boolean =
    try {
        Files.isRegularFile(path)
    } catch (_: Exception) {
        false
    }

private fun readText(path: Path): String? =
    try {
        if (!Files.isRegularFile(path) || Files.size(path) > MAX_CONFIG_BYTES) {
            null
        } else {
            Files.readString(path).removePrefix(BOM)
        }
    } catch (_: Exception) {
        null
    }

private fun readJson(path: Path): JsonNode? {
    val text = readText(path) ?: return null
    return try {
        mapper.readTree(text)?.takeIf { it.isObject }
    } catch (_: Exception) {
        null
    }
}
