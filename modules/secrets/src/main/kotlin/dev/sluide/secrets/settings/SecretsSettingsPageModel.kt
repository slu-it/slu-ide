package dev.sluide.secrets.settings

import dev.sluide.secrets.SavedSecretEntry
import dev.sluide.secrets.SavedSecretSource
import dev.sluide.secrets.Secrets
import java.io.IOException

/**
 * What the Secrets page shows and does, without JavaFX: the store line, the entries of each source with their pending
 * removals (applied only by [apply]) and Forget All.
 */
internal class SecretsSettingsPageModel(
    private val secrets: Secrets,
    private val sources: () -> List<SavedSecretSource>,
) {
    /** The entries of one source as loaded. */
    class Section(
        val source: SavedSecretSource,
        val entries: List<SavedSecretEntry>,
    )

    var sections: List<Section> = emptyList()
        private set

    private val pending = mutableSetOf<Pair<Int, String>>()

    /** Re-reads the entries of every source and drops the pending removals; sources without entries are left out. */
    fun load() {
        pending.clear()
        sections = sources().map { Section(it, it.entries()) }.filter { it.entries.isNotEmpty() }
    }

    fun isPending(
        section: Int,
        id: String,
    ) = (section to id) in pending

    /** Marks the entry for removal, or unmarks it; returns whether it is marked now. */
    fun toggle(
        section: Int,
        id: String,
    ): Boolean = if (pending.remove(section to id)) false else pending.add(section to id)

    /**
     * Removes the marked entries from their sources. A source that fails (or throws) keeps its marks on the entries
     * that are still there. @throws IOException when a source cannot remove them.
     */
    fun apply() {
        val failed = mutableMapOf<String, Set<String>>()
        sections.forEachIndexed { index, section ->
            val ids =
                section.entries
                    .map { it.id }
                    .filter { isPending(index, it) }
                    .toSet()
            if (ids.isNotEmpty() && !removed(section.source, ids)) failed[section.source.title] = ids
        }
        load()
        sections.forEachIndexed { index, section ->
            val marked = failed[section.source.title] ?: return@forEachIndexed
            section.entries.filter { it.id in marked }.forEach { pending += index to it.id }
        }
        if (failed.isNotEmpty()) throw IOException("Could not remove saved secrets: ${failed.keys.joinToString()}")
    }

    private fun removed(
        source: SavedSecretSource,
        ids: Set<String>,
    ) = try {
        source.remove(ids)
    } catch (_: RuntimeException) {
        false
    }

    /** The first line of the page. */
    fun storeLine(): String {
        val reason = secrets.saveUnavailableReason()
        return when {
            reason == null -> "Store: ${secrets.storeName}"
            secrets.accessDenied -> "Store: ${secrets.storeName}, access denied"
            else -> "Not available: $reason"
        }
    }

    /** Whether the store line is the denied one, which offers Try Again. */
    fun denied(): Boolean = secrets.accessDenied

    /** Forget All needs a store that is available or denied; without one (no store, needs secret-tool) it is off. */
    fun canForgetAll(): Boolean =
        secrets.storeName != null && (secrets.saveUnavailableReason() == null || secrets.accessDenied)

    fun tryAgain() = secrets.tryAgain()

    /** Forgets the key; blocks, so not on the FX thread. Returns the message for the status label. */
    fun forgetAll(): String {
        val store = secrets.storeName
        return if (secrets.forgetAll()) "Saved secrets forgotten." else "The key could not be deleted from $store."
    }
}
