package dev.sluide.secrets

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.platform.mac.CoreFoundation
import com.sun.jna.platform.mac.CoreFoundation.CFDataRef
import com.sun.jna.platform.mac.CoreFoundation.CFIndex
import com.sun.jna.platform.mac.CoreFoundation.CFMutableDictionaryRef
import com.sun.jna.platform.mac.CoreFoundation.CFStringRef
import com.sun.jna.platform.mac.CoreFoundation.CFTypeRef
import com.sun.jna.ptr.PointerByReference
import java.lang.System.Logger.Level.WARNING
import java.util.Base64

private val log = System.getLogger(MacKeychain::class.java.name)

private const val ERR_USER_CANCELED = -128
private const val ERR_AUTH_FAILED = -25293
private const val ERR_ITEM_NOT_FOUND = -25300
private const val ERR_DUPLICATE_ITEM = -25299
private const val ERR_INTERACTION_NOT_ALLOWED = -25308
private const val ERR_NO_DEFAULT_KEYCHAIN = -25307
private const val ERR_NOT_AVAILABLE = -25291

/**
 * Maps the status of `SecItemCopyMatching` to a [KeyRead]; [base64] is the item's data on success. Zeroes [base64]
 * and every intermediate it creates; the returned key is the caller's.
 */
internal fun keychainReadResult(
    status: Int,
    base64: ByteArray?,
): KeyRead =
    try {
        when (status) {
            0 -> decodeKey(base64)
            ERR_ITEM_NOT_FOUND -> KeyRead.Missing
            ERR_USER_CANCELED, ERR_AUTH_FAILED -> KeyRead.Denied("Keychain access denied")
            ERR_INTERACTION_NOT_ALLOWED -> KeyRead.Failed("Keychain is locked")
            ERR_NO_DEFAULT_KEYCHAIN, ERR_NOT_AVAILABLE -> KeyRead.Failed("no default keychain")
            else -> KeyRead.Failed("Keychain error $status")
        }
    } finally {
        base64?.fill(0)
    }

private fun decodeKey(base64: ByteArray?): KeyRead =
    try {
        KeyRead.Found(Base64.getDecoder().decode(base64 ?: ByteArray(0)))
    } catch (_: IllegalArgumentException) {
        KeyRead.Failed("unreadable key in the Keychain")
    }

/** Maps the status of `SecItemAdd` to a [KeyCreate]. */
internal fun keychainCreateResult(status: Int): KeyCreate =
    when (status) {
        0 -> KeyCreate.Created
        ERR_DUPLICATE_ITEM -> KeyCreate.AlreadyExists
        ERR_USER_CANCELED, ERR_AUTH_FAILED -> KeyCreate.Failed("Keychain access denied")
        ERR_INTERACTION_NOT_ALLOWED -> KeyCreate.Failed("Keychain is locked")
        ERR_NO_DEFAULT_KEYCHAIN, ERR_NOT_AVAILABLE -> KeyCreate.Failed("no default keychain")
        else -> KeyCreate.Failed("Keychain error $status")
    }

/**
 * The key as a generic password in the user's login keychain (ADR 0122): item with [service] (also its label) and
 * [account], the key as base64 text, read, added and deleted through `Security.framework` via JNA
 * (`SecItemCopyMatching`, `SecItemAdd`, `SecItemDelete` with CoreFoundation dictionaries), never through the
 * `security` CLI, which would put `security` on the item's access list. The file-based keychain is used (no data
 * protection keychain, which needs entitlements). `errSecItemNotFound` is [KeyRead.Missing], Deny or Cancel on the
 * prompt [KeyRead.Denied], a duplicate on add [KeyCreate.AlreadyExists]. Tests pass their own service so they never
 * touch Thoreau's item. The native libraries load on first use; a failure to load is a `Failed` result.
 */
