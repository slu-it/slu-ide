package dev.sluide.httpclient.send

import dev.sluide.httpclient.env.SslConfig
import dev.sluide.httpclient.env.SslConfig.CertificateFile
import dev.sluide.httpclient.env.SslConfig.CertificateFormat
import dev.sluide.httpclient.send.TlsServer.Companion.fixture
import dev.sluide.httpclient.send.TlsServer.Companion.trustedCa
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.junit.jupiter.api.io.TempDir
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
                passphrase = {
                    asked++
                    "secret".toCharArray()
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
        val contexts = SslContexts(passphrase = { answers.removeFirst().toCharArray() }, trustStore = trustedCa())
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
            SslContexts({
                asked++
                "secret".toCharArray()
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
            SslContexts({
                prompting.countDown()
                answer.await()
                "secret".toCharArray()
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
}
