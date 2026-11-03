package dev.sluide.secrets

/** What [Secrets.decrypt] got from a saved value. */
sealed interface Decrypted {
    /** The secret; the caller zeroes it when done. */
    class Plain(
        val value: CharArray,
    ) : Decrypted

    /**
     * Counts as nothing saved: written elsewhere or with another key (other machine, restored `~/.sluide`, forgotten
     * key), or malformed. The caller removes the value and asks for the secret.
     */
    data object Unreadable : Decrypted

    /** No key available (no store, access denied, a read error); the caller keeps the value and asks for the secret. */
    data class NoKey(
        val reason: String,
    ) : Decrypted
}
