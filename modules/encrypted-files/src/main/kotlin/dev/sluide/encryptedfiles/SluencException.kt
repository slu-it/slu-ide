package dev.sluide.encryptedfiles

import java.io.IOException

/** Why a `.sluenc` file could not be opened or decrypted. */
enum class SluencFailure {
    /** The password slot's tag failed: a wrong password, or a changed slot. */
    WRONG_PASSWORD,

    /** Empty, shorter than the magic, or another magic. */
    NOT_SLUENC,

    /** Written in a newer format version; [SluencException.formatVersion] names it. */
    NEWER_VERSION,

    /** Argon2 parameters above the caps (or impossible), refused before deriving anything. */
    UNSUPPORTED_PARAMETERS,

    /** Truncated, malformed, or the body's tag failed under the right key. */
    DAMAGED,

    /** The file was written under another data key: its password was changed elsewhere. */
    KEY_MISMATCH,
}

/**
 * A failure reading a `.sluenc` file. The message is a fixed text per [failure] and never holds a password, key or
 * content, so it may be shown and logged.
 */
class SluencException(
    val failure: SluencFailure,
    val formatVersion: Int? = null,
) : IOException(messageOf(failure, formatVersion))

private fun messageOf(
    failure: SluencFailure,
    formatVersion: Int?,
): String =
    when (failure) {
        SluencFailure.WRONG_PASSWORD -> "Wrong password."
        SluencFailure.NOT_SLUENC -> "Not a Thoreau encrypted file."
        SluencFailure.NEWER_VERSION -> "Written by a newer Thoreau (format ${formatVersion ?: "unknown"})."
        SluencFailure.UNSUPPORTED_PARAMETERS -> "The file asks for unsupported key parameters."
        SluencFailure.DAMAGED -> "The file is damaged."
        SluencFailure.KEY_MISMATCH -> "Changed elsewhere with another password."
    }
