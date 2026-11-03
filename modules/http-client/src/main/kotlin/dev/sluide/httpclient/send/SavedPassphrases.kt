package dev.sluide.httpclient.send

import dev.sluide.projectdata.ProjectDataStore
import dev.sluide.secrets.Decrypted
import dev.sluide.secrets.Encrypted
import dev.sluide.secrets.SavedSecretEntry
import dev.sluide.secrets.SavedSecretSource
import dev.sluide.secrets.Secrets
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.lang.System.Logger.Level.WARNING
import java.nio.file.Path

private val mapper = JsonMapper()
private val log = System.getLogger(SavedPassphrases::class.java.name)

/** The field of the shared data that holds what the HTTP client stores there. */
private const val HTTP_CLIENT_FIELD = "httpClient"
private const val PASSPHRASES = "passphrases"
private const val FILES = "files"
private const val VALUE = "value"

/**
 * What [SslContexts] needs of the saved passphrases. Every function blocks (a system prompt for the key may be open),
 * so call them on worker threads only; none throws.
 */
internal interface PassphraseStore {
    /** Why nothing can be saved, shown next to the disabled "Save passphrase"; `null` when saving may work. */
    fun unavailableReason(): String?

    /** The saved passphrase of these certificate and key [files]; `null` if none is saved. */
    fun load(files: List<Path>): Decrypted?

    /** Saves [passphrase] for [files], replacing a saved one; the caller keeps and zeroes [passphrase]. */
    fun save(
        files: List<Path>,
        passphrase: CharArray,
    ): Encrypted

    fun remove(files: List<Path>)
}

/**
 * The client certificate passphrases saved with "Save passphrase" (ADR 0122), encrypted in the shared data
 * (`~/.sluide/shared.json`, ADR 0101) as `httpClient: {"passphrases": [{"files": [cert, key], "value": "v1:…"}]}`,
 * keyed by the normalized absolute paths of the certificate and key files in that order. Unknown keys of `httpClient`
 * and of an entry survive a rewrite; an entry that cannot be read is skipped and kept. A value that cannot be
 * decrypted any more is removed when it is loaded.
 *
 * Thread-safe through the store's lock; no lock is held while encrypting or decrypting, so Settings → Secrets
 * ([entries], [remove] on the FX thread) never waits for a system prompt. Never throws: failures are logged and count
 * as nothing saved.
 */
