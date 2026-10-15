package dev.sluide.projects

import java.nio.file.Path
import java.time.Instant

/** A project folder that was opened before, and when it was opened last. */
data class RecentProject(
    val folder: Path,
    val lastOpened: Instant,
) {
    /** The folder's name, or its whole path for a file system root. */
    val name: String get() = folder.fileName?.toString() ?: folder.toString()

    /** The folder's path for display: `~` for [home], `~/a/b` below it, the absolute path elsewhere. */
    fun displayPath(home: Path): String {
        val base = home.toAbsolutePath().normalize()
        return when {
            folder == base -> "~"
            folder.startsWith(base) -> "~/" + base.relativize(folder).joinToString("/")
            else -> folder.toString()
        }
    }
}
