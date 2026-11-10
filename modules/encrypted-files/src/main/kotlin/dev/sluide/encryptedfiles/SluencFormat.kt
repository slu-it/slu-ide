package dev.sluide.encryptedfiles

import java.nio.ByteBuffer

/**
 * The binary `.sluenc` format v1, big-endian (ADR 0162): `SLUENC` | version u8 | slot count u8 | slots | body nonce
 * (12) | body ciphertext and tag. Every slot is type u8 | length u16 (the slot bytes that follow) | fields, so readers
 * skip slot types they do not know. A password slot is type 1 | length 85 | memory KiB u32 | passes u32 |
 * parallelism u8 | salt (16) | wrap nonce (12) | wrapped DEK (32 + 16 tag).
 */
internal object SluencFormat {
    val MAGIC = "SLUENC".toByteArray(Charsets.US_ASCII)
    const val VERSION = 1
    const val MAX_SLOTS = 8
    const val PASSWORD_SLOT = 1
    const val SALT_BYTES = 16
    const val NONCE_BYTES = 12
    const val KEY_BYTES = 32
    const val TAG_BYTES = 16

    /** Type and length, before every slot's own fields. */
    const val SLOT_HEAD_BYTES = 1 + 2

    /** The slot's bytes before the wrapped DEK, the wrap's associated data. */
    const val SLOT_PREFIX_BYTES = SLOT_HEAD_BYTES + 4 + 4 + 1 + SALT_BYTES + NONCE_BYTES
    const val PASSWORD_SLOT_BYTES = SLOT_PREFIX_BYTES + KEY_BYTES + TAG_BYTES

    val FIXED_HEADER_BYTES = MAGIC.size + 2

    /** The bytes of a password slot before its wrapped DEK. */
    fun slotPrefix(
        params: KdfParams,
        salt: ByteArray,
        wrapNonce: ByteArray,
    ): ByteArray =
        ByteBuffer
            .allocate(SLOT_PREFIX_BYTES)
            .put(PASSWORD_SLOT.toByte())
            .putShort((PASSWORD_SLOT_BYTES - SLOT_HEAD_BYTES).toShort())
            .putInt(params.memoryKiB)
            .putInt(params.passes)
            .put(params.parallelism.toByte())
            .put(salt)
            .put(wrapNonce)
            .array()

    /** Everything from the magic to the end of the last slot: the body's associated data. */
    fun header(
        slotCount: Int,
        slots: ByteArray,
    ): ByteArray =
        ByteBuffer
            .allocate(FIXED_HEADER_BYTES + slots.size)
            .put(MAGIC)
            .put(VERSION.toByte())
            .put(slotCount.toByte())
            .put(slots)
            .array()

    /**
     * Parses [file] up to its body and picks its first password slot, skipping slots of other types; throws
     * SluencException with NOT_SLUENC, NEWER_VERSION or DAMAGED (also without a password slot, or with one whose
     * length is not a password slot's).
     */
    fun parse(file: ByteArray): ParsedFile {
        if (file.size < MAGIC.size || !file.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw SluencException(SluencFailure.NOT_SLUENC)
        }
        if (file.size < FIXED_HEADER_BYTES) throw damaged()
        val version = file[MAGIC.size].toInt() and 0xFF
        if (version > VERSION) throw SluencException(SluencFailure.NEWER_VERSION, version)
        if (version < VERSION) throw damaged()
        val slotCount = file[MAGIC.size + 1].toInt() and 0xFF
        if (slotCount !in 1..MAX_SLOTS) throw damaged()
        var offset = FIXED_HEADER_BYTES
        var first: PasswordSlot? = null
        repeat(slotCount) {
            if (file.size - offset < SLOT_HEAD_BYTES) throw damaged()
            val type = file[offset].toInt() and 0xFF
            val length = ByteBuffer.wrap(file).getShort(offset + 1).toInt() and 0xFFFF
            val end = offset + SLOT_HEAD_BYTES + length
            if (end > file.size) throw damaged()
            if (type == PASSWORD_SLOT) {
                if (end - offset != PASSWORD_SLOT_BYTES) throw damaged()
                if (first == null) first = PasswordSlot(file.copyOfRange(offset, end))
            }
            offset = end
        }
        if (file.size - offset < NONCE_BYTES + TAG_BYTES) throw damaged()
        return ParsedFile(file, offset, first ?: throw damaged())
    }

    private fun damaged() = SluencException(SluencFailure.DAMAGED)
}

/** A password slot as stored; the parameters are checked against the caps only when a key is derived from it. */
internal class PasswordSlot(
    val encoded: ByteArray,
) {
    private val buffer = ByteBuffer.wrap(encoded)
    private val memoryKiB = buffer.getInt(SluencFormat.SLOT_HEAD_BYTES).toLong() and 0xFFFF_FFFFL
    private val passes = buffer.getInt(SluencFormat.SLOT_HEAD_BYTES + 4).toLong() and 0xFFFF_FFFFL
    private val parallelism = encoded[SluencFormat.SLOT_HEAD_BYTES + 8].toInt() and 0xFF

    val salt: ByteArray get() = encoded.copyOfRange(SALT_OFFSET, SALT_OFFSET + SluencFormat.SALT_BYTES)
    val wrapNonce: ByteArray get() =
        encoded.copyOfRange(
            SluencFormat.SLOT_PREFIX_BYTES - SluencFormat.NONCE_BYTES,
            SluencFormat.SLOT_PREFIX_BYTES,
        )

    /** The Argon2 parameters, or SluencException UNSUPPORTED_PARAMETERS when they are above the caps. */
    fun params(): KdfParams {
        if (!KdfParams.supported(memoryKiB, passes, parallelism)) {
            throw SluencException(SluencFailure.UNSUPPORTED_PARAMETERS)
        }
        return KdfParams(memoryKiB.toInt(), passes.toInt(), parallelism)
    }

    private companion object {
        const val SALT_OFFSET = SluencFormat.SLOT_HEAD_BYTES + 4 + 4 + 1
    }
}

/** A parsed file: the header is `file[0, headerLength)`, the body nonce follows it, then the body. */
internal class ParsedFile(
    val file: ByteArray,
    val headerLength: Int,
    val slot: PasswordSlot,
) {
    val slotCount: Int get() = file[SluencFormat.MAGIC.size + 1].toInt() and 0xFF

    /** All slots as stored, those this reader skipped included. */
    val slots: ByteArray get() = file.copyOfRange(SluencFormat.FIXED_HEADER_BYTES, headerLength)
    val bodyNonce: ByteArray get() = file.copyOfRange(headerLength, headerLength + SluencFormat.NONCE_BYTES)
    val bodyOffset: Int get() = headerLength + SluencFormat.NONCE_BYTES
    val bodyLength: Int get() = file.size - bodyOffset
}
