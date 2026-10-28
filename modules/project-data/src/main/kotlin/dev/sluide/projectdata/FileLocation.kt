package dev.sluide.projectdata

import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString

internal fun normalized(path: Path): Path = path.toAbsolutePath().normalize()

/**
 * Where the per-file data of a file lives: in the file of the [project] under the [key] relative to it, or, with no
 * [project], in the shared file under the absolute path.
 */
internal data class FileLocation(
    val project: Path?,
    val key: String,
)

/** The location of [file]'s data: its project's file if it lies under [projectFolder], else the shared file. */
internal fun locate(
    file: Path,
    projectFolder: Path?,
): FileLocation {
    val absolute = normalized(file)
    val project = projectFolder?.let(::normalized)
    return if (project != null && absolute != project && absolute.startsWith(project)) {
        FileLocation(project, project.relativize(absolute).invariantSeparatorsPathString)
    } else {
        FileLocation(null, absolute.invariantSeparatorsPathString)
    }
}

/** The absolute file that the [key] of a per-file entry stands for in [project]'s file or, without, the shared file. */
internal fun fileOf(
    project: Path?,
    key: String,
): Path? =
    try {
        val path = if (project == null) Path.of(key) else project.resolve(key)
        if (path.isAbsolute) path.normalize() else null
    } catch (_: InvalidPathException) {
        null
    }
