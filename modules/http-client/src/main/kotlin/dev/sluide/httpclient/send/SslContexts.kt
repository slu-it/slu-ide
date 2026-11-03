package dev.sluide.httpclient.send

import dev.sluide.httpclient.env.SslConfig
import dev.sluide.secrets.Decrypted
import dev.sluide.secrets.Encrypted
import java.io.IOException
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import javax.net.ssl.SSLContext
import kotlin.io.path.getLastModifiedTime

/** The prompt's answer: the passphrase, zeroed by its taker. */
internal class PassphraseAnswer(
    val passphrase: CharArray,
    /**
     * Whether "Save passphrase" was checked. Ignored when the prompt was shown with a reason why nothing can be saved:
     * the box is then disabled (shown checked when a passphrase is saved), and the saved one is neither replaced nor
     * removed.
     */
    val save: Boolean,
)

/**
 * The [SSLContext]s of one window, one per `SSLConfiguration` (and modification time of its files), so that
 * `HttpClients.ClientKey`s of equal configurations are equal. Contexts that are replaced (because a file changed) or
 * cleared go to [discarded], e.g. to stop their HTTP clients.
 *
 * A passphrase is needed at most once per pair of certificate and key files, when a context is first created, and
 * remembered for the session; it is needed again only if it turned out wrong. It comes from memory, else from the
 * [saved] passphrases (ADR 0122), else from [passphrase], the prompt, with the reason why nothing can be saved (`null`
 * when saving may work) and whether "Save passphrase" starts checked (a saved one exists, readable or without a key).
 * A prompted passphrase is saved, or the saved one removed when the box was unchecked, only after the context was
 * created with it and only if the box was enabled; a save that fails goes to [notSaved] with its reason (unless [clear]
 * was called meanwhile). A rejected saved passphrase (also one in memory that equals the saved one)
 * is not loaded again in this session: the next prompt starts checked and the saved value stays until a prompted one
 * replaces it.
 *
 * [context] blocks while the prompt is open or the saved passphrases wait for a system prompt, so never call it on the
 * FX thread; a prompt shown on the FX thread must not wait for this class. Only calls for the same files wait for each
 * other (on the per-files lock, interruptibly); [clear] never waits for a prompt or for [saved], because the store lock
 * is never held around them.
 *
 * [trustStore] holds the certificates to trust when verifying hosts; null means the JDK's (`cacerts`).
 */
