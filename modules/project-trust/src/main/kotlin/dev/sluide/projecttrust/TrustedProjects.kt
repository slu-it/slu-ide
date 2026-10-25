package dev.sluide.projecttrust

import dev.sluide.projectdata.ProjectDataStore
import org.springframework.stereotype.Component
import java.nio.file.Path

internal const val TRUST_FIELD = "trust"
internal const val TRUSTED_FIELD = "trusted"
internal const val FINGERPRINT_FIELD = "fingerprint"

/**
 * Remembers the user's trust decisions in the [store] (ADR 0090): the project's JSON file holds the field
 * `trust: { "trusted": Boolean, "fingerprint": String }`, the decision and the fingerprint of the findings it was made
 * for. Problems with the files are logged by the store, never thrown.
 */
@Component
class TrustedProjects(
    private val store: ProjectDataStore,
) {
    /**
     * The decision stored for [folder] about the findings with [fingerprint]: `true` trusted, `false` not trusted,
     * `null` if there is none, it was about other findings, or the stored data is malformed.
     */
    fun decision(
        folder: Path,
        fingerprint: String,
    ): Boolean? {
        val trust = store.read(folder)?.get(TRUST_FIELD) ?: return null
        val stored = trust.get(FINGERPRINT_FIELD)?.takeIf { it.isString }?.stringValue()
        val trusted = trust.get(TRUSTED_FIELD)?.takeIf { it.isBoolean }?.asBoolean()
        return if (stored == fingerprint) trusted else null
    }

    /**
     * Remembers that the user [trusted] (or not) the findings with [fingerprint] in [folder], replacing an earlier
     * decision. Called under `ProjectTrust`'s lock: the store's lock comes after it.
     */
    fun remember(
        folder: Path,
        fingerprint: String,
        trusted: Boolean,
    ) {
        store.update(folder) {
            it.putObject(TRUST_FIELD).put(TRUSTED_FIELD, trusted).put(FINGERPRINT_FIELD, fingerprint)
        }
    }
}
