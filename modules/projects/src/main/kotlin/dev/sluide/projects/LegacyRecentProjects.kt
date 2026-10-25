package dev.sluide.projects

import dev.sluide.projectdata.LegacyProjectData
import dev.sluide.projectdata.ProjectDataStore
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.nio.file.Path

private val log = System.getLogger(LegacyRecentProjects::class.java.name)

/**
 * Moves the old `~/.sluide/projects.properties` (key: folder path, value: epoch milliseconds of the last opening) into
 * the project data as `lastOpened` (ADR 0090). A later time already stored is kept.
 */
@Component
class LegacyRecentProjects(
    override val file: Path,
) : LegacyProjectData {
    @Autowired
    constructor() : this(Path.of(System.getProperty("user.home"), ".sluide", "projects.properties"))

    override fun migrate(
        entries: Map<String, String>,
        store: ProjectDataStore,
    ): Boolean {
        var complete = true
        for ((key, value) in entries) {
            val millis = value.trim().toLongOrNull()
            val folder = runCatching { Path.of(key) }.getOrNull()
            if (millis == null || folder == null) {
                log.log(System.Logger.Level.WARNING, "Skipping the invalid recent project entry $key=$value")
                continue
            }
            val saved =
                store.update(folder) { data ->
                    val existing = data.get("lastOpened")?.takeIf { it.isNumber }?.asLong() ?: Long.MIN_VALUE
                    data.put("lastOpened", maxOf(existing, millis))
                }
            if (!saved) complete = false
        }
        return complete
    }
}
