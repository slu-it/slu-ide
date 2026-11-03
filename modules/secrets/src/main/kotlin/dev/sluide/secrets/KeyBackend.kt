package dev.sluide.secrets

/** What reading the key from the OS secret store found (ADR 0122). */
internal sealed interface KeyRead {
    /** The stored key, 32 bytes; the array passes to the caller, which zeroes it when done. */
    class Found(
        val key: ByteArray,
    ) : KeyRead

    /** The store says the item does not exist; only then is a new key created. */
    data object Missing : KeyRead

    /** The user denied access (Deny or Cancel on the system prompt); the store is not asked again until Try Again. */
    data class Denied(
        val reason: String,
    ) : KeyRead

    /** Any other error; fails only this call. */
    data class Failed(
        val reason: String,
    ) : KeyRead
}

/** What storing a new key did. */
internal sealed interface KeyCreate {
    data object Created : KeyCreate

    /** Another Thoreau process stored one first; it is read instead. */
    data object AlreadyExists : KeyCreate

    data class Failed(
        val reason: String,
    ) : KeyCreate
}

/**
 * The OS secret store that holds the one key of the saved secrets (ADR 0122). All calls but [unavailableReason]
 * block (a read may wait minutes on a system prompt) and never run on the FX thread. No call throws: errors are
 * [KeyRead.Failed], [KeyCreate.Failed] or `false`.
 */
internal interface KeyBackend {
    /** The name the settings show, e.g. "macOS Keychain" or "Secret Service (secret-tool)". */
    val storeName: String

    /** Cheap, any thread: `null` when usable, else why not, e.g. "needs secret-tool". */
    fun unavailableReason(): String?

    fun read(): KeyRead

    /** Stores [key]; must neither keep nor modify it (copies of its own it may zero). */
    fun create(key: ByteArray): KeyCreate

    /** `true` when the key is gone, also when it was missing. */
    fun delete(): Boolean
}
