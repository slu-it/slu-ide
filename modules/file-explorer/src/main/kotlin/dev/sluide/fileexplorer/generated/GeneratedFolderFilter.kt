package dev.sluide.fileexplorer.generated

import java.nio.file.Files
import java.nio.file.Path

/**
 * Decides which folders of an explorer window are left out as generated (ADR 0158): the structural reason of
 * [generatedReason], corrected by git, and the folders the user revealed. Shared by all items of one view; FX thread.
 */
internal class GeneratedFolderFilter(
    /** Whether generated folders are hidden; read at every decision. */
    private val enabled: () -> Boolean,
) {
    /** Whether git ignores a folder; `null` if its status does not cover it. */
    var ignoredByGit: (Path) -> Boolean? = { null }

    private val revealed = mutableSetOf<Path>()

    /** The structural reason of [folder], one of the entries of a folder whose entry names are [siblingNames]. */
    fun structural(
        folder: Path,
        siblingNames: Set<String>,
    ): GeneratedReason? = if (Files.isDirectory(folder)) generatedReason(folder, siblingNames) else null

    /** [structural] unless git knows the folder is not ignored (tracked, or untracked but not ignored). */
    fun effective(
        folder: Path,
        structural: GeneratedReason,
    ): GeneratedReason? = structural.takeUnless { ignoredByGit(folder) == false }

    /** Whether [folder] with the effective [reason] is left out of the tree now. */
    fun isHidden(
        folder: Path,
        reason: GeneratedReason?,
    ): Boolean = reason != null && enabled() && folder !in revealed

    /** Shows [folder] although it is generated, until the exceptions are cleared. */
    fun unhide(folder: Path) {
        revealed.add(folder)
    }

    /** Forgets the revealed folders and returns them. */
    fun clearRevealed(): Set<Path> = revealed.toSet().also { revealed.clear() }
}
