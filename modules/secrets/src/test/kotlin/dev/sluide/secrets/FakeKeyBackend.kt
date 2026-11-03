package dev.sluide.secrets

/** An in-memory store that counts the calls that would reach the OS store and keeps the arrays it exchanged. */
internal class FakeKeyBackend : KeyBackend {
    var stored: ByteArray? = null
    var unavailable: String? = null
    var deleted = true
    var onRead: (() -> KeyRead)? = null
    var onCreate: ((ByteArray) -> KeyCreate)? = null
    var onDelete: (() -> Boolean)? = null

    /** The arrays returned by [read] (owned by the caller) and those given to [create], not copies. */
    val found = mutableListOf<ByteArray>()
    val handed = mutableListOf<ByteArray>()

    @Volatile var reads = 0

    var creates = 0
    var deletes = 0
    val calls get() = reads + creates + deletes

    override val storeName = "Fake Store"

    override fun unavailableReason() = unavailable

    override fun read(): KeyRead {
        synchronized(this) { reads++ }
        onRead?.let { return it() }
        return stored?.let { KeyRead.Found(it.copyOf().also(found::add)) } ?: KeyRead.Missing
    }

    override fun create(key: ByteArray): KeyCreate {
        creates++
        handed += key
        onCreate?.let { return it(key) }
        stored = key.copyOf()
        return KeyCreate.Created
    }

    override fun delete(): Boolean {
        deletes++
        onDelete?.let { return it() }
        if (deleted) stored = null
        return deleted
    }
}
