package dev.sluide.secrets

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class SecretsTest {
    private val backend = FakeBackend()
    private val zeroed = mutableListOf<ByteArray>()
    private val secrets = Secrets(backend, SecureRandom()).also { it.onZeroed = zeroed::add }
    private val context = "databases/1/password"

    @Test
    fun `nothing touches the store before the first use`() {
        assertEquals("Fake Store", secrets.storeName)
        assertNull(secrets.saveUnavailableReason())
        secrets.tryAgain()

        assertEquals(0, backend.calls)
    }

    @Test
    fun `the key is read once and reused`() {
        backend.stored = ByteArray(32) { 1 }

        val value = secrets.encryptText("secret")
        secrets.encryptText("other")
        val decrypted = secrets.decrypt(value, context)

        assertArrayEquals("secret".toCharArray(), assertInstanceOf<Decrypted.Plain>(decrypted).value)
        assertEquals(1, backend.reads)
        assertEquals(0, backend.creates)
    }

    @Test
    fun `a missing key is created on encrypt and then used`() {
        val value = secrets.encryptText("secret")
        secrets.encryptText("other")

        assertEquals(1, backend.reads)
        assertEquals(1, backend.creates)
        assertEquals(32, backend.stored?.size)
        assertTrue(backend.handed.single().all { it == 0.toByte() }, "the array handed to the store is zeroed")
        // A restarted app reads the stored key and decrypts the value.
        val restarted = Secrets(backend, SecureRandom())
        assertInstanceOf<Decrypted.Plain>(restarted.decrypt(value, context))
    }

    @Test
    fun `a key stored by another process meanwhile is read again`() {
        val theirs = ByteArray(32) { 9 }
        backend.onCreate = {
            backend.stored = theirs
            KeyCreate.AlreadyExists
        }

        val value = secrets.encryptText("secret")

        assertEquals(2, backend.reads)
        assertArrayEquals(theirs, backend.stored)
        assertInstanceOf<Decrypted.Plain>(Secrets(backend, SecureRandom()).decrypt(value, context))
    }

    @Test
    fun `a duplicate on create that cannot be read again saves nothing`() {
        backend.onCreate = { KeyCreate.AlreadyExists }

        assertEquals(
            Encrypted.Failed("the key was stored by another process but cannot be found"),
            secrets.encrypt("secret".toCharArray(), context),
        )
        assertEquals(2, backend.reads)
        assertNull(backend.stored)
    }

    @Test
    fun `a failed create saves nothing, zeroes the new key and is not sticky`() {
        backend.onCreate = { KeyCreate.Failed("disk full") }

        assertEquals(Encrypted.Failed("disk full"), secrets.encrypt("secret".toCharArray(), context))
        assertEquals(Encrypted.Failed("disk full"), secrets.encrypt("secret".toCharArray(), context))

        assertEquals(2, backend.creates)
        assertNull(secrets.saveUnavailableReason())
        // Per create: the handed copy and the new key itself.
        assertEquals(4, zeroed.size)
        assertTrue(zeroed.all { array -> array.all { it == 0.toByte() } })
    }

    @Test
    fun `decrypting never creates a key`() {
        val value = Secrets(FakeBackend(), SecureRandom()).encryptText("secret")

        assertEquals(Decrypted.Unreadable, secrets.decrypt(value, context))
        assertEquals(1, backend.reads)
        assertEquals(0, backend.creates)
        assertNull(backend.stored)
    }

    @Test
    fun `a value of another key or context is unreadable`() {
        val value = Secrets(FakeBackend(), SecureRandom()).encryptText("secret")
        backend.stored = ByteArray(32)

        assertEquals(Decrypted.Unreadable, secrets.decrypt(value, context))
        val own = secrets.encryptText("secret")
        assertEquals(Decrypted.Unreadable, secrets.decrypt(own, "databases/2/password"))
    }

    @Test
    fun `denied access is sticky until try again`() {
        backend.onRead = { KeyRead.Denied("Keychain access denied") }
        val denied = Encrypted.Failed("Keychain access denied")

        assertEquals(denied, secrets.encrypt("secret".toCharArray(), context))
        assertEquals("Keychain access denied", secrets.saveUnavailableReason())
        assertEquals(denied, secrets.encrypt("secret".toCharArray(), context))
        assertEquals(Decrypted.NoKey("Keychain access denied"), secrets.decrypt(validValue(), context))
        assertEquals(1, backend.calls)

        secrets.tryAgain()
        backend.onRead = null
        backend.stored = ByteArray(32)

        assertNull(secrets.saveUnavailableReason())
        assertInstanceOf<Encrypted.Value>(secrets.encrypt("secret".toCharArray(), context))
        assertEquals(2, backend.reads)
    }

    @Test
    fun `a failed read fails only that call`() {
        backend.onRead = { KeyRead.Failed("timed out") }

        assertEquals(Encrypted.Failed("timed out"), secrets.encrypt("secret".toCharArray(), context))
        assertNull(secrets.saveUnavailableReason())
        assertEquals(Decrypted.NoKey("timed out"), secrets.decrypt(validValue(), context))

        assertEquals(2, backend.reads)
        assertEquals(0, backend.creates)
    }

    @Test
    fun `a throwing store fails only that call and names only the exception class`() {
        backend.onRead = { throw IllegalStateException("value 1234") }

        val failed = assertInstanceOf<Encrypted.Failed>(secrets.encrypt("secret".toCharArray(), context))
        assertEquals("the secret store failed (java.lang.IllegalStateException)", failed.reason)
        assertNull(secrets.saveUnavailableReason())

        backend.onRead = null
        backend.onCreate = { throw IllegalStateException() }
        assertInstanceOf<Encrypted.Failed>(secrets.encrypt("secret".toCharArray(), context))
        assertTrue(backend.handed.single().all { it == 0.toByte() }, "the handed copy is zeroed")

        backend.onDelete = { throw IllegalStateException() }
        assertFalse(secrets.forgetAll())
    }

    @Test
    fun `a stored key of the wrong length is zeroed, not used and not replaced`() {
        backend.stored = ByteArray(16) { 3 }

        assertInstanceOf<Encrypted.Failed>(secrets.encrypt("secret".toCharArray(), context))
        assertInstanceOf<Decrypted.NoKey>(secrets.decrypt(validValue(), context))

        assertEquals(0, backend.creates)
        assertEquals(16, backend.stored?.size)
        assertEquals(2, backend.found.size)
        assertTrue(backend.found.all { array -> array.all { it == 0.toByte() } })
    }

    @Test
    fun `an unavailable store is never called`() {
        backend.unavailable = "needs secret-tool"

        assertEquals("needs secret-tool", secrets.saveUnavailableReason())
        assertEquals(Encrypted.Failed("needs secret-tool"), secrets.encrypt("secret".toCharArray(), context))
        assertEquals(Decrypted.NoKey("needs secret-tool"), secrets.decrypt(validValue(), context))
        assertFalse(secrets.forgetAll())

        assertEquals(0, backend.calls)
    }

    @Test
    fun `without a store there is no store name`() {
        val none = Secrets(NoBackend, SecureRandom())

        assertNull(none.storeName)
        assertEquals("not supported on this system", none.saveUnavailableReason())
        assertEquals(Encrypted.Failed("not supported on this system"), none.encrypt("secret".toCharArray(), context))
    }

    @Test
    fun `a malformed value is unreadable without asking the store`() {
        backend.stored = ByteArray(32)

        assertEquals(Decrypted.Unreadable, secrets.decrypt("v2:abc", context))
        assertEquals(Decrypted.Unreadable, secrets.decrypt("plain text", context))

        assertEquals(0, backend.calls)
    }

    @Test
    fun `forget all deletes the key, zeroes the read key, and the next encrypt reads and creates again`() {
        backend.stored = ByteArray(32) { 1 }
        val old = secrets.encryptText("secret")
        val kept = backend.found.single()

        assertTrue(secrets.forgetAll())

        assertTrue(kept.all { it == 0.toByte() }, "the key in memory is zeroed")
        assertEquals(1, backend.deletes)
        assertNull(backend.stored)
        assertEquals(Decrypted.Unreadable, secrets.decrypt(old, context))
        assertInstanceOf<Encrypted.Value>(secrets.encrypt("secret".toCharArray(), context))
        assertEquals(3, backend.reads)
        assertEquals(1, backend.creates)
    }

    @Test
    fun `forget all zeroes a created key`() {
        secrets.encryptText("secret")
        zeroed.clear()

        secrets.forgetAll()

        assertEquals(1, zeroed.size)
        assertTrue(zeroed.single().all { it == 0.toByte() })
    }

    @Test
    fun `forget all leaves the denied state and reports a failed delete`() {
        backend.onRead = { KeyRead.Denied("Keychain access denied") }
        secrets.encrypt("secret".toCharArray(), context)
        backend.deleted = false

        assertFalse(secrets.forgetAll())
        assertNull(secrets.saveUnavailableReason())
    }

    @Test
    fun `concurrent first use reads the key once`() {
        backend.stored = ByteArray(32)
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        backend.onRead = {
            reading.countDown()
            release.await(10, TimeUnit.SECONDS)
            KeyRead.Found(backend.stored!!.copyOf())
        }
        val values = CopyOnWriteArrayList<Encrypted>()
        val threads = (1..4).map { thread { values += secrets.encrypt("secret".toCharArray(), context) } }

        assertTrue(reading.await(10, TimeUnit.SECONDS))
        // The other threads wait for the lock (parked) while the first one reads.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (threads.any { it.state != Thread.State.WAITING && it.state != Thread.State.TIMED_WAITING }) {
            check(System.nanoTime() < deadline) { "threads did not block" }
            Thread.onSpinWait()
        }
        release.countDown()
        threads.forEach { it.join(10_000) }

        assertEquals(1, backend.reads)
        assertEquals(4, values.filterIsInstance<Encrypted.Value>().size)
    }

    private fun Secrets.encryptText(plain: String) =
        assertInstanceOf<Encrypted.Value>(encrypt(plain.toCharArray(), context)).text

    private fun validValue() = SecretCipher(SecureRandom()).encrypt(ByteArray(32) { 5 }, "x".toCharArray(), context)

    /** An in-memory store that counts the calls that would reach the OS store and keeps the arrays it exchanged. */
    private class FakeBackend : KeyBackend {
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
}
