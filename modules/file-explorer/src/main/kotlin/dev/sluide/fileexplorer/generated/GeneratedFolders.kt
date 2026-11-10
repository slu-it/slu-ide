package dev.sluide.fileexplorer.generated

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** Why a folder counts as generated, shown in a tooltip: "Generated folder (<text>)". */
internal data class GeneratedReason(
    val text: String,
)

/**
 * One catalogue row: folders named [folders] count as generated when one of [projectFiles] exists next to them.
 * Names may start or end with a single `*`. No [projectFiles] means the name alone is enough; [reason] then replaces
 * the default text "<folder> for <project file>". With a [parent] the row is a two-segment rule (`vendor/bundle`):
 * the folder's parent must be named [parent] and the project file is looked up in the grandparent.
 */
internal class GeneratedRule(
    val folders: List<String>,
    val projectFiles: List<String> = emptyList(),
    val reason: String? = null,
    val parent: String? = null,
)

private fun rule(
    folders: String,
    projectFiles: String = "",
    reason: String? = null,
    parent: String? = null,
) = GeneratedRule(folders.split(' '), projectFiles.split(' ').filter { it.isNotEmpty() }, reason, parent)

private const val PYTHON_FILES = "pyproject.toml setup.py setup.cfg"
private const val GRADLE_FILES = "build.gradle build.gradle.kts settings.gradle settings.gradle.kts"

/** Build-tool folders by ecosystem; the first matching row wins. Not generated on purpose: see docs. */
internal val generatedCatalogue: List<GeneratedRule> =
    listOf(
        // JavaScript / TypeScript
        rule(
            "node_modules .next .nuxt .output .svelte-kit .angular .turbo .parcel-cache .docusaurus .astro " +
                ".pnpm-store dist coverage",
            "package.json",
        ),
        rule("bower_components", "bower.json"),
        // Java / Kotlin / Groovy (Gradle)
        rule("build .gradle .kotlin", GRADLE_FILES),
        // Java (Maven)
        rule("target", "pom.xml"),
        // Scala
        rule("target .bsp .bloop .metals", "*.sbt"),
        rule("target", "build.sbt", parent = "project"),
        // Clojure
        rule("target", "project.clj deps.edn"),
        rule(".cpcache", "deps.edn"),
        rule(".shadow-cljs", "shadow-cljs.edn"),
        // Python
        rule("__pycache__ .pytest_cache .mypy_cache .ruff_cache .hypothesis", reason = "Python cache"),
        rule(".ipynb_checkpoints", reason = "Jupyter checkpoints"),
        rule("htmlcov", reason = "coverage report"),
        rule(".venv .tox __pypackages__ build dist *.egg-info", PYTHON_FILES),
        rule(".tox", "tox.ini"),
        rule(".nox", "noxfile.py"),
        // Rust
        rule("target", "Cargo.toml"),
        // .NET
        rule("bin obj", "*.csproj *.fsproj *.vbproj"),
        rule(".vs", "*.sln *.slnx"),
        // C / C++ (CMake)
        rule("cmake-build-*", "CMakeLists.txt"),
        // Ruby
        rule(".bundle coverage", "Gemfile"),
        rule("bundle", "Gemfile", parent = "vendor"),
        // PHP
        rule("vendor", "composer.json"),
        // Swift
        rule(".build .swiftpm", "Package.swift"),
        rule("Pods", "Podfile"),
        rule("Build", "Cartfile", parent = "Carthage"),
        // Dart / Flutter
        rule(".dart_tool build", "pubspec.yaml"),
        // Perl
        rule("local", "cpanfile"),
        rule("blib _build", "Makefile.PL Build.PL"),
        // Lua
        rule("lua_modules", "*.rockspec"),
        // D
        rule(".dub", "dub.json dub.sdl"),
        // Fortran
        rule("build", "fpm.toml"),
        // Terraform
        rule(".terraform", "*.tf"),
    )

private const val CACHEDIR_TAG = "CACHEDIR.TAG"
private const val CACHEDIR_SIGNATURE = "Signature: 8a477f597d28d172789f06886806bc55"
private const val GITIGNORE = ".gitignore"
private const val PYVENV_CFG = "pyvenv.cfg"
private const val CMAKE_CACHE = "CMakeCache.txt"
private const val MESON_PRIVATE = "meson-private"
private const val MAX_READ_BYTES = 4096

