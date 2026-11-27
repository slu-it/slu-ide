package dev.sluide.git.ssh

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
import java.nio.file.InvalidPathException
import java.nio.file.Path

private val mapper = JsonMapper()
private val log = System.getLogger(SavedSshPassphrases::class.java.name)

/** The field of the shared data that holds what the git module stores there. */
private const val GIT_FIELD = "git"
private const val PASSPHRASES = "sshPassphrases"
private const val KEY = "key"
private const val VALUE = "value"

/**
 * The passphrases of ssh key files saved with "Save passphrase" in the git log's prompt (ADR 0122, 0194), encrypted in
 * the shared data (`~/.sluide/shared.json`, ADR 0101) as `git: {"sshPassphrases": [{"key": "<absolute path>", "value":
 * "v1:…"}]}`, with the context `git/ssh-passphrase/<path>`. Other fields of `git` and of an entry survive a rewrite; an
 * entry that cannot be read is skipped and kept. A value that cannot be decrypted any more is removed when it is
 * loaded. Listed on Settings → Secrets, where removing only removes the saved value.
 *
 * Thread-safe through the store's lock; no lock is held while encrypting or decrypting. The functions block (a system
 * prompt for the key may be open): never on the FX thread, except [entries] and [remove] by id, which touch only the
 * file. Never throws: failures are logged (the exception's class only) and count as nothing saved.
 */
