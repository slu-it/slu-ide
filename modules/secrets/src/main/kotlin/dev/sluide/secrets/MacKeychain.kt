package dev.sluide.secrets

/**
 * The key as a generic password in the macOS Keychain (ADR 0122): item with [service] (also its label) and [account],
 * read, added and deleted through `Security.framework` via JNA (`SecItemCopyMatching`, `SecItemAdd`, `SecItemDelete`
 * with CoreFoundation dictionaries), never through the `security` CLI, which would put `security` on the item's
 * access list. `errSecItemNotFound` is [KeyRead.Missing], Deny or Cancel on the prompt [KeyRead.Denied], a duplicate on
 * add [KeyCreate.AlreadyExists]. Tests pass their own service so they never touch Thoreau's item.
 *
 * Stub until the backend is implemented: nothing is stored.
 */
internal class MacKeychain(
    private val service: String = "Thoreau Safe Storage",
    private val account: String = "Thoreau",
) : KeyBackend {
    override val storeName = "macOS Keychain"

    override fun unavailableReason(): String? = NOT_IMPLEMENTED

    override fun read(): KeyRead = KeyRead.Failed(NOT_IMPLEMENTED)

    override fun create(key: ByteArray): KeyCreate = KeyCreate.Failed(NOT_IMPLEMENTED)

    override fun delete() = false

    private companion object {
        const val NOT_IMPLEMENTED = "not implemented yet"
    }
}
