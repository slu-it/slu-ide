package dev.sluide.projects

import dev.sluide.projectdata.ProjectDataStore
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.time.Instant

private const val LAST_OPENED = "lastOpened"

/**
 * Remembers the opened project folders in the [store]: the project's JSON file holds the field `lastOpened`, the epoch
 * milliseconds of the last time it was opened (ADR 0090). Problems with the files are logged by the store, never
 * thrown.
 */
@Component
class RecentProjects(
    private val store: ProjectDataStore,
) {
    /** The remembered projects, newest first; projects without a `lastOpened` (only other data) are not listed. */
    fun list(): List<RecentProject> =
        store
            .projects()
            .mapNotNull { project ->
                val millis =
                    project.data
                        .get(LAST_OPENED)
                        ?.takeIf { it.isNumber }
                        ?.asLong() ?: return@mapNotNull null
                RecentProject(project.folder, Instant.ofEpochMilli(millis))
            }.sortedByDescending { it.lastOpened }

    /** Remembers [folder] as opened at [at]. */
    fun opened(
        folder: Path,
        at: Instant = Instant.now(),
    ) {
        store.update(folder) { it.put(LAST_OPENED, at.toEpochMilli()) }
    }

    /**
     * Forgets [folder]: deletes the project's whole file, so its trust decision and argument histories are forgotten
     * too; the folder itself is never touched.
     */
    fun remove(folder: Path) = store.remove(folder)
}
