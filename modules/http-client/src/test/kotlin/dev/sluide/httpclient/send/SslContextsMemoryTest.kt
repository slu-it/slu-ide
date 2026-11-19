package dev.sluide.httpclient.send

import dev.sluide.httpclient.env.SslConfig
import dev.sluide.httpclient.env.SslConfig.CertificateFile
import dev.sluide.httpclient.send.TlsServer.Companion.fixture
import dev.sluide.httpclient.send.TlsServer.Companion.trustedCa
import dev.sluide.secrets.Decrypted
import dev.sluide.secrets.Encrypted
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import kotlin.io.path.copyTo
import kotlin.io.path.setLastModifiedTime
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * What [SslContexts] remembers of passphrases between calls: when one is kept, zeroed, marked as rejected or saved, and
 * how the per-files lock and `clear` interact with a call in progress. The key files are copies of the fixtures so that
 * their modification time can change.
 */
class SslContextsMemoryTest {
    @TempDir
    lateinit var folder: Path

    private data class Asked(
        val saveReason: String?,
        val saveChecked: Boolean,
    )

    private class Store(
        var value: String? = null,
    ) : PassphraseStore {
        var reason: String? = null
        var failure: String? = null
        var onLoad: () -> Unit = {}
        val loads = CopyOnWriteArrayList<List<Path>>()
        val savedArrays = CopyOnWriteArrayList<CharArray>()
        val removed = CopyOnWriteArrayList<List<Path>>()

        override fun unavailableReason() = reason

        override fun load(files: List<Path>): Decrypted? {
            loads += files
            onLoad()
            return value?.let { Decrypted.Plain(it.toCharArray()) }
        }

        override fun save(
            files: List<Path>,
            passphrase: CharArray,
        ): Encrypted {
            savedArrays += passphrase
            failure?.let { return Encrypted.Failed(it) }
            value = String(passphrase)
            return Encrypted.Value("v1:saved")
        }

        override fun remove(files: List<Path>) {
            removed += files
            value = null
        }
    }

    private var tick = 0L
    private val p12 by lazy { fixture("client.p12").copyTo(folder.resolve("client.p12")) }
    private val p12Config get() = SslConfig(CertificateFile(p12, null), null, true, verifyHostCertificate = false)

    private fun touch(path: Path) = path.setLastModifiedTime(FileTime.from(Instant.now().plusSeconds(++tick * 10)))

    private fun breakP12() {
        p12.writeBytes(byteArrayOf(1, 2, 3))
        touch(p12)
    }

    private fun restoreP12() {
        fixture("client.p12").copyTo(p12, overwrite = true)
        touch(p12)
    }

    private fun answer(
        text: String,
        save: Boolean = false,
    ) = PassphraseAnswer(text.toCharArray(), save)

    private fun contexts(
        store: PassphraseStore?,
        asked: MutableList<Asked>,
        vararg answers: PassphraseAnswer?,
        discarded: (SSLContext) -> Unit = {},
    ): SslContexts {
        val queue = ArrayDeque(answers.toList())
        return SslContexts(
            passphrase = { _, reason, checked ->
                asked += Asked(reason, checked)
                queue.removeFirst()
            },
            discarded = discarded,
            saved = store,
        )
    }

    /** The passphrases [contexts] holds in memory, by reflection: they are the one thing no API hands out. */
    private fun remembered(contexts: SslContexts): List<CharArray> {
        val field = SslContexts::class.java.getDeclaredField("passphrases").apply { isAccessible = true }
        val map = field.get(contexts) as Map<*, *>
        return map.values.map { entry ->
            val value = entry!!.javaClass.getDeclaredField("value").apply { isAccessible = true }
            value.get(entry) as CharArray
        }
    }

    /**
     * Whether the prompt that follows the rejection of a passphrase in memory starts with "Save passphrase" checked.
     * It does when that passphrase was the saved one. [again]: a second context is made from memory before the file
     * breaks.
     */
    private fun checkedAfterRejection(
        store: PassphraseStore?,
        first: PassphraseAnswer,
        again: Boolean = false,
    ): Boolean {
        restoreP12()
        val asked = mutableListOf<Asked>()
        val contexts = contexts(store, asked, first, answer("wrong"))
        contexts.context(p12Config)
        if (again) {
            touch(p12)
            contexts.context(p12Config)
        }
        asked.clear()
        breakP12()
        assertFailsWith<SslConfigException> { contexts.context(p12Config) }
        assertTrue(asked.isEmpty(), "the passphrase in memory is tried first")
        assertFailsWith<SslConfigException> { contexts.context(p12Config) }
        return asked.single().saveChecked
    }

