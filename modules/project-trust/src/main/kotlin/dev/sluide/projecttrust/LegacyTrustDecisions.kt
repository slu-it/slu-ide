package dev.sluide.projecttrust

import dev.sluide.projectdata.LegacyProjectData
import dev.sluide.projectdata.ProjectDataStore
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.nio.file.Path

private val log = System.getLogger(LegacyTrustDecisions::class.java.name)

private const val TRUSTED = "trusted:"
private const val UNTRUSTED = "untrusted:"

/**
 * Moves the old `~/.sluide/project-trust.properties` (key: folder path, value: `trusted:` or `untrusted:` and the
 * fingerprint) into the project data as `trust` (ADR 0090). A decision already stored is never overwritten.
 */
@Component
class LegacyTrustDecisions(
    override val file: Path,
) : LegacyProjectData {
    @Autowired
    constructor() : this(Path.of(System.getProperty("user.home"), ".sluide", "project-trust.properties"))

    override fun migrate(
        entries: Map<String, String>,
        store: ProjectDataStore,
    ): Boolean {
        var complete = true
        for ((key, value) in entries) {
            val trusted =
                when {
                    value.startsWith(TRUSTED) -> true
                    value.startsWith(UNTRUSTED) -> false
                    else -> null
                }
            val folder = runCatching { Path.of(key) }.getOrNull()
            if (trusted == null || folder == null) {
                log.log(System.Logger.Level.WARNING, "Skipping the invalid trust decision entry $key")
                continue
            }
            val fingerprint = value.removePrefix(if (trusted) TRUSTED else UNTRUSTED)
            val saved =
                store.update(folder) { data ->
                    if (!data.has(TRUST_FIELD)) {
                        data.putObject(TRUST_FIELD).put(TRUSTED_FIELD, trusted).put(FINGERPRINT_FIELD, fingerprint)
                    }
                }
            if (!saved) complete = false
        }
        return complete
    }
}
