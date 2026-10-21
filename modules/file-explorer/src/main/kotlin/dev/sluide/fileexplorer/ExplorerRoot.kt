package dev.sluide.fileexplorer

import dev.sluide.fileicons.FileIcon
import java.nio.file.Path

/**
 * A folder the explorer shows as a further top-level node after the opened folder, under [label] instead of its name.
 * [folder] must exist when the explorer is opened; otherwise it is shown as a file. [folderIcons] replaces the icons
 * of the folder and the folders in it; `null` means the normal folder icons.
 */
data class ExplorerRoot(
    val folder: Path,
    val label: String,
    val folderIcons: FolderIcons? = null,
)

/** The folder icons of an [ExplorerRoot]: [top] for the root itself (expanded or not), [closed]/[open] for the folders in it. */
data class FolderIcons(
    val top: FileIcon,
    val closed: FileIcon,
    val open: FileIcon,
)