@Component
internal class SavedSshPassphrases internal constructor(
    /** The shared data, as [ProjectDataStore.readShared] and [ProjectDataStore.updateShared]. */
    private val read: () -> ObjectNode?,
    private val update: ((ObjectNode) -> Unit) -> Boolean,
    private val encrypt: (CharArray, String) -> Encrypted,
    private val decrypt: (String, String) -> Decrypted,
    private val unavailable: () -> String?,
    private val home: Path,
) : SavedSecretSource {
    internal constructor(
        store: ProjectDataStore,
        encrypt: (CharArray, String) -> Encrypted,
        decrypt: (String, String) -> Decrypted,
        unavailable: () -> String?,
        home: Path,
    ) : this(store::readShared, store::updateShared, encrypt, decrypt, unavailable, home)

    @Autowired
    constructor(store: ProjectDataStore, secrets: Secrets) :
        this(
            store,
            secrets::encrypt,
            secrets::decrypt,
            secrets::saveUnavailableReason,
            Path.of(System.getProperty("user.home")),
        )

    private data class Entry(
        val key: String,
        val value: String,
    )

    override val title = "Git SSH key passphrases"

    override fun entries(): List<SavedSecretEntry> =
        readEntries().map { SavedSecretEntry(it.key, shown(it.key), it.key) }

    override fun remove(ids: Set<String>): Boolean = edit { list -> list.removeIf { entry(it)?.key in ids } }

    /** The path with the home folder as `~`; a stored path this system cannot parse is shown as written. */
    private fun shown(key: String): String =
        try {
            val path = Path.of(key)
            if (path.startsWith(home) && path != home) "~/" + home.relativize(path) else key
        } catch (_: InvalidPathException) {
            key
        }

    /** Why nothing can be saved, shown next to the disabled "Save passphrase"; `null` when saving may work. */
    fun unavailableReason(): String? =
        try {
            unavailable()
        } catch (e: RuntimeException) {
            log.log(WARNING, "Cannot tell whether passphrases can be saved: {0}", e.javaClass.name)
            "the secret store failed"
        }

    /** `null` when nothing is saved; [Decrypted.Unreadable] also removes the saved value. */
    fun load(keyFile: Path): Decrypted? {
        val name = name(keyFile)
        val entry = readEntries().firstOrNull { it.key == name }
        if (entry == null) return null
        val value = entry.value
        val decrypted =
            try {
                decrypt(value, context(name))
            } catch (e: RuntimeException) {
                log.log(WARNING, "Cannot decrypt a saved passphrase: {0}", e.javaClass.name)
                return null
            }
        if (decrypted == Decrypted.Unreadable) {
            // Only this value: a newer one saved meanwhile stays.
            edit { list -> list.removeIf { entry(it) == Entry(name, value) } }
        }
        return decrypted
    }

    /** Saves [passphrase] for [keyFile], replacing a saved one; the caller keeps and zeroes [passphrase]. */
    fun save(
        keyFile: Path,
        passphrase: CharArray,
    ): Encrypted {
        val name = name(keyFile)
        val encrypted =
            try {
                encrypt(passphrase, context(name))
            } catch (e: RuntimeException) {
                log.log(WARNING, "Cannot encrypt a passphrase: {0}", e.javaClass.name)
                return Encrypted.Failed("the secret store failed")
            }
        if (encrypted !is Encrypted.Value) return encrypted
        val written =
            edit { list ->
                val index = list.indexOfFirst { entry(it)?.key == name }
                // The entry that matched is an object (entry() needs one); its other fields stay.
                val node = if (index >= 0) (list[index] as ObjectNode).deepCopy() else mapper.createObjectNode()
                node.put(KEY, name)
                node.put(VALUE, encrypted.text)
                if (index >= 0) list[index] = node else list.add(node)
            }
        return if (written) encrypted else Encrypted.Failed("~/.sluide/shared.json cannot be written")
    }

    /**
     * Removes the saved passphrase of [keyFile] if it is [rejected], the one ssh did not accept; a different one that
     * was saved meanwhile stays.
     */
    fun removeRejected(
        keyFile: Path,
        rejected: CharArray,
    ) {
        val name = name(keyFile)
        val entry = readEntries().firstOrNull { it.key == name }
        if (entry == null) return
        val value = entry.value
        val decrypted =
            try {
                decrypt(value, context(name))
            } catch (e: RuntimeException) {
                log.log(WARNING, "Cannot decrypt a saved passphrase: {0}", e.javaClass.name)
                return
            }
        if (decrypted !is Decrypted.Plain) return
        val same = decrypted.value contentEquals rejected
        decrypted.value.fill('\u0000')
        if (same) edit { list -> list.removeIf { entry(it) == Entry(name, value) } }
    }

    /** Removes the saved passphrase of [keyFile], if any. */
    fun remove(keyFile: Path) {
        val name = name(keyFile)
        edit { list -> list.removeIf { entry(it)?.key == name } }
    }

    private fun readEntries(): List<Entry> =
        try {
            val root = read() ?: return emptyList()
            passphrases(root).mapNotNull(::entry).distinctBy { it.key }
        } catch (e: RuntimeException) {
            log.log(WARNING, "Cannot read the saved passphrases: {0}", e.javaClass.name)
            emptyList()
        }

    /** Applies [change] to the list of entries in the shared data; `true` if stored. Never throws (logs). */
    private fun edit(change: (MutableList<JsonNode>) -> Unit): Boolean =
        try {
            update { root ->
                val git = (root.get(GIT_FIELD) as? ObjectNode) ?: mapper.createObjectNode()
                val list = passphrases(root).toMutableList()
                change(list)
                if (list.isEmpty()) {
                    git.remove(PASSPHRASES)
                } else {
                    git.set(PASSPHRASES, mapper.createArrayNode().apply { list.forEach(::add) })
                }
                if (git.isEmpty) root.remove(GIT_FIELD) else root.set(GIT_FIELD, git)
            }
        } catch (e: RuntimeException) {
            log.log(WARNING, "Cannot store the saved passphrases: {0}", e.javaClass.name)
            false
        }

    private fun passphrases(root: ObjectNode): List<JsonNode> {
        val git = root.get(GIT_FIELD) as? ObjectNode ?: return emptyList()
        val list = git.get(PASSPHRASES)
        if (list == null || !list.isArray) return emptyList()
        return list.values().toList()
    }

    /** The entry of [node]; `null` without a key or a value that are text. */
    private fun entry(node: JsonNode): Entry? {
        if (node !is ObjectNode) return null
        val key = node.get(KEY)
        val value = node.get(VALUE)
        if (key == null || !key.isString || value == null || !value.isString) return null
        return Entry(key.stringValue(), value.stringValue())
    }

    private fun name(keyFile: Path) = keyFile.toAbsolutePath().normalize().toString()

    /** The GCM associated data: a value decrypts only for the key file it was saved for (ADR 0122). */
    private fun context(name: String) = "git/ssh-passphrase/$name"
}
