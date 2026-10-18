package dev.sluide.fileexplorer

import java.nio.file.Path

/**
 * An action the window adds to the explorer's context menu, run on a folder; [perform] may throw an IOException or
 * UncheckedIOException, whose message is shown.
 */
class FolderAction(
    val label: String,
    val perform: (folder: Path) -> FolderActionResult,
)

/** What a [FolderAction] did: the file to select and open (if any), and a note to show (if any). */
data class FolderActionResult(
    val open: Path?,
    val note: String? = null,
)
