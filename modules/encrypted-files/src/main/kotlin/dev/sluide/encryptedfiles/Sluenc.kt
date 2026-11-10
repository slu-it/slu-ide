package dev.sluide.encryptedfiles

import java.security.SecureRandom

/**
 * Encrypts text files with a password into the `.sluenc` format (ADR 0162): AES-256-GCM under a random data key (DEK)
 * per file, wrapped by a key Argon2id derives from the password. Every function blocks (Argon2 takes about 0.2 s on an
 * M-class machine with [KdfParams.DEFAULT], up to about 1 s on slower ones), so callers run them off the FX thread.
 * Failures reading a file are [SluencException]s. Password arrays stay the caller's: they are neither kept nor zeroed
 * here.
 */
object Sluenc {
    private val codec = SluencCodec(SecureRandom(), Argon2id)

    /** A new file holding [plain] under [password], with a new DEK, and its unlocked key. */
    fun create(
        password: CharArray,
        plain: ByteArray,
        params: KdfParams = KdfParams.DEFAULT,
    ): Created = codec.create(password, plain, params)

    /** The key of [file] for [password], after checking that the body decrypts with it. */
    fun unlock(
        file: ByteArray,
        password: CharArray,
    ): FileKey = codec.unlock(file, password)

    /** The plain bytes of [file]; KEY_MISMATCH when it was written under another DEK (password changed elsewhere). */
    fun decrypt(
        file: ByteArray,
        key: FileKey,
    ): ByteArray = codec.decrypt(file, key)

    /** [plain] as a file under [key]: the key's slot list unchanged (unknown slot types kept), a fresh body nonce. */
    fun encrypt(
        plain: ByteArray,
        key: FileKey,
    ): ByteArray = codec.encrypt(plain, key)

    /**
     * Checks [current] against [file] (failing as [unlock] does), then writes [plain] under [new] with a new DEK, salt
     * and nonces, so a key of the old file gets KEY_MISMATCH on the new bytes. Only the new password slot is written:
     * other slots wrap the old DEK, so a version that adds slot types must re-wrap them here.
     */
    fun changePassword(
        file: ByteArray,
        current: CharArray,
        new: CharArray,
        plain: ByteArray,
        params: KdfParams = KdfParams.DEFAULT,
    ): Created = codec.changePassword(file, current, new, plain, params)
}
