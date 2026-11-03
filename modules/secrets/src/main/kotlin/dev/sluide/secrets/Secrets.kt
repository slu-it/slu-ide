package dev.sluide.secrets

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.lang.System.Logger.Level.WARNING
import java.security.SecureRandom
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private val log = System.getLogger(Secrets::class.java.name)

/**
 * Saved secrets (ADR 0122): values encrypted with one random key that lives in the OS secret store, so Thoreau reads
 * only that one item (one "Always Allow" per update on macOS). The key is read on first need and kept in memory until
 * [forgetAll]; a new one is created only when the store says it does not exist, and only to encrypt. When the user
 * denies access, the store is not asked again until [tryAgain] or a restart.
 *
 * [encrypt], [decrypt] and [forgetAll] may block for minutes on a system prompt (other callers wait for the first) and
 * must never run on the FX thread; [storeName], [saveUnavailableReason] and [tryAgain] are cheap on any thread.
 */
@Component
class Secrets internal constructor(
    private val backend: KeyBackend,
    private val random: SecureRandom,
) {
    @Autowired
    constructor() : this(KeyBackends.forPlatform(), SecureRandom())

    private val cipher = SecretCipher(random)

    /** Guards [key] and the calls to [backend]. */
    private val lock = ReentrantLock()
    private var key: ByteArray? = null

    /** Written under [lock], read without it, so [saveUnavailableReason] and [tryAgain] never wait for a prompt. */
    @Volatile
    private var deniedReason: String? = null

    /** Called with every key array after it was zeroed; tests check that keys do not stay in memory. */
    internal var onZeroed: (ByteArray) -> Unit = {}

    /** The secret store, e.g. "macOS Keychain"; `null` without one. */
    val storeName: String?
        get() = backend.storeName.takeUnless { backend is NoBackend }

    /** Why nothing can be saved, e.g. "needs secret-tool" or the denied access; `null` when saving may work. */
    fun saveUnavailableReason(): String? = deniedReason ?: backend.unavailableReason()

    /**
     * [plain] encrypted for [context] (e.g. `databases/<id>/password`), creating the key if the store has none; without
     * a key [Encrypted.Failed] with the reason to show the user (logged). The caller keeps [plain] and zeroes it.
     */
    fun encrypt(
        plain: CharArray,
        context: String,
    ): Encrypted =
        lock.withLock {
            when (val state = key(create = true)) {
                is KeyState.Ready -> Encrypted.Value(cipher.encrypt(state.key, plain, context))
                is KeyState.NoKey -> Encrypted.Failed(state.reason)
                KeyState.Missing -> error("a key is created when missing")
            }
        }

    /** [value] decrypted for the [context] it was written with; never creates a key. */
    fun decrypt(
        value: String,
        context: String,
    ): Decrypted {
        if (!SecretCipher.isWellFormed(value)) return Decrypted.Unreadable
        return lock.withLock {
            when (val state = key(create = false)) {
                is KeyState.Ready -> {
                    cipher.decrypt(state.key, value, context)?.let(Decrypted::Plain)
                        ?: Decrypted.Unreadable
                }

                is KeyState.NoKey -> {
                    Decrypted.NoKey(state.reason)
                }

                KeyState.Missing -> {
                    Decrypted.Unreadable
                }
            }
        }
    }

    /**
     * Deletes the key from the store and zeroes it in memory, so every saved value becomes unreadable; leaves the
     * denied state. `false` when the store could not delete it or is unavailable (then only memory is cleared).
     */
    fun forgetAll(): Boolean =
        lock.withLock {
            key?.let(::zero)
            key = null
            deniedReason = null
            if (backend.unavailableReason() != null) return false
            val deleted =
                try {
                    backend.delete()
                } catch (e: RuntimeException) {
                    log.log(WARNING, "The secret store failed to delete the key: {0}", e.javaClass.name)
                    return false
                }
            if (!deleted) log.log(WARNING, "Could not delete the key of the saved secrets from {0}", backend.storeName)
            deleted
        }

    /** Asks the store again on the next use after the user denied access. */
    fun tryAgain() {
        deniedReason = null
    }

    /** The key, read or (with [create]) created on first need. Called with [lock] held. */
    private fun key(create: Boolean): KeyState {
        key?.let { return KeyState.Ready(it) }
        deniedReason?.let { return KeyState.NoKey(it) }
        backend.unavailableReason()?.let { return fail(it) }
        return when (val read = read()) {
            is KeyRead.Found -> keep(read.key)
            KeyRead.Missing -> if (create) create() else KeyState.Missing
            is KeyRead.Denied -> deny(read.reason)
            is KeyRead.Failed -> fail(read.reason)
        }
    }

    private fun create(): KeyState {
        val fresh = ByteArray(KEY_BYTES).also(random::nextBytes)
        val handed = fresh.copyOf()
        val created =
            try {
                backend.create(handed)
            } catch (e: RuntimeException) {
                KeyCreate.Failed(thrown(e))
            } finally {
                zero(handed)
            }
        if (created == KeyCreate.Created) return keep(fresh)
        zero(fresh)
        return when (created) {
            is KeyCreate.Failed -> {
                fail(created.reason)
            }

            else -> {
                when (val again = read()) {
                    is KeyRead.Found -> keep(again.key)
                    is KeyRead.Denied -> deny(again.reason)
                    is KeyRead.Failed -> fail(again.reason)
                    KeyRead.Missing -> fail("the key was stored by another process but cannot be found")
                }
            }
        }
    }

    private fun read(): KeyRead =
        try {
            backend.read()
        } catch (e: RuntimeException) {
            KeyRead.Failed(thrown(e))
        }

    /** Only the class: an exception's message could quote what the store returned. */
    private fun thrown(e: RuntimeException) = "the secret store failed (${e.javaClass.name})"

    private fun keep(found: ByteArray): KeyState {
        if (found.size != KEY_BYTES) {
            val size = found.size
            zero(found)
            return fail("the stored key has $size bytes instead of $KEY_BYTES")
        }
        key = found
        return KeyState.Ready(found)
    }

    private fun zero(array: ByteArray) {
        array.fill(0)
        onZeroed(array)
    }

    /** Logs the denial once and makes it sticky until [tryAgain]. */
    private fun deny(reason: String): KeyState {
        log.log(WARNING, "Access to the key of the saved secrets denied: {0}", reason)
        deniedReason = reason
        return KeyState.NoKey(reason)
    }

    /** Logs the failure once; the next call asks the store again. */
    private fun fail(reason: String): KeyState {
        log.log(WARNING, "Could not get the key of the saved secrets: {0}", reason)
        return KeyState.NoKey(reason)
    }

    private sealed interface KeyState {
        class Ready(
            val key: ByteArray,
        ) : KeyState

        data object Missing : KeyState

        data class NoKey(
            val reason: String,
        ) : KeyState
    }

    private companion object {
        const val KEY_BYTES = 32
    }
}
