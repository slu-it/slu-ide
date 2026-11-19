package dev.sluide.httpclient.send

import dev.sluide.httpclient.env.SslConfig
import dev.sluide.httpclient.env.SslConfig.CertificateFile
import dev.sluide.httpclient.env.SslConfig.CertificateFormat
import dev.sluide.httpclient.send.TlsServer.Companion.fixture
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectory
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What [SslContextFactory] says about client certificates and keys it cannot use, and the formats it reads. The keys
 * are the test-only ones of `src/test/resources/ssl` (see its README) or made here; passphrases are the fixtures' and
 * never appear in an assertion message.
 */
class SslContextFactoryTest {
    @TempDir
    lateinit var folder: Path

    private val pkcs8Hint = "convert it with: openssl pkcs8 -topk8 -nocrypt -in key.pem -out key-pkcs8.pem"
    private val encryptionHint = "convert it with: openssl pkcs8 -topk8 -v2 aes-256-cbc -in key.pem -out key-aes.pem"

    private fun file(
        path: Path,
        format: CertificateFormat? = null,
    ) = CertificateFile(path, format)

    private fun config(
        certificate: Path?,
        key: Path? = null,
        passphrase: Boolean = false,
        certificateFormat: CertificateFormat? = null,
        keyFormat: CertificateFormat? = null,
    ) = SslConfig(
        certificate?.let { file(it, certificateFormat) },
        key?.let { file(it, keyFormat) },
        passphrase,
        verifyHostCertificate = false,
    )

    private fun create(
        config: SslConfig,
        passphrase: CharArray? = null,
    ) = SslContextFactory.create(config, { passphrase }, null)

    private fun failure(
        config: SslConfig,
        passphrase: CharArray? = null,
    ) = assertFailsWith<SslConfigException> { create(config, passphrase) }

    private fun write(
        name: String,
        text: String,
    ) = folder.resolve(name).also { it.writeText(text) }

    private fun pem(
        type: String,
        der: ByteArray,
    ) = "-----BEGIN $type-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) +
        "\n-----END $type-----\n"

    /** The DER of the first block of a PEM [file]. */
    private fun derOf(file: Path): ByteArray =
        Base64.getMimeDecoder().decode(
            file
                .readText()
                .lines()
                .filter { it.isNotBlank() && !it.startsWith("-----") && ':' !in it }
                .joinToString(""),
        )

    @Test
    fun `a missing or unreadable file is named in full`() {
        val missing = folder.resolve("missing.key")
        assertEquals(
            "The client certificate key $missing does not exist",
            failure(config(fixture("client.pem"), missing)).message,
        )

        val directory = folder.resolve("dir").also { it.createDirectory() }
        val error = failure(config(directory, fixture("client.key")))
        assertIs<IOException>(error.cause)
        assertEquals("The client certificate $directory cannot be read: ${error.cause?.message}", error.message)
        val keyError = failure(config(fixture("client.pem"), directory))
        assertEquals(
            "The client certificate key $directory cannot be read: ${keyError.cause?.message}",
            keyError.message,
        )
    }

    @Test
    fun `a truncated or corrupt PEM certificate is refused with its file name`() {
        val truncated =
            folder
                .resolve(
                    "truncated.pem",
                ).also { it.writeBytes(fixture("client.pem").readBytes().copyOf(120)) }
        assertEquals(
            "truncated.pem contains no PEM CERTIFICATE",
            failure(config(truncated, fixture("client.key"))).message,
        )

        val keyOnly = fixture("client.key")
        assertEquals("client.key contains no PEM CERTIFICATE", failure(config(keyOnly, keyOnly)).message)

        val garbled = write("garbled.pem", "-----BEGIN CERTIFICATE-----\n!!!!\n-----END CERTIFICATE-----\n")
        val base64 = failure(config(garbled, fixture("client.key")))
        assertEquals("A PEM CERTIFICATE block is no valid Base64", base64.message)
        assertIs<IllegalArgumentException>(base64.cause)

        val notACertificate = write("empty.pem", pem("CERTIFICATE", ByteArray(40) { it.toByte() }))
        val invalid = failure(config(notACertificate, fixture("client.key")))
        assertEquals("empty.pem is no valid certificate: ${invalid.cause?.message}", invalid.message)
        assertNotNull(invalid.cause)
    }

    @Test
    fun `a certificate and key may share one PEM file, other blocks are skipped`() {
        val both = write("both.pem", fixture("client.key").readText() + fixture("client.pem").readText())

        create(config(both, both))
    }