    @Test
    fun `the prompt after a rejected passphrase starts checked only if that passphrase was saved`() {
        assertEquals(false, checkedAfterRejection(null, answer("secret")), "no store")
        assertEquals(
            false,
            checkedAfterRejection(Store().apply { reason = "needs secret-tool" }, answer("secret", true)),
        )
        assertEquals(
            false,
            checkedAfterRejection(Store().apply { failure = "full" }, answer("secret", true)),
            "save failed",
        )
        val unchecked = Store()
        assertEquals(false, checkedAfterRejection(unchecked, answer("secret", false)), "not saved")
        assertEquals(emptyList<List<Path>>(), unchecked.removed.toList(), "nothing saved, nothing to remove")
        assertEquals(true, checkedAfterRejection(Store(), answer("secret", true)), "saved")
    }

    @Test
    fun `a passphrase taken from memory is the saved one only if it was saved`() {
        assertEquals(false, checkedAfterRejection(Store(), answer("secret", false), again = true))
        assertEquals(true, checkedAfterRejection(Store(), answer("secret", true), again = true))
    }

    @Test
    fun `a prompted passphrase that was wrong does not mark a saved one as rejected`() {
        val store = Store()
        val asked = mutableListOf<Asked>()
        val contexts = contexts(store, asked, answer("wrong"), answer("secret"))

        assertFailsWith<SslConfigException> { contexts.context(p12Config) }
        contexts.context(p12Config)

        assertEquals(listOf(Asked(null, false), Asked(null, false)), asked)
        assertEquals(2, store.loads.size)
    }

    @Test
    fun `a prompted passphrase that worked lifts the mark of a rejected saved one`() {
        val store = Store("old")
        val asked = mutableListOf<Asked>()
        val contexts = contexts(store, asked, answer("secret"), answer("wrong"))
        assertFailsWith<SslConfigException> { contexts.context(p12Config) }
        contexts.context(p12Config)
        assertEquals(1, store.loads.size)

        breakP12()
        assertFailsWith<SslConfigException> { contexts.context(p12Config) }
        restoreP12()
        store.value = "secret"
        contexts.context(p12Config)

        assertEquals(2, store.loads.size, "the saved passphrase is looked at again")
    }

