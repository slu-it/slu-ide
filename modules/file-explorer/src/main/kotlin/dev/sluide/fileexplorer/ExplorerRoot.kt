package dev.sluide.fileexplorer

import java.nio.file.Path

/**
 * A folder the explorer shows as a further top-level node after the opened folder, under [label] instead of its name.
 * [folder] must exist when the explorer is opened; otherwise it is shown as a file.
 */
data class ExplorerRoot(
    val folder: Path,
    val label: String,
)
