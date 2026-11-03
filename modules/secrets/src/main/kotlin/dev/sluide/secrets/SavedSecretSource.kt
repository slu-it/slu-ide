package dev.sluide.secrets

/**
 * A feature's saved secrets that Settings → Secrets lists and removes one by one. Other modules contribute it as a
 * bean (the HTTP client's certificate passphrases; database passwords are managed on their connection cards).
 *
 * [entries] and [remove] read and write small JSON files and may run on the FX thread.
 */
interface SavedSecretSource {
    /** The section heading on the page, e.g. "HTTP client passphrases". */
    val title: String

    fun entries(): List<SavedSecretEntry>

    /** Removes the entries with these [ids]; `false` when they could not be removed. */
    fun remove(ids: Set<String>): Boolean
}

/** One saved secret: [id] identifies it within its source, [label] is shown, [detail] is the tooltip. */
data class SavedSecretEntry(
    val id: String,
    val label: String,
    val detail: String,
)
