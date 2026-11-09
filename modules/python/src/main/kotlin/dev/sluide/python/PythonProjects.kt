package dev.sluide.python

import java.nio.file.Files
import java.nio.file.Path

/*
 * Where a Python file's project and virtual environment are (ADR 0151). Pure file-system functions; every IO error
 * counts as "absent".
 */

private const val VENV_CONFIG = "pyvenv.cfg"
private const val DEFAULT_VENV = ".venv"

private fun isFile(path: Path): Boolean =
    try {
        Files.isRegularFile(path)
    } catch (_: Exception) {
        false
    }

private fun isDirectory(path: Path): Boolean =
    try {
        Files.isDirectory(path)
    } catch (_: Exception) {
        false
    }

/** The folders from [file]'s parent up to [windowFolder], both included; empty for a file outside it. */
private fun foldersUpTo(
    file: Path,
    windowFolder: Path,
): List<Path> {
    val top = windowFolder.toAbsolutePath().normalize()
    var current: Path? = file.toAbsolutePath().normalize().parent
    val result = mutableListOf<Path>()
    while (current != null && current.startsWith(top)) {
        result.add(current)
        if (current == top) return result
        current = current.parent
    }
    return emptyList()
}

/**
 * The nearest folder from [file]'s parent up to [windowFolder] (included, never above) holding a `uv.lock` or a
 * virtual environment ([venvOf]); `null` for a file outside the window folder or without such a folder. Members of a
 * uv workspace have only a `pyproject.toml` and so resolve to the workspace root.
 */
internal fun projectRoot(
    file: Path,
    windowFolder: Path,
): Path? = foldersUpTo(file, windowFolder).firstOrNull { isFile(it.resolve("uv.lock")) || venvOf(it) != null }

/**
 * The virtual environment in [folder]: a child directory with a `pyvenv.cfg`, `.venv` first (one stat in the common
 * case), then the others by name.
 */
internal fun venvOf(folder: Path): Path? {
    val default = folder.resolve(DEFAULT_VENV)
    if (isFile(default.resolve(VENV_CONFIG))) return default
    val children =
        try {
            Files.newDirectoryStream(folder).use { stream -> stream.toList() }
        } catch (_: Exception) {
            return null
        }
    return children
        .filter { isFile(it.resolve(VENV_CONFIG)) }
        .minByOrNull { it.fileName.toString() }
}

/** The `major.minor` Python version of [venv] from its `pyvenv.cfg` (`version_info` of uv, else `version`). */
internal fun pythonVersion(venv: Path): String? {
    val entries =
        try {
            Files.readAllLines(venv.resolve(VENV_CONFIG)).mapNotNull { line ->
                val index = line.indexOf('=')
                if (index < 0) null else line.substring(0, index).trim() to line.substring(index + 1).trim()
            }
        } catch (_: Exception) {
            return null
        }.toMap()
    val full = entries["version_info"] ?: entries["version"] ?: return null
    return Regex("""^(\d+)\.(\d+)""").find(full)?.let { "${it.groupValues[1]}.${it.groupValues[2]}" }
}

/** The existing `site-packages` folders of [venv] (`lib`, `lib64`, Windows `Lib`), sorted. */
internal fun sitePackages(venv: Path): List<Path> {
    val found = mutableListOf<Path>()
    for (lib in listOf("lib", "lib64")) {
        val children =
            try {
                Files.newDirectoryStream(venv.resolve(lib), "python*").use { it.toList() }
            } catch (_: Exception) {
                emptyList()
            }
        found.addAll(children.map { it.resolve("site-packages") })
    }
    found.add(venv.resolve("Lib").resolve("site-packages"))
    return found.filter { isDirectory(it) }.sorted()
}

/**
 * The folder that lacks a virtual environment for [file]: its project root without one, or, with no project root, the
 * nearest folder up to [windowFolder] with a `pyproject.toml` or `uv.lock`; else `null`.
 */
internal fun missingVenvFolder(
    file: Path,
    windowFolder: Path,
): Path? {
    val root = projectRoot(file, windowFolder)
    if (root != null) return root.takeIf { venvOf(it) == null }
    return foldersUpTo(file, windowFolder).firstOrNull {
        isFile(it.resolve("pyproject.toml")) || isFile(it.resolve("uv.lock"))
    }
}