internal class MacKeychain(
    private val service: String = "Thoreau Safe Storage",
    private val account: String = "Thoreau",
) : KeyBackend {
    override val storeName = "macOS Keychain"

    private val natives: Result<Natives> by lazy {
        runCatching { Natives() }.onFailure {
            log.log(WARNING, "The Keychain library could not be loaded: {0}", it.javaClass.name)
        }
    }

    override fun unavailableReason(): String? = null

    override fun read(): KeyRead =
        call<KeyRead>(KeyRead::Failed) { n, refs ->
            n.withDictionary { dict ->
                n.set(dict, n.kSecClass, n.kSecClassGenericPassword)
                identify(n, refs, dict)
                n.set(dict, n.kSecReturnData, n.kCFBooleanTrue)
                n.set(dict, n.kSecMatchLimit, n.kSecMatchLimitOne)
                val result = PointerByReference()
                val status = n.security.SecItemCopyMatching(dict.pointer, result)
                val data = result.value?.let { CFDataRef(it) }
                try {
                    keychainReadResult(status, data?.let { it.bytePtr.getByteArray(0, it.length) })
                } finally {
                    data?.release()
                }
            }
        }

    override fun create(key: ByteArray): KeyCreate =
        call<KeyCreate>(KeyCreate::Failed) { n, refs ->
            val base64 = Base64.getEncoder().encode(key)
            val buffer = Memory(base64.size.toLong())
            try {
                buffer.write(0, base64, 0, base64.size)
                val data = refs.add(n.cf.CFDataCreate(null, buffer, CFIndex(base64.size.toLong())))
                n.withDictionary { dict ->
                    n.set(dict, n.kSecClass, n.kSecClassGenericPassword)
                    identify(n, refs, dict)
                    n.set(dict, n.kSecAttrLabel, refs.add(CFStringRef.createCFString(service)))
                    n.set(dict, n.kSecValueData, data)
                    keychainCreateResult(n.security.SecItemAdd(dict.pointer, null))
                }
            } finally {
                buffer.clear()
                base64.fill(0)
            }
        }

    override fun delete(): Boolean =
        call({ _ -> false }) { n, refs ->
            n.withDictionary { dict ->
                n.set(dict, n.kSecClass, n.kSecClassGenericPassword)
                identify(n, refs, dict)
                val status = n.security.SecItemDelete(dict.pointer)
                status == 0 || status == ERR_ITEM_NOT_FOUND
            }
        }

    /** Service and account, the attributes that identify the item. */
    private fun identify(
        n: Natives,
        refs: Refs,
        dict: CFMutableDictionaryRef,
    ) {
        n.set(dict, n.kSecAttrService, refs.add(CFStringRef.createCFString(service)))
        n.set(dict, n.kSecAttrAccount, refs.add(CFStringRef.createCFString(account)))
    }

    /** Runs [block] with the natives and a scope that releases the CF objects it creates; never throws. */
    private fun <T> call(
        failed: (String) -> T,
        block: (Natives, Refs) -> T,
    ): T {
        val n =
            natives.getOrNull()
                ?: return failed("Keychain library not loaded (${natives.exceptionOrNull()?.javaClass?.name})")
        return try {
            Refs().use { block(n, it) }
        } catch (e: Throwable) {
            log.log(WARNING, "A Keychain call failed: {0}", e.javaClass.name)
            failed("Keychain call failed (${e.javaClass.name})")
        }
    }

    /** CF objects created by one call; released when it ends (the dictionary retains what it holds). */
    private class Refs : AutoCloseable {
        private val created = mutableListOf<CFTypeRef>()

        fun <T : CFTypeRef> add(ref: T): T = ref.also { created += it }

        override fun close() = created.forEach { it.release() }
    }

    // The function names are those of the C API, which JNA binds by name.
    @Suppress("ktlint:standard:function-naming")
    private interface SecurityApi : Library {
        fun SecItemCopyMatching(
            query: Pointer,
            result: PointerByReference,
        ): Int

        fun SecItemAdd(
            attributes: Pointer,
            result: PointerByReference?,
        ): Int

        fun SecItemDelete(query: Pointer): Int
    }

    /** The loaded libraries and the `kSec*` constants, which are global CFString variables. */
    private class Natives {
        val cf: CoreFoundation = CoreFoundation.INSTANCE
        val security: SecurityApi = Native.load("Security", SecurityApi::class.java)
        private val securityLib = NativeLibrary.getInstance("Security")
        private val cfLib = NativeLibrary.getInstance("CoreFoundation")

        val kSecClass = constant("kSecClass")
        val kSecClassGenericPassword = constant("kSecClassGenericPassword")
        val kSecAttrService = constant("kSecAttrService")
        val kSecAttrAccount = constant("kSecAttrAccount")
        val kSecAttrLabel = constant("kSecAttrLabel")
        val kSecValueData = constant("kSecValueData")
        val kSecReturnData = constant("kSecReturnData")
        val kSecMatchLimit = constant("kSecMatchLimit")
        val kSecMatchLimitOne = constant("kSecMatchLimitOne")
        val kCFBooleanTrue = CFTypeRef(cfLib.getGlobalVariableAddress("kCFBooleanTrue").getPointer(0))

        private fun constant(name: String) = CFTypeRef(securityLib.getGlobalVariableAddress(name).getPointer(0))

        fun set(
            dict: CFMutableDictionaryRef,
            key: CFTypeRef,
            value: CFTypeRef,
        ) = cf.CFDictionarySetValue(dict, key, value)

        fun <T> withDictionary(block: (CFMutableDictionaryRef) -> T): T {
            val dict =
                cf.CFDictionaryCreateMutable(
                    null,
                    CFIndex(0),
                    cfLib.getGlobalVariableAddress("kCFTypeDictionaryKeyCallBacks"),
                    cfLib.getGlobalVariableAddress("kCFTypeDictionaryValueCallBacks"),
                )
            try {
                return block(dict)
            } finally {
                dict.release()
            }
        }
    }
}
