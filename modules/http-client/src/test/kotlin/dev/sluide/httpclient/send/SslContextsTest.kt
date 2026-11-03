package dev.sluide.httpclient.send

import dev.sluide.httpclient.env.SslConfig
import dev.sluide.httpclient.env.SslConfig.CertificateFile
import dev.sluide.httpclient.env.SslConfig.CertificateFormat
import dev.sluide.httpclient.send.TlsServer.Companion.fixture
import dev.sluide.httpclient.send.TlsServer.Companion.trustedCa
import dev.sluide.secrets.Decrypted
import dev.sluide.secrets.Encrypted
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.KeyStore
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import kotlin.io.path.copyTo
import kotlin.io.path.readBytes
import kotlin.io.path.setLastModifiedTime
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SslContextsTest {
    private val server = TlsServer()
    private val clients = HttpClients(proxy = HttpClient.Builder.NO_PROXY)

    @TempDir
    lateinit var folder: Path

    @AfterTest
    fun stop() {
        clients.close()
        server.close()
    }

    private fun config(
        certificate: String?,
        key: String? = null,
        passphrase: Boolean = false,
        verify: Boolean = true,
    ) = SslConfig(
        certificate?.let { CertificateFile(fixture(it), null) },
        key?.let { CertificateFile(fixture(it), null) },
        passphrase,
        verify,
    )

    private fun create(
        config: SslConfig,
        passphrase: String? = null,
        trustStore: KeyStore? = trustedCa(),
    ) = SslContexts.create(config, { passphrase?.toCharArray() }, trustStore)

    /** The subject of the client certificate the server saw. */
    private fun get(
        context: SSLContext,
        host: String = "localhost",
    ): String {
        val key = HttpClients.ClientKey(followRedirects = false, cookieJar = false, Duration.ofSeconds(5), context)
        val request =
            HttpRequest
                .newBuilder(
                    URI("https://$host:${server.port}/"),
                ).timeout(Duration.ofSeconds(10))
                .build()
        val response = clients.client(key).send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, response.statusCode())
        return response.body()
    }

    private fun answer(
        text: String,
        save: Boolean = false,
    ) = PassphraseAnswer(text.toCharArray(), save)

    /** What the prompt was asked with: why saving is unavailable and whether "Save passphrase" starts checked. */
    private data class Asked(
        val saveReason: String?,
        val saveChecked: Boolean,
    )

    /** Saved passphrases in memory; [load] answers [noKey] instead of the value when set. */
    private class FakeStore(
        var value: String? = null,
    ) : PassphraseStore {
        var reason: String? = null
        var noKey: String? = null
        var failure: String? = null
        val loads = CopyOnWriteArrayList<List<Path>>()
        val saved = CopyOnWriteArrayList<String>()
        val removed = CopyOnWriteArrayList<List<Path>>()

        override fun unavailableReason() = reason

        override fun load(files: List<Path>): Decrypted? {
            loads += files
            noKey?.let { return Decrypted.NoKey(it) }
            return value?.let { Decrypted.Plain(it.toCharArray()) }
        }

        override fun save(
            files: List<Path>,
            passphrase: CharArray,
        ): Encrypted {
            saved += String(passphrase)
            failure?.let { return Encrypted.Failed(it) }
            value = String(passphrase)
            return Encrypted.Value("v1:saved")
        }

        override fun remove(files: List<Path>) {
            removed += files
            value = null
        }
    }

    private val encrypted = config("client.pem", "client-encrypted.key")
    private val encryptedFiles = listOf(fixture("client.pem"), fixture("client-encrypted.key"))

    /** Contexts with [store] whose prompt records how it was asked and gives the [answers] in turn. */
    private fun contexts(
        store: FakeStore?,
        asked: MutableList<Asked>,
        vararg answers: PassphraseAnswer,
        notSaved: (String) -> Unit = {},
    ): SslContexts {
        val queue = ArrayDeque(answers.toList())
        return SslContexts(
            passphrase = { _, reason, checked ->
                asked += Asked(reason, checked)
                queue.removeFirst()
            },
            trustStore = trustedCa(),
            saved = store,
            notSaved = notSaved,
        )
    }

    private fun failure(
        config: SslConfig,
        passphrase: String? = null,
    ) = assertFailsWith<SslConfigException> { create(config, passphrase) }.message.orEmpty()

    @Test
    fun `uses a PEM certificate with a PKCS 8 key`() {
        assertEquals("CN=SluIde Test Client", get(create(config("client.pem", "client.key"))))
    }

    @Test
    fun `uses an encrypted PKCS 8 key with its passphrase`() {
        assertEquals("CN=SluIde Test Client", get(create(config("client.pem", "client-encrypted.key"), "secret")))
    }

    @Test
    fun `uses a PKCS 1 RSA key`() {
        assertEquals("CN=SluIde Test Client", get(create(config("client.pem", "client-pkcs1.key"))))
    }

    @Test
    fun `uses a P12 file with its passphrase`() {
        assertEquals("CN=SluIde Test Client", get(create(config("client.p12", passphrase = true), "secret")))
    }

    @Test
    fun `uses DER files, by extension or by the given format`() {
        assertEquals("CN=SluIde Test Client", get(create(config("client-cert.der", "client-key.der"))))

        val certificate = fixture("client-cert.der").copyTo(folder.resolve("client.crt"))
        val key = fixture("client-key.der").copyTo(folder.resolve("client.bin"))
        val config =
            SslConfig(
                CertificateFile(certificate, CertificateFormat.DER),
                CertificateFile(key, CertificateFormat.DER),
                hasCertificatePassphrase = false,
                verifyHostCertificate = true,
            )
        assertEquals("CN=SluIde Test Client", get(create(config)))
    }

    @Test
    fun `cannot connect without a client certificate`() {
        assertFailsWith<IOException> { get(create(config(null))) }
    }

    @Test
    fun `verifies the host certificate and its name unless told not to`() {
        val trustAll = create(config("client.pem", "client.key", verify = false), trustStore = null)
        assertEquals("CN=SluIde Test Client", get(trustAll, "127.0.0.1"))
        assertEquals("CN=SluIde Test Client", get(trustAll, "localhost"))

        // The server certificate names localhost only.
        val verifying = create(config("client.pem", "client.key"))
        assertTrue(
            "No subject alternative" in assertFailsWith<IOException> { get(verifying, "127.0.0.1") }.message.orEmpty(),
        )
        // The JDK's cacerts do not know the test CA.
        val defaultTrust = create(config("client.pem", "client.key"), trustStore = null)
        assertFailsWith<IOException> { get(defaultTrust, "localhost") }
    }

    @Test
    fun `reports keys that cannot be used`() {
        assertTrue("openssl pkcs8 -topk8" in failure(config("client.pem", "client-ec.key")))
        assertEquals(
            "The key other.key does not belong to the certificate client.pem",
            failure(config("client.pem", "other.key")),
        )
        assertTrue(
            "cannot be decrypted; is the passphrase right?" in
                failure(config("client.pem", "client-encrypted.key"), "wrong"),
        )
        assertEquals(
            "client-encrypted.key is encrypted, but no passphrase was given",
            failure(config("client.pem", "client-encrypted.key")),
        )
        assertTrue("set hasCertificatePassphrase" in failure(config("client.p12")))
        assertTrue("needs a clientCertificateKey" in failure(config("client.pem")))
        val missing =
            config(
                null,
                "client.key",
            ).copy(clientCertificate = CertificateFile(folder.resolve("a.pem"), null))
        assertTrue("does not exist" in failure(missing))
        assertTrue("no clientCertificate" in failure(config(null, "client.key")))
    }

    @Test
    fun `keeps one context per configuration and asks for the passphrase once`() {
        var asked = 0
        val contexts =
            SslContexts(
                passphrase = { _, _, _ ->
                    asked++
                    answer("secret")
                },
                trustStore = trustedCa(),
            )
        val certificate = fixture("client.pem").copyTo(folder.resolve("client.pem"))
        val config =
            config(null, "client-encrypted.key").copy(clientCertificate = CertificateFile(certificate, null))

        val first = contexts.context(config)
        assertSame(first, contexts.context(config.copy()))
        certificate.setLastModifiedTime(FileTime.from(Instant.now().plusSeconds(10)))
        val second = contexts.context(config)

        assertNotSame(first, second)
        assertEquals(1, asked)
        assertEquals("CN=SluIde Test Client", get(second))
        contexts.clear()
        contexts.context(config)
        assertEquals(2, asked)
    }

    @Test
    fun `asks again after a wrong passphrase`() {
        val answers = ArrayDeque(listOf("wrong", "secret"))
        val contexts = SslContexts(passphrase = { _, _, _ -> answer(answers.removeFirst()) }, trustStore = trustedCa())
        val config = config("client.p12", passphrase = true)

        assertFailsWith<SslConfigException> { contexts.context(config) }
        contexts.context(config)

        assertTrue(answers.isEmpty())
    }

    @Test
    fun `finds the certificate of the key in a chain in any order`() {
        val chain = folder.resolve("chain.pem")
        chain.writeBytes(fixture("ca.pem").readBytes() + fixture("client.pem").readBytes())
        val config = config(null, "client.key").copy(clientCertificate = CertificateFile(chain, null))

        assertEquals("CN=SluIde Test Client", get(create(config)))
    }

    @Test
    fun `reports unsupported key encryption without asking for a passphrase`() {
        var asked = false

        val message =
            assertFailsWith<SslConfigException> {
                SslContexts.create(config("client.pem", "client-des3.key"), {
                    asked = true
                    "secret".toCharArray()
                })
            }.message.orEmpty()

        assertTrue("not supported" in message && "openssl pkcs8 -topk8 -v2 aes-256-cbc" in message, message)
        assertEquals(false, asked)
    }

    @Test
    fun `keeps a right passphrase when something else fails`() {
        var asked = 0
        val contexts =
            SslContexts({ _, _, _ ->
                asked++
                answer("secret")
            }, trustedCa())
        // The server certificate does not belong to the client key.
        val config = config("server.pem", "client-encrypted.key")

        repeat(2) {
            assertTrue(
                "does not belong" in assertFailsWith<SslConfigException> { contexts.context(config) }.message.orEmpty(),
            )
        }

        assertEquals(1, asked)
    }

    @Test
    fun `does not hold up other configurations or clear while a prompt is open`() {
        val prompting = CountDownLatch(1)
        val answer = CountDownLatch(1)
        val contexts =
            SslContexts({ _, _, _ ->
                prompting.countDown()
                answer.await()
                answer("secret")
            }, trustedCa())
        val pending = CompletableFuture.supplyAsync { contexts.context(config("client.pem", "client-encrypted.key")) }
        assertTrue(prompting.await(5, TimeUnit.SECONDS))

        assertTimeoutPreemptively(Duration.ofSeconds(5)) {
            contexts.context(config("client.pem", "client.key"))
            contexts.clear()
        }

        answer.countDown()
        pending.get(5, TimeUnit.SECONDS)
    }

    @Test
    fun `hands replaced and cleared contexts on`() {
        val discarded = CopyOnWriteArrayList<SSLContext>()
        val contexts = SslContexts(trustStore = trustedCa(), discarded = discarded::add)
        val certificate = fixture("client.pem").copyTo(folder.resolve("client.pem"))
        val config = config(null, "client.key").copy(clientCertificate = CertificateFile(certificate, null))

        val first = contexts.context(config)
        certificate.setLastModifiedTime(FileTime.from(Instant.now().plusSeconds(10)))
        val second = contexts.context(config)
        contexts.clear()

        assertEquals(listOf(first, second), discarded.toList())
    }

    @Test
    fun `stops the HTTP clients of an evicted context`() {
        val context = create(config("client.pem", "client.key"))
        val key = HttpClients.ClientKey(followRedirects = false, cookieJar = false, Duration.ofSeconds(5), context)
        val client = clients.client(key)

        clients.evict(context)

        assertNotSame(client, clients.client(key))
        assertTrue(client.isTerminated || client.awaitTermination(Duration.ofSeconds(5)))
    }

    @Test
    fun `uses a saved passphrase without asking`() {
        val store = FakeStore("secret")
        val asked = mutableListOf<Asked>()
        val contexts = contexts(store, asked)

        assertEquals("CN=SluIde Test Client", get(contexts.context(encrypted)))

        assertEquals(listOf(encryptedFiles), store.loads.toList())
        assertEquals(emptyList<Asked>(), asked)
        assertEquals(emptyList<String>(), store.saved.toList())
    }

    @Test
    fun `asks with Save passphrase checked only when a saved one exists`() {
        val asked = mutableListOf<Asked>()
        contexts(FakeStore(), asked, answer("secret")).context(encrypted)
        contexts(FakeStore("secret").apply { noKey = "Keychain access denied" }, asked, answer("secret", save = true))
            .context(encrypted)
        contexts(FakeStore().apply { reason = "needs secret-tool" }, asked, answer("secret")).context(encrypted)
        contexts(null, asked, answer("secret")).context(encrypted)

        assertEquals(
            listOf(
                Asked(null, false),
                Asked(null, true),
                Asked("needs secret-tool", false),
                Asked("not supported", false),
            ),
            asked,
        )
    }

    @Test
    fun `saves a prompted passphrase only after it worked`() {
        val store = FakeStore()
        val contexts = contexts(store, mutableListOf(), answer("wrong", save = true), answer("secret", save = true))

        assertFailsWith<SslConfigException> { contexts.context(encrypted) }
        assertEquals(emptyList<String>(), store.saved.toList())
        contexts.context(encrypted)

        assertEquals(listOf("secret"), store.saved.toList())
    }

    @Test
    fun `tells why a passphrase was not saved`() {
        val store = FakeStore().apply { failure = "needs secret-tool" }
        val reasons = mutableListOf<String>()
        val contexts = contexts(store, mutableListOf(), answer("secret", save = true), notSaved = reasons::add)

        contexts.context(encrypted)

        assertEquals(listOf("needs secret-tool"), reasons)
    }

    @Test
    fun `removes the saved passphrase when Save passphrase was unchecked`() {
        val store = FakeStore("old").apply { noKey = "the secret store failed" }
        val contexts = contexts(store, mutableListOf(), answer("secret", save = false))

        contexts.context(encrypted)

        assertEquals(listOf(encryptedFiles), store.removed.toList())
        assertEquals(emptyList<String>(), store.saved.toList())
    }

    @Test
    fun `a rejected saved passphrase is not loaded again and stays until a prompted one is saved`() {
        val store = FakeStore("old")
        val asked = mutableListOf<Asked>()
        val contexts = contexts(store, asked, answer("wrong", save = true), answer("secret", save = true))

        assertFailsWith<SslConfigException> { contexts.context(encrypted) }
        assertFailsWith<SslConfigException> { contexts.context(encrypted) }
        assertEquals("old", store.value)
        contexts.context(encrypted)

        assertEquals(1, store.loads.size)
        assertEquals(listOf(Asked(null, true), Asked(null, true)), asked)
        assertEquals("secret", store.value)
        assertEquals(emptyList<List<Path>>(), store.removed.toList())
    }

    @Test
    fun `clear loads a rejected saved passphrase again`() {
        val store = FakeStore("old")
        val contexts = contexts(store, mutableListOf(), answer("wrong"))
        assertFailsWith<SslConfigException> { contexts.context(encrypted) }
        assertFailsWith<SslConfigException> { contexts.context(encrypted) }
        assertEquals(1, store.loads.size)

        store.value = "secret"
        contexts.clear()
        contexts.context(encrypted)

        assertEquals(2, store.loads.size)
    }

    @Test
    fun `keeps a saved passphrase when the box was disabled`() {
        val store =
            FakeStore("old").apply {
                noKey = "Keychain access denied"
                reason = "Keychain access denied"
            }
        val asked = mutableListOf<Asked>()
        val reasons = mutableListOf<String>()
        val contexts = contexts(store, asked, answer("secret", save = true), notSaved = reasons::add)

        contexts.context(encrypted)

        assertEquals(listOf(Asked("Keychain access denied", true)), asked)
        assertEquals(emptyList<String>(), store.saved.toList())
        assertEquals(emptyList<List<Path>>(), store.removed.toList())
        assertEquals(emptyList<String>(), reasons)
    }

    @Test
    fun `an unreadable saved passphrase counts as none`() {
        val asked = mutableListOf<Asked>()
        val store =
            object : PassphraseStore by FakeStore() {
                override fun load(files: List<Path>) = Decrypted.Unreadable
            }

        SslContexts({ _, reason, checked ->
            asked += Asked(reason, checked)
            answer("secret")
        }, trustedCa(), saved = store).context(encrypted)

        assertEquals(listOf(Asked(null, false)), asked)
    }

    /**
     * A passphrase in memory that is the saved one (loaded, or saved after the prompt) and is rejected once the PKCS#12
     * file changed counts as a rejected saved one: it is not loaded again, and the prompt starts checked.
     */
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `a rejected passphrase in memory that is the saved one is not loaded again`(loaded: Boolean) {
        val file = fixture("client.p12").copyTo(folder.resolve("client.p12"))
        val config = config(null, passphrase = true).copy(clientCertificate = CertificateFile(file, null))
        val store = FakeStore(if (loaded) "secret" else null)
        val asked = mutableListOf<Asked>()
        val first = if (loaded) arrayOf() else arrayOf(answer("secret", save = true))
        val contexts = contexts(store, asked, *first, answer("wrong"))
        contexts.context(config)
        asked.clear()

        file.writeBytes(byteArrayOf(1, 2, 3))
        file.setLastModifiedTime(FileTime.from(Instant.now().plusSeconds(10)))
        assertFailsWith<SslConfigException> { contexts.context(config) }
        assertFailsWith<SslConfigException> { contexts.context(config) }

        assertEquals(1, store.loads.size, "loaded only for the first context")
        assertEquals(listOf(Asked(null, true)), asked)
    }

    @Test
    fun `does not tell about a failed save after the window closed`() {
        val reasons = mutableListOf<String>()
        lateinit var contexts: SslContexts
        contexts =
            SslContexts({ _, _, _ ->
                contexts.clear()
                answer("secret", save = true)
            }, trustedCa(), saved = FakeStore().apply { failure = "needs secret-tool" }, notSaved = reasons::add)

        contexts.context(encrypted)

        assertEquals(emptyList<String>(), reasons)
    }
}