    @Test
    fun `a clear while the saved passphrase is loaded keeps it from being marked as rejected`() {
        val loading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val store =
            Store("wrong").apply {
                onLoad = {
                    loading.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
            }
        val contexts = contexts(store, mutableListOf(), answer("secret"))
        val executor = Executors.newSingleThreadExecutor()
        try {
            val pending = executor.submit<Result<SSLContext>> { runCatching { contexts.context(p12Config) } }
            assertTrue(loading.await(5, TimeUnit.SECONDS))
            contexts.clear()
            release.countDown()
            assertTrue(pending.get(5, TimeUnit.SECONDS).exceptionOrNull() is SslConfigException)
        } finally {
            executor.shutdownNow()
        }
        store.onLoad = {}
        store.value = "secret"

        contexts.context(p12Config)

        assertEquals(2, store.loads.size)
    }

    @Test
    fun `a failure that is no wrong passphrase keeps the passphrase in memory`() {
        val certificate = fixture("client.pem").copyTo(folder.resolve("client.pem"))
        val config =
            SslConfig(
                CertificateFile(certificate, null),
                CertificateFile(fixture("client-encrypted.key"), null),
                hasCertificatePassphrase = false,
                verifyHostCertificate = false,
            )
        val asked = mutableListOf<Asked>()
        val contexts = contexts(null, asked, answer("secret"))
        contexts.context(config)

        // The certificate no longer belongs to the key: not the passphrase's fault.
        fixture("server.pem").copyTo(certificate, overwrite = true)
        touch(certificate)
        assertTrue(
            "does not belong" in assertFailsWith<SslConfigException> { contexts.context(config) }.message.orEmpty(),
        )
        fixture("client.pem").copyTo(certificate, overwrite = true)
        touch(certificate)
        contexts.context(config)

        assertEquals(1, asked.size)
    }

    @Test
    fun `a failure of the prompt itself is passed on as it is`() {
        val contexts = SslContexts(passphrase = { _, _, _ -> throw IllegalStateException("window closed") })

        repeat(2) {
            val error = assertFailsWith<IllegalStateException> { contexts.context(p12Config) }
            assertEquals("window closed", error.message)
        }
    }

    @Test
    fun `a prompt that is cancelled fails without a passphrase and remembers nothing`() {
        val asked = mutableListOf<Asked>()
        val contexts = contexts(null, asked, null, answer("secret"))

        assertEquals(
            "No passphrase for client.p12 was given",
            assertFailsWith<SslConfigException> {
                contexts.context(p12Config)
            }.message,
        )
        contexts.context(p12Config)

        assertEquals(2, asked.size)
    }

    @Test
    fun `a context made across a clear is not kept, nor its passphrase`() {
        val prompting = CountDownLatch(1)
        val release = CountDownLatch(1)
        var asked = 0
        lateinit var contexts: SslContexts
        contexts =
            SslContexts({ _, _, _ ->
                asked++
                prompting.countDown()
                release.await(5, TimeUnit.SECONDS)
                answer("secret")
            })
        val executor = Executors.newSingleThreadExecutor()
        val first =
            try {
                val pending = executor.submit<SSLContext> { contexts.context(p12Config) }
                assertTrue(prompting.await(5, TimeUnit.SECONDS))
                contexts.clear()
                release.countDown()
                pending.get(5, TimeUnit.SECONDS)
            } finally {
                executor.shutdownNow()
            }

        val second = contexts.context(p12Config)

        assertNotSame(first, second)
        assertEquals(2, asked)
    }

    @Test
    fun `calls for the same files wait for each other and share the context`() {
        val prompting = CountDownLatch(1)
        val release = CountDownLatch(1)
        var asked = 0
        val contexts =
            SslContexts({ _, _, _ ->
                asked++
                prompting.countDown()
                release.await(5, TimeUnit.SECONDS)
                answer("secret")
            })
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<SSLContext> { contexts.context(p12Config) }
            assertTrue(prompting.await(5, TimeUnit.SECONDS))
            var waiting: Thread? = null
            val second =
                executor.submit<SSLContext> {
                    waiting = Thread.currentThread()
                    contexts.context(p12Config)
                }
            // The second call parks on the per-files lock while the first one prompts.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (waiting?.state != Thread.State.WAITING && System.nanoTime() < deadline) Thread.onSpinWait()
            assertEquals(Thread.State.WAITING, waiting?.state)
            release.countDown()

            assertSame(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS))
            assertEquals(1, asked)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `the lock of the files is released after each call`() {
        val contexts = contexts(null, mutableListOf(), answer("secret"))
        val executor = Executors.newFixedThreadPool(2)
        try {
            executor.submit { contexts.context(p12Config) }.get(5, TimeUnit.SECONDS)
            touch(p12)

            assertTimeoutPreemptively(Duration.ofSeconds(5)) {
                executor.submit { contexts.context(p12Config) }.get()
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `a replaced context is handed on when its replacement is made`() {
        val discarded = CopyOnWriteArrayList<SSLContext>()
        val contexts = contexts(null, mutableListOf(), answer("secret"), discarded = discarded::add)

        val first = contexts.context(p12Config)
        assertEquals(emptyList<SSLContext>(), discarded.toList())
        touch(p12)
        contexts.context(p12Config)

        assertEquals(listOf(first), discarded.toList())
    }

    @Test
    fun `a saved passphrase is zeroed after saving and the ones in memory when cleared or rejected`() {
        val store = Store()
        val contexts = contexts(store, mutableListOf(), answer("secret", save = true), answer("wrong"))
        contexts.context(p12Config)

        assertContentEquals(CharArray(6), store.savedArrays.single())
        assertEquals("secret", store.value)

        val inMemory = remembered(contexts).single()
        assertContentEquals("secret".toCharArray(), inMemory)
        breakP12()
        assertFailsWith<SslConfigException> { contexts.context(p12Config) }
        assertContentEquals(CharArray(6), inMemory, "a rejected passphrase is wiped")

        restoreP12()
        val another = contexts(null, mutableListOf(), answer("secret"))
        another.context(p12Config)
        val kept = remembered(another).single()
        another.clear()
        assertContentEquals(CharArray(6), kept, "clear wipes the passphrases")
        assertEquals(emptyList<CharArray>(), remembered(another))
    }
}