/**
 * [folder]'s reason to count as generated, or null. [siblingNames] are the names of all entries of [folder]'s parent
 * (the parent's listing, which the caller already has), [folder]'s own name included.
 *
 * The catalogue is checked first (no I/O, except one lookup per project file in the grandparent for two-segment
 * rules), then the specific self-markers (`pyvenv.cfg`, `CMakeCache.txt`, `meson-private`), then the generic ones
 * (`CACHEDIR.TAG`, `.gitignore`). That is at most five lookups in the folder and at most two small reads of regular
 * files up to 4 KB. Never throws: unreadable means "not a marker".
 */
internal fun generatedReason(
    folder: Path,
    siblingNames: Set<String>,
): GeneratedReason? {
    val name = folder.fileName?.toString() ?: return null
    return (catalogueReason(folder, name, siblingNames) ?: selfMarkerReason(folder))?.let(::GeneratedReason)
}

private fun selfMarkerReason(folder: Path): String? =
    when {
        isRegularFile(folder.resolve(PYVENV_CFG)) -> "virtual environment (pyvenv.cfg)"
        isRegularFile(folder.resolve(CMAKE_CACHE)) -> "CMake build folder"
        isDirectory(folder.resolve(MESON_PRIVATE)) -> "Meson build folder"
        readStart(folder.resolve(CACHEDIR_TAG))?.startsWith(CACHEDIR_SIGNATURE) == true -> "marked by CACHEDIR.TAG"
        readStart(folder.resolve(GITIGNORE))?.let(::ignoresEverything) == true -> "marked by its .gitignore"
        else -> null
    }

private fun catalogueReason(
    folder: Path,
    name: String,
    siblingNames: Set<String>,
): String? {
    for (rule in generatedCatalogue) {
        if (rule.folders.none { matches(it, name) }) continue
        if (rule.projectFiles.isEmpty()) return rule.reason
        if (rule.parent == null) {
            val file = firstMatch(rule.projectFiles, siblingNames) ?: continue
            return rule.reason ?: "$name for $file"
        }
        val parent = folder.parent ?: continue
        if (parent.fileName?.toString() != rule.parent) continue
        val file = rule.projectFiles.firstOrNull { exists(parent.resolveSibling(it)) } ?: continue
        return rule.reason ?: "${rule.parent}/$name for $file"
    }
    return null
}

private fun firstMatch(
    patterns: List<String>,
    names: Set<String>,
): String? = patterns.firstNotNullOfOrNull { pattern -> names.firstOrNull { matches(pattern, it) } }

/** Case-sensitive match of [name] against [pattern], which may start or end with one `*`. */
private fun matches(
    pattern: String,
    name: String,
): Boolean =
    when {
        pattern.startsWith('*') -> name.length > pattern.length - 1 && name.endsWith(pattern.substring(1))
        pattern.endsWith('*') -> name.length > pattern.length - 1 && name.startsWith(pattern.dropLast(1))
        else -> name == pattern
    }

private fun exists(path: Path): Boolean =
    try {
        Files.exists(path)
    } catch (_: SecurityException) {
        false
    }

private fun isRegularFile(path: Path): Boolean =
    try {
        Files.isRegularFile(path)
    } catch (_: SecurityException) {
        false
    }

private fun isDirectory(path: Path): Boolean =
    try {
        Files.isDirectory(path)
    } catch (_: SecurityException) {
        false
    }

/**
 * A regular file's content as text when it is at most [MAX_READ_BYTES] long, else null (missing, unreadable, bigger,
 * or not a regular file: opening a named pipe would block forever).
 */
private fun readStart(file: Path): String? =
    try {
        val attributes = Files.readAttributes(file, BasicFileAttributes::class.java)
        if (!attributes.isRegularFile || attributes.size() > MAX_READ_BYTES) {
            null
        } else {
            Files.newInputStream(file).use { input ->
                val bytes = input.readNBytes(MAX_READ_BYTES + 1)
                if (bytes.size > MAX_READ_BYTES) null else String(bytes, Charsets.UTF_8)
            }
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

private fun ignoresEverything(content: String): Boolean =
    content
        .removePrefix("\uFEFF")
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith('#') }
        .toList() == listOf("*")