    @Test
    fun `PEM files with Windows line breaks, blank lines and indentation are read`() {
        val text = fixture("client.pem").readText().replace("\n", "\r\n")
        val crlf = write("crlf.pem", text)
        val key = write("crlf.key", fixture("client.key").readText().lines().joinToString("\r\n\r\n  "))

        create(config(crlf, key))
    }

    @Test
    fun `a PEM block ends with its own type`() {
        val mismatched = write("mixed.pem", fixture("client.pem").readText().replace("END CERTIFICATE", "END X509"))

        assertEquals(
            "mixed.pem contains no PEM CERTIFICATE",
            failure(config(mismatched, fixture("client.key"))).message,
        )
    }

    @Test
    fun `keys of the wrong kind are refused with the file name and a hint`() {
        assertEquals(
            "clientCertificateKey client.p12 must be PEM or DER; a P12 file goes in clientCertificate",
            failure(config(fixture("client.pem"), fixture("client.p12"))).message,
        )
        assertEquals(
            "client.pem contains no PEM PRIVATE KEY",
            failure(config(fixture("client.pem"), fixture("client.pem"))).message,
        )
        assertEquals(
            "client-ec.key is an EC key in SEC1 form, which is not supported; $pkcs8Hint",
            failure(config(fixture("client.pem"), fixture("client-ec.key"))).message,
        )
        val dsa = write("dsa.key", pem("DSA PRIVATE KEY", ByteArray(20)))
        assertEquals(
            "dsa.key: DSA PRIVATE KEY is not supported; $pkcs8Hint",
            failure(config(fixture("client.pem"), dsa)).message,
        )
    }

    @Test
    fun `a certificate without a key file says which file needs one`() {
        assertEquals(
            "The client certificate client.pem needs a clientCertificateKey " +
                "(or use a P12 file with certificate and key)",
            failure(config(fixture("client.pem"))).message,
        )
    }

    @Test
    fun `block types that only hash like the supported ones are not supported`() {
        // The same String.hashCode as "RSA PRIVATE KEY" and "ENCRYPTED PRIVATE KEY": the type is compared as text.
        for ((type, twin) in listOf(
            "RSA PRIVATE KEY" to "S4A PRIVATE KEY",
            "ENCRYPTED PRIVATE KEY" to "END3YPTED PRIVATE KEY",
        )) {
            assertEquals(type.hashCode(), twin.hashCode())
            val key = write("twin.key", pem(twin, derOf(fixture("client-pkcs1.key"))))
            assertEquals(
                "twin.key: $twin is not supported; $pkcs8Hint",
                failure(config(fixture("client.pem"), key)).message,
            )
        }
    }

    @Test
    fun `an encryption the JDK has no algorithm for is named`() {
        // SEQUENCE { SEQUENCE { OID 1.2.3.4, NULL }, OCTET STRING { 00 } }
        val unknown =
            byteArrayOf(0x30, 0x0c, 0x30, 0x07, 0x06, 0x03, 0x2a, 0x03, 0x04, 0x05, 0x00, 0x04, 0x01, 0x00)
        val key = write("unknown.key", pem("ENCRYPTED PRIVATE KEY", unknown))
        var asked = false

        val error =
            assertFailsWith<SslConfigException> {
                SslContextFactory.create(config(fixture("client.pem"), key), {
                    asked = true
                    null
                }, null)
            }

        assertEquals("unknown.key uses an unsupported key encryption 1.2.3.4; $encryptionHint", error.message)
        assertFalse(asked)
    }

    @Test
    fun `an encrypted PKCS 1 key is refused, one with unrelated headers is read`() {
        val pkcs1 = derOf(fixture("client-pkcs1.key"))
        val encrypted =
            write(
                "encrypted-pkcs1.key",
                "-----BEGIN RSA PRIVATE KEY-----\nProc-Type: 4,ENCRYPTED\nDEK-Info: AES-128-CBC,0011\n\n" +
                    Base64.getEncoder().encodeToString(pkcs1) + "\n-----END RSA PRIVATE KEY-----\n",
            )
        assertEquals(
            "encrypted-pkcs1.key is an encrypted PKCS#1 key, which is not supported; $pkcs8Hint",
            failure(config(fixture("client.pem"), encrypted)).message,
        )

        val comment = Base64.getEncoder().encodeToString(pkcs1)
        for (header in listOf("Comment: ENCRYPTED", "Proc-Type: 4,MIC-ONLY")) {
            val plain =
                write(
                    "plain-pkcs1.key",
                    "-----BEGIN RSA PRIVATE KEY-----\n$header\n\n$comment\n-----END RSA PRIVATE KEY-----\n",
                )
            create(config(fixture("client.pem"), plain))
        }
    }

