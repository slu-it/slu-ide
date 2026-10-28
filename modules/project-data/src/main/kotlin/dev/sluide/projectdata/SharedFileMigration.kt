package dev.sluide.projectdata

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private val log = System.getLogger(ProjectDataStore::class.java.name)

/** The name the shared file had before it became the shared data file (ADR 0101). */
private const val OLD_NAME = "outside-projects.json"

/**
 * Moves the old `outside-projects.json` next to [sharedFile] to [sharedFile], if that does not exist yet. When both
 * exist, [sharedFile] wins and the old file stays untouched (a warning is logged); a failed move is logged and leaves
 * the old file for the next start.
 */
internal fun migrateSharedFile(sharedFile: Path) {
    val old = sharedFile.resolveSibling(OLD_NAME)
    if (old == sharedFile || !Files.exists(old)) return
    if (Files.exists(sharedFile)) {
        log.log(System.Logger.Level.WARNING, "Both $old and $sharedFile exist, using $sharedFile and leaving $old")
        return
    }
    try {
        try {
            Files.move(old, sharedFile, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(old, sharedFile)
        }
        log.log(System.Logger.Level.INFO, "Moved $old to $sharedFile")
    } catch (e: IOException) {
        log.log(System.Logger.Level.WARNING, "Cannot move $old to $sharedFile, keeping it for the next start", e)
    }
}
