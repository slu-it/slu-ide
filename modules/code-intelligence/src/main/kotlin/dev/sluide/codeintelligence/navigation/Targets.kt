package dev.sluide.codeintelligence.navigation

import dev.sluide.codeintelligence.binding.SourcePosition
import dev.sluide.codeintelligence.ui.PopupCell
import java.nio.file.Path

/** A place a chooser or the usages list offers, with the trimmed text of its line once known. */
internal data class NavigationTarget(
    val position: SourcePosition,
    val lineText: String? = null,
)

/**
 * How [target] shows in a list: `file.py:LINE` (1-based), then the path, relative to [projectFolder] when inside it
 * (absolute outside, e.g. site-packages), and the line's text once read.
 */
internal fun targetCell(
    target: NavigationTarget,
    projectFolder: Path?,
): PopupCell {
    val file = target.position.file
    val label = "${file.fileName}:${target.position.line + 1}"
    val path = shownPath(file, projectFolder)
    val secondary = target.lineText?.takeIf { it.isNotEmpty() }?.let { "$path  $it" } ?: path
    return PopupCell(label, secondary)
}

/** [file] relative to [projectFolder] when inside it, else absolute. */
internal fun shownPath(
    file: Path,
    projectFolder: Path?,
): String {
    val absolute = file.toAbsolutePath().normalize()
    val folder = projectFolder?.toAbsolutePath()?.normalize()
    return if (folder != null && absolute.startsWith(folder) && absolute != folder) {
        folder.relativize(absolute).joinToString("/")
    } else {
        absolute.toString()
    }
}

/** [positions] in the order the usages list shows them: those in [current] first, then by path, line and column. */
internal fun sortedUsages(
    positions: List<SourcePosition>,
    current: Path,
): List<SourcePosition> =
    positions.distinct().sortedWith(
        compareBy<SourcePosition> { !sameFile(it.file, current) }
            .thenBy {
                it.file
                    .toAbsolutePath()
                    .normalize()
                    .toString()
            }.thenBy { it.line }
            .thenBy { it.column },
    )