    @Test
    fun `a broken PKCS 8 key or encryption block says what is wrong`() {
        val broken = write("broken.key", pem("PRIVATE KEY", ByteArray(30) { 1 }))
        val error = failure(config(fixture("client.pem"), broken))
        assertEquals("broken.key is no valid private key: ${error.cause?.message}", error.message)
        assertNotNull(error.cause)

        val encrypted = write("broken-enc.key", pem("ENCRYPTED PRIVATE KEY", ByteArray(30) { 1 }))
        val info = failure(config(fixture("client.pem"), encrypted))
        assertEquals(
            "broken-enc.key is encrypted in a way that is not supported or invalid (${info.cause?.message}); $encryptionHint",
            info.message,
        )
        assertIs<IOException>(info.cause)
    }

    @Test
    fun `DER keys may be plain, PKCS 1 or encrypted`() {
        val pkcs1 = folder.resolve("pkcs1.der").also { it.writeBytes(derOf(fixture("client-pkcs1.key"))) }
        create(config(fixture("client-cert.der"), pkcs1, keyFormat = CertificateFormat.DER))

        val encrypted = folder.resolve("encrypted.der").also { it.writeBytes(derOf(fixture("client-encrypted.key"))) }
        create(config(fixture("client-cert.der"), encrypted, keyFormat = CertificateFormat.DER), "secret".toCharArray())
        val rejected =
            failure(
                config(fixture("client-cert.der"), encrypted, keyFormat = CertificateFormat.DER),
                "wrong".toCharArray(),
            )
        assertTrue(rejected.passphraseRejected)
        assertEquals(
            "encrypted.der cannot be decrypted; is the passphrase right? (${rejected.cause?.message})",
            rejected.message,
        )

        val garbage = folder.resolve("garbage.der").also { it.writeBytes(ByteArray(64) { 7 }) }
        assertEquals(
            "garbage.der is no PKCS#8 or PKCS#1 RSA key in DER form; $pkcs8Hint",
            failure(config(fixture("client-cert.der"), garbage, keyFormat = CertificateFormat.DER)).message,
        )
    }

    @Test
    fun `the format is the given one, else the extension, else the content`() {
        val upper = fixture("client.p12").copyTo(folder.resolve("CLIENT.P12"))
        create(config(upper, passphrase = true), "secret".toCharArray())
        val pfx = fixture("client.p12").copyTo(folder.resolve("client.pfx"))
        create(config(pfx, passphrase = true), "secret".toCharArray())
        val named = fixture("client.p12").copyTo(folder.resolve("client.bin"))
        create(config(named, passphrase = true, certificateFormat = CertificateFormat.P12), "secret".toCharArray())

        // A given format wins over the extension: this is a PEM file.
        val pemAsP12 = fixture("client.pem").copyTo(folder.resolve("really-pem.p12"))
        create(config(pemAsP12, fixture("client.key"), certificateFormat = CertificateFormat.PEM))
        // No extension, no format: PEM by its content.
        val noExtension = fixture("client.pem").copyTo(folder.resolve("certificate"))
        create(config(noExtension, fixture("client.key")))
    }

    @Test
    fun `a PKCS 12 file says why it cannot be used`() {
        val p12 = fixture("client.p12")

        val wrong = failure(config(p12, passphrase = true), "wrong".toCharArray())
        assertTrue(wrong.passphraseRejected)
        assertEquals(
            "client.p12 cannot be read as PKCS#12 (is the passphrase right?): ${wrong.cause?.message}",
            wrong.message,
        )

        val protected = failure(config(p12))
        assertFalse(protected.passphraseRejected)
        assertEquals(
            "client.p12 cannot be read as PKCS#12 (set hasCertificatePassphrase if it has one): ${protected.cause?.message}",
            protected.message,
        )

        assertEquals("No passphrase for client.p12 was given", failure(config(p12, passphrase = true)).message)

        val truncated = folder.resolve("truncated.p12").also { it.writeBytes(p12.readBytes().copyOf(200)) }
        val broken = failure(config(truncated, passphrase = true), "secret".toCharArray())
        assertEquals(
            "truncated.p12 cannot be read as PKCS#12 (is the passphrase right?): ${broken.cause?.message}",
            broken.message,
        )
    }

