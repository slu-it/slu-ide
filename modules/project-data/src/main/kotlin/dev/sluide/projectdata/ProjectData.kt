package dev.sluide.projectdata

import tools.jackson.databind.node.ObjectNode
import java.nio.file.Path

/** One remembered project: its folder and a read-only copy of its data. */
data class ProjectData(
    val folder: Path,
    /** A copy; changes to it are not saved. */
    val data: ObjectNode,
)

/**
 * Migrates one old store into the project data (implemented by the owning module).
 *
 * Implementations are beans of their own that do not inject [ProjectDataStore]: the store collects them, so injecting
 * it would be a circular reference. The store is handed to [migrate] instead.
 */
interface LegacyProjectData {
    /** The old properties file, e.g. `~/.sluide/projects.properties`. */
    val file: Path

    /**
     * Writes [entries] (the old file's properties) into [store]; `true` when all were written. Never overwrites a value
     * already present.
     *
     * Runs once per store under its lock, on the thread of the store's first access: it must not take other locks. It
     * writes through [store]'s methods, but never from inside one of their `change` functions (that nested write would
     * be overwritten).
     */
    fun migrate(
        entries: Map<String, String>,
        store: ProjectDataStore,
    ): Boolean
}
