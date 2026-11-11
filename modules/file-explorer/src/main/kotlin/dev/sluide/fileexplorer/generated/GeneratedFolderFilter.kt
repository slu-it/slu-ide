package dev.sluide.fileexplorer.generated

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * Decides which folders and files of an explorer window are left out as generated (ADR 0158): the structural reason of
 * [generatedReason], corrected by git, and the entries the user revealed. Shared by all items of one view; FX thread.
 */
internal class GeneratedFolderFilter(
    /** Whether generated folders and files are hidden; read at every decision. */
    private val enabled: () -> Boolean,
) {
    /** Whether git ignores a folder; `null` if its status does not cover it. */
    var ignoredByGit: (Path) -> Boolean? = { null }

    /** Whether git tracks a file: true when tracked, false when untracked or ignored, `null` if its status does not cover it. */
    var trackedByGit: (Path) -> Boolean? = { null }

    private val revealed = mutableSetOf<Path>()

    /** Whether [path] is a file of the file catalogue, by name alone. */
    fun isCatalogueFile(path: Path): Boolean = generatedFileReason(path) != null

    /** The structural reason of [path], one of the entries of a folder whose entry names are [siblingNames]. Never throws. */
    fun structural(
        path: Path,
        siblingNames: Set<String>,
    ): GeneratedReason? {
        val attributes =
            try {
                Files.readAttributes(path, BasicFileAttributes::class.java)
            } catch (_: IOException) {
                return null
            } catch (_: SecurityException) {
                return null
            }
        return when {
            attributes.isDirectory -> generatedReason(path, siblingNames)
            attributes.isRegularFile -> generatedFileReason(path)
            else -> null
        }
    }

    /**
     * [structural] unless git knows better: a folder is not generated when git does not ignore it (tracked, or untracked
     * but not ignored); a file is not generated when git tracks it.
     */
    fun effective(
        path: Path,
        structural: GeneratedReason,
    ): GeneratedReason? =
        if (structural.file) {
            structural.takeUnless { trackedByGit(path) == true }
        } else {
            structural.takeUnless { ignoredByGit(path) == false }
        }

    /**
     * Whether compacting middle folders leaves [entry] out of the count: a generated file by name alone while the
     * setting is on and it is not revealed (no git; a tracked one in a compacted middle folder is not visible,
     * ADR 0163).
     */
    fun hidesFileForCompacting(entry: Path): Boolean =
        enabled() && entry !in revealed && generatedFileReason(entry) != null && Files.isRegularFile(entry)

    /** Whether [path] with the effective [reason] is left out of the tree now. */
    fun isHidden(
        path: Path,
        reason: GeneratedReason?,
    ): Boolean = reason != null && enabled() && path !in revealed

    /** Shows [path] although it is generated, until the exceptions are cleared. */
    fun unhide(path: Path) {
        revealed.add(path)
    }

    /** Forgets the revealed folders and files and returns them. */
    fun clearRevealed(): Set<Path> = revealed.toSet().also { revealed.clear() }
}
