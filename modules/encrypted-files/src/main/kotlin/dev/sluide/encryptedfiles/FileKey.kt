package dev.sluide.encryptedfiles

/**
 * The data key (DEK) of an unlocked `.sluenc` file, the encoded password slot it was unlocked or created with, and the
 * file's whole slot list (ADR 0162). [close] zeroes the DEK; it may be called more than once, and a closed key throws
 * IllegalStateException on use. Operations hold the key's lock while they use the DEK, so a close from another thread
 * waits for them.
 */
class FileKey internal constructor(
    private val dek: ByteArray,
    passwordSlot: ByteArray,
    private val slotCount: Int = 1,
    slots: ByteArray = passwordSlot,
) : AutoCloseable {
    private val slotBytes = passwordSlot.copyOf()
    private val allSlots = slots.copyOf()
    private var closed = false

    /** The encoded password slot, compared with a file's to tell a key mismatch from damage. */
    internal val slot: ByteArray get() = slotBytes.copyOf()

    /**
     * The header every [Sluenc.encrypt] writes: the slot list unchanged, slots of types this version does not know
     * included, so saving keeps them.
     */
    internal val header: ByteArray get() = SluencFormat.header(slotCount, allSlots)

    /** The DEK array itself, for tests that check it is zeroed. */
    internal val rawDek: ByteArray get() = dek

    internal fun <T> withDek(block: (ByteArray) -> T): T =
        synchronized(this) {
            check(!closed) { "The key is closed." }
            block(dek)
        }

    internal fun hasSlot(encoded: ByteArray): Boolean = slotBytes.contentEquals(encoded)

    override fun close() {
        synchronized(this) {
            dek.fill(0)
            closed = true
        }
    }
}

/** A new file's bytes and its unlocked key. */
class Created(
    val bytes: ByteArray,
    val key: FileKey,
)
