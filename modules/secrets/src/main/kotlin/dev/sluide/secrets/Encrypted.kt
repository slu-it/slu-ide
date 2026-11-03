package dev.sluide.secrets

/** What [Secrets.encrypt] made of a secret. */
sealed interface Encrypted {
    /** The text to store, `v1:…`. */
    data class Value(
        val text: String,
    ) : Encrypted

    /** No key available; [reason] is shown to the user, e.g. "Password not saved: needs secret-tool". */
    data class Failed(
        val reason: String,
    ) : Encrypted
}
