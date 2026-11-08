package dev.sluide.librarycode

import java.nio.file.Files
import java.nio.file.Path

/** The rule by which a file is library code (ADR 0150); the order is the precedence when several match. */
enum class LibraryKind { VIRTUAL_ENV, SITE_PACKAGES, PYPACKAGES, STANDARD_LIBRARY, TYPESHED }

/** Where a library file lies: [root] is the library folder (the venv folder, site-packages, …), [kind] its rule. */
data class LibraryLocation(
    val root: Path,
    val kind: LibraryKind,
) {
    /**
     * For banners: "virtual environment .venv", "site-packages" (or "dist-packages"), "__pypackages__", "Python standard
     * library", "ty's typeshed stubs" (other typeshed folders: "typeshed stubs").
     */
    val description: String
        get() =
            when (kind) {
                LibraryKind.VIRTUAL_ENV -> {
                    "virtual environment ${root.fileName}"
                }

                LibraryKind.SITE_PACKAGES -> {
                    root.fileName.toString()
                }

                LibraryKind.PYPACKAGES -> {
                    PYPACKAGES
                }

                LibraryKind.STANDARD_LIBRARY -> {
                    "Python standard library"
                }

                LibraryKind.TYPESHED -> {
                    if (root.parent?.fileName?.toString() == VENDORED) "ty's typeshed stubs" else "typeshed stubs"
                }
            }
}

private const val PYVENV_CFG = "pyvenv.cfg"
private const val PYPACKAGES = "__pypackages__"
private const val TYPESHED = "typeshed"
private const val VENDORED = "vendored"
private val PACKAGE_FOLDERS = setOf("site-packages", "dist-packages")
private val LIB_FOLDERS = setOf("lib", "lib64")
private val TYPESHED_CHILDREN = setOf("stdlib", "stubs")

/** `python3.12`, `python3.13t`: the standard library folder under `lib`. */
private val PYTHON_VERSION_FOLDER = Regex("""python\d+\.\d+[a-z]*""")

/**
 * Tells whether a file is Python library code by its path (ADR 0150). Each [LibraryKind] has its rule, matched on the
 * file's ancestor folders:
 * - [LibraryKind.VIRTUAL_ENV]: the nearest ancestor holding a `pyvenv.cfg` file;
 * - [LibraryKind.SITE_PACKAGES]: the nearest ancestor named `site-packages` or `dist-packages`;
 * - [LibraryKind.PYPACKAGES]: the nearest ancestor named `__pypackages__` (PEP 582);
 * - [LibraryKind.STANDARD_LIBRARY]: the nearest `pythonX.Y` folder in a `lib` or `lib64` folder, or a Windows-style
 *   `Lib` folder beside a `python.exe`;
 * - [LibraryKind.TYPESHED]: the nearest `typeshed` folder in a `vendored` folder (ty's cache) or holding the next
 *   folder `stdlib` or `stubs`.
 *
 * The first matching rule in [LibraryKind]'s order wins, so a venv's site-packages reads as the venv, and a
 * site-packages inside the standard library folder as site-packages. No file is library code when one of its matching
 * roots is the project folder or contains it: the user opened that library as a project, or keeps the project inside
 * it (an old-style venv holding the sources), and edits it on purpose.
 *
 * Paths are made absolute and normalized, symbolic links are not followed: no I/O but the [isFile] lookups.
 */
object LibraryCode {
    /**
     * The library [file] lies in, or null; null too when the library root contains [projectFolder] (or is it). Pure
     * path logic plus [isFile] lookups for pyvenv.cfg. Any thread.
     */
    fun locate(
        file: Path,
        projectFolder: Path?,
        isFile: (Path) -> Boolean = Files::isRegularFile,
    ): LibraryLocation? {
        val absolute = file.toAbsolutePath().normalize()
        val project = projectFolder?.toAbsolutePath()?.normalize()
        val found = mutableMapOf<LibraryKind, Path>()
        var child: Path = absolute
        var folder: Path? = absolute.parent
        while (folder != null) {
            for (kind in kindsOf(folder, child, isFile)) {
                found.putIfAbsent(kind, folder)
            }
            child = folder
            folder = folder.parent
        }
        if (project != null && found.values.any { project.startsWith(it) }) return null
        return LibraryKind.entries.firstNotNullOfOrNull { kind -> found[kind]?.let { LibraryLocation(it, kind) } }
    }

    /**
     * Whether [folder] itself is a library root shown in the explorer: VIRTUAL_ENV (it holds pyvenv.cfg) or PYPACKAGES
     * (named __pypackages__); null otherwise. Any thread.
     */
    fun libraryRootKind(
        folder: Path,
        isFile: (Path) -> Boolean = Files::isRegularFile,
    ): LibraryKind? =
        when {
            folder.fileName?.toString() == PYPACKAGES -> LibraryKind.PYPACKAGES
            isFile(folder.resolve(PYVENV_CFG)) -> LibraryKind.VIRTUAL_ENV
            else -> null
        }

    /** The rules [folder] is the root of, for a file under its [child] folder (or the file itself). */
    private fun kindsOf(
        folder: Path,
        child: Path,
        isFile: (Path) -> Boolean,
    ): List<LibraryKind> {
        val name = folder.fileName?.toString() ?: return emptyList()
        val parentName = folder.parent?.fileName?.toString()
        return buildList {
            if (isFile(folder.resolve(PYVENV_CFG))) add(LibraryKind.VIRTUAL_ENV)
            if (name in PACKAGE_FOLDERS) add(LibraryKind.SITE_PACKAGES)
            if (name == PYPACKAGES) add(LibraryKind.PYPACKAGES)
            if (isStandardLibrary(folder, name, parentName, isFile)) add(LibraryKind.STANDARD_LIBRARY)
            if (name == TYPESHED && (parentName == VENDORED || child.fileName.toString() in TYPESHED_CHILDREN)) {
                add(LibraryKind.TYPESHED)
            }
        }
    }

    private fun isStandardLibrary(
        folder: Path,
        name: String,
        parentName: String?,
        isFile: (Path) -> Boolean,
    ): Boolean =
        (PYTHON_VERSION_FOLDER.matches(name) && parentName in LIB_FOLDERS) ||
            (name.equals("lib", ignoreCase = true) && folder.parent?.let { isFile(it.resolve("python.exe")) } == true)
}
