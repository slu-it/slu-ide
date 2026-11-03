package dev.sluide.databases.connect

import dev.sluide.secrets.Decrypted
import dev.sluide.secrets.Encrypted
import dev.sluide.secrets.Secrets

/**
 * The saved connection passwords (ADR 0122): encrypted for the context `databases/<id>/password`, so a value only
 * decrypts for the connection it was written for. [encrypt] and [decrypt] block (maybe minutes on a Keychain prompt)
 * and never run on the FX thread; [unavailableReason] is cheap on any thread.
 */
internal interface SavedPasswords {
    /** Why nothing can be saved, e.g. "needs secret-tool"; `null` when saving may work. */
    fun unavailableReason(): String?

    /** [password] of connection [id] encrypted; the caller keeps and zeroes [password]. */
    fun encrypt(
        id: String,
        password: CharArray,
    ): Encrypted

    /** The saved [value] of connection [id]; the caller zeroes a [Decrypted.Plain]. */
    fun decrypt(
        id: String,
        value: String,
    ): Decrypted
}

/** The [SavedPasswords] of the app's [Secrets]. */
internal class SecretsPasswords(
    private val secrets: Secrets,
) : SavedPasswords {
    override fun unavailableReason(): String? = secrets.saveUnavailableReason()

    override fun encrypt(
        id: String,
        password: CharArray,
    ): Encrypted = secrets.encrypt(password, context(id))

    override fun decrypt(
        id: String,
        value: String,
    ): Decrypted = secrets.decrypt(value, context(id))

    private fun context(id: String) = "databases/$id/password"
}

/** Nothing can be saved; the default where saving does not matter (tests). */
internal object NoSavedPasswords : SavedPasswords {
    private const val REASON = "not supported on this system"

    override fun unavailableReason(): String = REASON

    override fun encrypt(
        id: String,
        password: CharArray,
    ): Encrypted = Encrypted.Failed(REASON)

    override fun decrypt(
        id: String,
        value: String,
    ): Decrypted = Decrypted.NoKey(REASON)
}