    @Test
    fun `a PKCS 12 file without a private key or with an unusable one is refused`() {
        val authority = certificate(fixture("ca.pem"))
        val noKey = folder.resolve("no-key.p12")
        KeyStore.getInstance("PKCS12").run {
            load(null, null)
            setCertificateEntry("ca", authority)
            noKey.outputStream().use { store(it, "pw".toCharArray()) }
        }
        assertEquals(
            "no-key.p12 contains no private key",
            failure(config(noKey, passphrase = true), "pw".toCharArray()).message,
        )

        // The key is protected by a password other than the file's, which is the one the key managers use.
        val source =
            KeyStore.getInstance("PKCS12").apply {
                fixture("client.p12").inputStream().use { load(it, "secret".toCharArray()) }
            }
        val alias = source.aliases().toList().first(source::isKeyEntry)
        val unusable = folder.resolve("unusable.p12")
        KeyStore.getInstance("PKCS12").run {
            load(null, null)
            setKeyEntry(
                "client",
                source.getKey(alias, "secret".toCharArray()),
                "other".toCharArray(),
                source.getCertificateChain(alias),
            )
            unusable.outputStream().use { store(it, "secret".toCharArray()) }
        }
        val error = failure(config(unusable, passphrase = true), "secret".toCharArray())
        assertEquals("The key of unusable.p12 cannot be used: ${error.cause?.message}", error.message)
    }

    @Test
    fun `a passphrase is zeroed once the key is decrypted, right or wrong`() {
        val right = "secret".toCharArray()
        create(config(fixture("client.pem"), fixture("client-encrypted.key")), right)
        assertContentEquals(CharArray(6), right)

        val wrong = "wrong1".toCharArray()
        failure(config(fixture("client.pem"), fixture("client-encrypted.key")), wrong)
        assertContentEquals(CharArray(6), wrong)
    }

    @Test
    fun `the unsupported encryption is named by file and algorithm`() {
        val message = failure(config(fixture("client.pem"), fixture("client-des3.key"))).message.orEmpty()

        assertTrue(
            message.startsWith("client-des3.key is encrypted in a way that is not supported or invalid ("),
            message,
        )
        assertTrue(message.endsWith("); $encryptionHint"), message)
    }

    private fun certificate(pem: Path): X509Certificate =
        java.security.cert.CertificateFactory
            .getInstance("X.509")
            .generateCertificate(derOf(pem).inputStream()) as X509Certificate

    /** A self-signed certificate and key of [algorithm] made by the JDK's keytool, as PEM files. */
    private fun generated(
        algorithm: String,
        vararg more: String,
    ): Pair<Path, Path> {
        val store = folder.resolve("$algorithm.p12")
        val keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString()
        val process =
            ProcessBuilder(
                keytool,
                "-genkeypair",
                "-alias",
                "a",
                "-keyalg",
                algorithm,
                *more,
                "-dname",
                "CN=test",
                "-validity",
                "30",
                "-storetype",
                "PKCS12",
                "-keystore",
                store.toString(),
                "-storepass",
                "changeit",
            ).redirectErrorStream(true).start()
        process.outputStream.close()
        process.inputStream.readBytes()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0, "keytool failed for $algorithm")
        val loaded =
            KeyStore
                .getInstance(
                    "PKCS12",
                ).apply { store.inputStream().use { load(it, "changeit".toCharArray()) } }
        val certificate =
            folder.resolve("$algorithm.pem").also {
                it.writeText(pem("CERTIFICATE", loaded.getCertificate("a").encoded))
            }
        val key =
            folder.resolve("$algorithm.key").also {
                it.writeText(pem("PRIVATE KEY", loaded.getKey("a", "changeit".toCharArray()).encoded))
            }
        return certificate to key
    }

    @Test
    fun `an RSA key never belongs to a certificate of another algorithm`() {
        val (certificate, _) = generated("EC")

        assertEquals(
            "The key client.key does not belong to the certificate EC.pem",
            failure(config(certificate, fixture("client.key"))).message,
        )
    }

    @Test
    fun `an RSA key that is not the certificate's is refused`() {
        assertEquals(
            "The key other.key does not belong to the certificate client.pem",
            failure(config(fixture("client.pem"), fixture("other.key"))).message,
        )
    }
}
