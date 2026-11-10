package dev.sluide.encryptedfiles

import dev.sluide.encryptedfiles.SluencFormat.KEY_BYTES
import dev.sluide.encryptedfiles.SluencFormat.NONCE_BYTES
import dev.sluide.encryptedfiles.SluencFormat.SALT_BYTES
import dev.sluide.encryptedfiles.SluencFormat.TAG_BYTES
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * What [Sluenc] does, with the randomness and the key derivation passed in so tests can watch them, and [onKey] told
 * of every key made, so tests can check that discarded ones are zeroed. Every array it allocates for key material
 * (key-encryption key, DEK copies, Argon2 output) is zeroed in a `finally`; the caller's password arrays are neither
 * kept nor zeroed.
 */
internal class SluencCodec(
    private val random: SecureRandom,
    private val kdf: Kdf,
    private val onKey: (FileKey) -> Unit = {},
) {
    fun create(
        password: CharArray,
        plain: ByteArray,
        params: KdfParams,
    ): Created {
        require(KdfParams.supported(params.memoryKiB.toLong(), params.passes.toLong(), params.parallelism)) {
            "Argon2 parameters above the caps: $params"
        }
        val dek = ByteArray(KEY_BYTES).also(random::nextBytes)
        var handedOver = false
        try {
            val key = FileKey(dek, passwordSlot(password, dek, params)).also(onKey)
            val bytes =
                try {
                    encrypt(plain, key)
                } catch (e: Throwable) {
                    key.close()
                    throw e
                }
            handedOver = true
            return Created(bytes, key)
        } finally {
            if (!handedOver) dek.fill(0)
        }
    }

    fun unlock(
        file: ByteArray,
        password: CharArray,
    ): FileKey {
        val parsed = SluencFormat.parse(file)
        val slot = parsed.slot
        val params = slot.params()
        val kek = kdf.derive(password, slot.salt, params)
        val dek =
            try {
                val prefix = SluencFormat.SLOT_PREFIX_BYTES
                aes(
                    Cipher.DECRYPT_MODE,
                    kek,
                    slot.wrapNonce,
                    slot.encoded,
                    prefix,
                    slot.encoded,
                    prefix,
                    KEY_BYTES + TAG_BYTES,
                )
            } catch (_: AEADBadTagException) {
                throw SluencException(SluencFailure.WRONG_PASSWORD)
            } finally {
                kek.fill(0)
            }
        val key = FileKey(dek, slot.encoded, parsed.slotCount, parsed.slots).also(onKey)
        try {
            openBody(parsed, key).fill(0)
        } catch (_: AEADBadTagException) {
            key.close()
            throw SluencException(SluencFailure.DAMAGED)
        } catch (e: Throwable) {
            key.close()
            throw e
        }
        return key
    }

    fun decrypt(
        file: ByteArray,
        key: FileKey,
    ): ByteArray {
        val parsed = SluencFormat.parse(file)
        return try {
            openBody(parsed, key)
        } catch (_: AEADBadTagException) {
            val failure = if (key.hasSlot(parsed.slot.encoded)) SluencFailure.DAMAGED else SluencFailure.KEY_MISMATCH
            throw SluencException(failure)
        }
    }

    fun encrypt(
        plain: ByteArray,
        key: FileKey,
    ): ByteArray = seal(key.header, plain, key)

    /** [header] followed by a fresh nonce and [plain] sealed under [header]; tests pass headers v1 does not write. */
    internal fun seal(
        header: ByteArray,
        plain: ByteArray,
        key: FileKey,
    ): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val sealed = key.withDek { aes(Cipher.ENCRYPT_MODE, it, nonce, header, header.size, plain, 0, plain.size) }
        return header + nonce + sealed
    }

    /** Only the new password slot is written: other slots wrap the old DEK (a later slot type is re-wrapped here). */
    fun changePassword(
        file: ByteArray,
        current: CharArray,
        new: CharArray,
        plain: ByteArray,
        params: KdfParams,
    ): Created {
        unlock(file, current).close()
        return create(new, plain, params)
    }

    /** A password slot wrapping [dek] under a key derived with a new salt; the derived key is zeroed. */
    private fun passwordSlot(
        password: CharArray,
        dek: ByteArray,
        params: KdfParams,
    ): ByteArray {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val wrapNonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val prefix = SluencFormat.slotPrefix(params, salt, wrapNonce)
        val kek = kdf.derive(password, salt, params)
        try {
            return prefix + aes(Cipher.ENCRYPT_MODE, kek, wrapNonce, prefix, prefix.size, dek, 0, dek.size)
        } finally {
            kek.fill(0)
        }
    }

    private fun openBody(
        parsed: ParsedFile,
        key: FileKey,
    ): ByteArray =
        key.withDek {
            aes(
                Cipher.DECRYPT_MODE,
                it,
                parsed.bodyNonce,
                parsed.file,
                parsed.headerLength,
                parsed.file,
                parsed.bodyOffset,
                parsed.bodyLength,
            )
        }

    /** AES-256-GCM with a 128-bit tag; the AAD is `aad[0, aadLength)`. A failed tag throws AEADBadTagException. */
    private fun aes(
        mode: Int,
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        aadLength: Int,
        input: ByteArray,
        offset: Int,
        length: Int,
    ): ByteArray {
        // SecretKeySpec copies the key and cannot be destroyed (JDK 25 throws DestroyFailedException), so the copy is
        // left to the garbage collector, a documented limit of the wiping (ADR 0162).
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
        cipher.updateAAD(aad, 0, aadLength)
        return cipher.doFinal(input, offset, length)
    }
}