internal class SslContexts(
    private val passphrase: (SslConfig, saveReason: String?, saveChecked: Boolean) -> PassphraseAnswer? =
        { _, _, _ -> null },
    private val trustStore: KeyStore? = null,
    private val discarded: (SSLContext) -> Unit = {},
    private val saved: PassphraseStore? = null,
    private val notSaved: (reason: String) -> Unit = {},
) {
    private data class Key(
        val config: SslConfig,
        val modified: List<FileTime?>,
    )

    /** A passphrase in memory; [saved] if it is the one in [SslContexts.saved]. */
    private class Remembered(
        val value: CharArray,
        val saved: Boolean,
    )

    private val contexts = ConcurrentHashMap<Key, SSLContext>()
    private val passphrases = ConcurrentHashMap<List<Path>, Remembered>()

    /** The files whose saved passphrase was rejected in this session; it is not loaded again. */
    private val rejectedSaved = ConcurrentHashMap.newKeySet<List<Path>>()

    /** One lock per pair of files, held while creating their context (and prompting for their passphrase). */
    private val fileLocks = ConcurrentHashMap<List<Path>, ReentrantLock>()

    /**
     * Guards changes of [contexts], [passphrases], [rejectedSaved] and [generation]; held only briefly, never around a
     * prompt or a call of [saved].
     */
    private val storeLock = Any()

    /** Counts [clear]s, so that a context created across one is not stored. */
    private var generation = 0L

    fun context(config: SslConfig): SSLContext {
        val files = listOfNotNull(config.clientCertificate, config.clientCertificateKey).map { it.path }
        val key = Key(config, files.map(::modified))
        contexts[key]?.let { return it }
        val fileLock = fileLocks.computeIfAbsent(files) { ReentrantLock() }
        fileLock.lockInterruptibly()
        try {
            contexts[key]?.let { return it }
            val generation = synchronized(storeLock) { this.generation }
            val ask = Ask(config, files, generation)
            val context =
                try {
                    create(config, ask::passphrase, trustStore)
                } catch (e: Throwable) {
                    ask.answer?.passphrase?.fill('\u0000')
                    remember(ask, generation, rejected = e is SslConfigException && e.passphraseRejected)
                    throw e
                }
            var savedNow = false
            try {
                savedNow = ask.saveAnswer()
            } finally {
                ask.answer?.passphrase?.fill('\u0000')
                remember(ask, generation, savedNow = savedNow)
            }
            val replaced =
                synchronized(storeLock) {
                    if (this.generation != generation) return context
                    val old = contexts.keys.filter { it.config == config }.mapNotNull { contexts.remove(it) }
                    contexts[key] = context
                    old
                }
            replaced.forEach(discarded)
            return context
        } finally {
            fileLock.unlock()
        }
    }

    /**
     * Keeps the passphrase that [ask] got (unless [rejected] or cleared meanwhile) or zeroes it. [savedNow] is `null`
     * when creating the context failed, else whether the passphrase is the saved one after this ask. A rejected saved
     * passphrase is marked, so that it is not loaded again; the mark goes once a prompted one worked.
     */
    private fun remember(
        ask: Ask,
        generation: Long,
        rejected: Boolean = false,
        savedNow: Boolean? = null,
    ) {
        synchronized(storeLock) {
            val current = this.generation == generation
            if (rejected) {
                passphrases.remove(ask.files)?.value?.fill('\u0000')
                if (current && ask.usedSaved) rejectedSaved += ask.files
            }
            val keep = ask.kept?.takeIf { !rejected && current }
            if (keep != null) {
                passphrases[ask.files] = Remembered(keep, ask.usedSaved || savedNow == true)
            } else {
                ask.kept?.fill('\u0000')
            }
            if (savedNow != null && ask.answer != null) rejectedSaved -= ask.files
        }
    }

    /** Where the passphrase of one [context] call comes from; called on its worker, with the per-files lock held. */
    private inner class Ask(
        val config: SslConfig,
        val files: List<Path>,
        val generation: Long,
    ) {
        /** The passphrase to remember when the context was created (a saved or prompted one). */
        var kept: CharArray? = null

        /** The prompt's answer, with its own copy of the passphrase for saving. */
        var answer: PassphraseAnswer? = null

        /** The passphrase was (or is in memory as) the saved one. */
        var usedSaved = false

        /** A saved passphrase exists for [files]: a readable one, one without a key, or a rejected one. */
        private var entryExisted = false

        /** Why the prompt could not offer saving; then its answer's `save` is ignored. */
        private var saveReason: String? = null

        fun passphrase(): CharArray? {
            passphrases[files]?.let {
                usedSaved = it.saved
                return it.value.copyOf()
            }
            loaded()?.let {
                usedSaved = true
                kept = it.copyOf()
                return it
            }
            saveReason = if (saved == null) "not supported" else saved.unavailableReason()
            val prompted = passphrase(config, saveReason, entryExisted) ?: return null
            kept = prompted.passphrase.copyOf()
            answer = PassphraseAnswer(prompted.passphrase.copyOf(), prompted.save)
            return prompted.passphrase
        }

        private fun loaded(): CharArray? {
            if (saved == null) return null
            if (files in rejectedSaved) {
                entryExisted = true
                return null
            }
            return when (val loaded = saved.load(files)) {
                null -> {
                    null
                }

                is Decrypted.Plain -> {
                    entryExisted = true
                    loaded.value
                }

                // The store removed it: nothing is saved any more.
                Decrypted.Unreadable -> {
                    null
                }

                is Decrypted.NoKey -> {
                    entryExisted = true
                    null
                }
            }
        }

        /**
         * After the context was created with the prompt's answer: saves it if asked to, else removes a saved one;
         * nothing when the box was disabled. `true` if the passphrase is now the saved one.
         */
        fun saveAnswer(): Boolean {
            val answer = answer ?: return false
            if (saved == null || saveReason != null) return false
            if (answer.save) {
                return when (val result = saved.save(files, answer.passphrase)) {
                    is Encrypted.Value -> {
                        true
                    }

                    is Encrypted.Failed -> {
                        // A closed window does not tell.
                        if (current(generation)) notSaved(result.reason)
                        false
                    }
                }
            }
            if (entryExisted) saved.remove(files)
            return false
        }
    }

    /** Forgets all contexts and passphrases (and which saved ones were rejected); does not wait for running calls. */
    fun clear() {
        val cleared =
            synchronized(storeLock) {
                generation++
                passphrases.values.forEach { it.value.fill('\u0000') }
                passphrases.clear()
                rejectedSaved.clear()
                contexts.values.toList().also { contexts.clear() }
            }
        cleared.forEach(discarded)
    }

    /** No [clear] since [generation] was read. */
    private fun current(generation: Long) = synchronized(storeLock) { this.generation == generation }

    private fun modified(path: Path): FileTime? =
        try {
            path.getLastModifiedTime()
        } catch (_: IOException) {
            null
        }

    companion object {
        /** See [SslContextFactory.create]. */
        fun create(
            config: SslConfig,
            passphrase: () -> CharArray?,
            trustStore: KeyStore? = null,
        ): SSLContext = SslContextFactory.create(config, passphrase, trustStore)
    }
}