@Component
class SavedPassphrases internal constructor(
    private val store: ProjectDataStore,
    private val encrypt: (CharArray, String) -> Encrypted,
    private val decrypt: (String, String) -> Decrypted,
    private val unavailable: () -> String?,
) : SavedSecretSource {
    @Autowired
    constructor(store: ProjectDataStore, secrets: Secrets) :
        this(store, secrets::encrypt, secrets::decrypt, secrets::saveUnavailableReason)

    private data class Entry(
        val files: List<String>,
        val value: String,
    )

    override val title = "HTTP client certificate passphrases"

    override fun entries(): List<SavedSecretEntry> =
        readEntries().map { entry ->
            val last = entry.files.last()
            // A stored path this system cannot parse is shown as written.
            val shown = runCatching { Path.of(last).fileName?.toString() }.getOrNull() ?: last
            SavedSecretEntry(id(entry.files), shown, entry.files.joinToString("\n"))
        }

    override fun remove(ids: Set<String>): Boolean =
        edit { list -> list.removeIf { entry(it)?.files?.let(::id) in ids } }

    /** [PassphraseStore] for [SslContexts]: an internal interface cannot be a supertype of this public bean. */
    internal fun asPassphraseStore(): PassphraseStore =
        object : PassphraseStore {
            override fun unavailableReason() = this@SavedPassphrases.unavailableReason()

            override fun load(files: List<Path>) = this@SavedPassphrases.load(files)

            override fun save(
                files: List<Path>,
                passphrase: CharArray,
            ) = this@SavedPassphrases.save(files, passphrase)

            override fun remove(files: List<Path>) = this@SavedPassphrases.remove(files)
        }

    internal fun unavailableReason(): String? =
        try {
            unavailable()
        } catch (e: RuntimeException) {
            log.log(WARNING, "Cannot tell whether passphrases can be saved: {0}", e.javaClass.name)
            "the secret store failed"
        }

    /** `null` when nothing is saved; [Decrypted.Unreadable] also removes the saved value. */
    internal fun load(files: List<Path>): Decrypted? {
        val names = names(files)
        val value = readEntries().firstOrNull { it.files == names }?.value ?: return null
        val decrypted =
            try {
                decrypt(value, context(names))
            } catch (e: RuntimeException) {
                log.log(WARNING, "Cannot decrypt a saved passphrase: {0}", e.javaClass.name)
                return null
            }
        if (decrypted == Decrypted.Unreadable) {
            // Only this value: a newer one saved meanwhile stays.
            edit { list -> list.removeIf { entry(it) == Entry(names, value) } }
        }
        return decrypted
    }

    internal fun save(
        files: List<Path>,
        passphrase: CharArray,
    ): Encrypted {
        val names = names(files)
        val encrypted =
            try {
                encrypt(passphrase, context(names))
            } catch (e: RuntimeException) {
                log.log(WARNING, "Cannot encrypt a passphrase: {0}", e.javaClass.name)
                return Encrypted.Failed("the secret store failed")
            }
        if (encrypted !is Encrypted.Value) return encrypted
        val written =
            edit { list ->
                val index = list.indexOfFirst { entry(it)?.files == names }
                val node = (list.getOrNull(index) as? ObjectNode)?.deepCopy() ?: mapper.createObjectNode()
                node.set(FILES, mapper.createArrayNode().apply { names.forEach(::add) })
                node.put(VALUE, encrypted.text)
                if (index >= 0) list[index] = node else list.add(node)
            }
        return if (written) encrypted else Encrypted.Failed("~/.sluide/shared.json cannot be written")
    }

    internal fun remove(files: List<Path>) {
        val names = names(files)
        edit { list -> list.removeIf { entry(it)?.files == names } }
    }

    private fun readEntries(): List<Entry> =
        try {
            passphrases(store.readShared()).mapNotNull(::entry).distinctBy { it.files }
        } catch (e: RuntimeException) {
            log.log(WARNING, "Cannot read the saved passphrases: {0}", e.javaClass.name)
            emptyList()
        }

    /** Applies [change] to the list of entries in the shared data; `true` if stored. Never throws (logs). */
    private fun edit(change: (MutableList<JsonNode>) -> Unit): Boolean =
        try {
            store.updateShared { root ->
                val httpClient = (root.get(HTTP_CLIENT_FIELD) as? ObjectNode) ?: mapper.createObjectNode()
                val list = passphrases(root).toMutableList()
                change(list)
                if (list.isEmpty()) {
                    httpClient.remove(PASSPHRASES)
                } else {
                    httpClient.set(PASSPHRASES, mapper.createArrayNode().apply { list.forEach(::add) })
                }
                if (httpClient.isEmpty) root.remove(HTTP_CLIENT_FIELD) else root.set(HTTP_CLIENT_FIELD, httpClient)
            }
        } catch (e: RuntimeException) {
            log.log(WARNING, "Cannot store the saved passphrases: {0}", e.javaClass.name)
            false
        }

    private fun passphrases(root: ObjectNode?): List<JsonNode> =
        (root?.get(HTTP_CLIENT_FIELD) as? ObjectNode)
            ?.get(PASSPHRASES)
            ?.takeIf { it.isArray }
            ?.values()
            ?.toList() ?: emptyList()

    /** The entry of [node]; `null` without files or a value. */
    private fun entry(node: JsonNode): Entry? {
        val files = (node as? ObjectNode)?.get(FILES)?.takeIf { it.isArray && !it.isEmpty } ?: return null
        if (!files.values().all { it.isString }) return null
        val value = node.get(VALUE)?.takeIf { it.isString }?.stringValue() ?: return null
        return Entry(files.values().map { it.stringValue() }, value)
    }

    private fun names(files: List<Path>) = files.map { it.toAbsolutePath().normalize().toString() }

    private fun id(names: List<String>) = names.joinToString("\n")

    /** The GCM associated data: a value decrypts only for the files it was saved for (ADR 0122). */
    private fun context(names: List<String>) = "http-client/passphrase/" + names.joinToString("\n")
}
