package dev.sluide.fileviewers

import dev.sluide.fileviewers.PemRenderer.Badge
import java.security.AlgorithmParameters
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.EncryptedPrivateKeyInfo
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.PBEParameterSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The details of PEM files, from the test-only fixtures in `src/test/resources/…/pem` (see its README). */
class PemRendererTest {
    private fun fixture(name: String): String =
        checkNotNull(PemRendererTest::class.java.getResource("pem/$name")) { "missing $name" }.readText()

    private val now = Instant.parse("2026-10-15T12:00:00Z")

    private fun describe(
        name: String,
        at: Instant = now,
    ) = PemRenderer.describe(fixture(name), at)

    private fun PemRenderer.Details.field(name: String): String? = fields.firstOrNull { it.name == name }?.value

    /** Generated here, so that no usable private key is checked in. */
    private val key =
        KeyPairGenerator
            .getInstance("EC")
            .apply { initialize(256) }
            .generateKeyPair()
            .private

    private val unencryptedKey = pem("PRIVATE KEY", key.encoded)

    /** PKCS #8 encrypted with PBES2 (HMAC-SHA256, AES-256), as `openssl pkcs8 -topk8` does. */
    private val encryptedKey: String
        get() {
            val algorithm = "PBEWithHmacSHA256AndAES_256"
            val random = SecureRandom()
            val salt = ByteArray(16).also(random::nextBytes)
            val iv = ByteArray(16).also(random::nextBytes)
            val secret = SecretKeyFactory.getInstance(algorithm).generateSecret(PBEKeySpec("test".toCharArray()))
            val cipher = Cipher.getInstance(algorithm)
            cipher.init(Cipher.ENCRYPT_MODE, secret, PBEParameterSpec(salt, 1_000, IvParameterSpec(iv)))
            // Only the PBES2 name has an OID of its own; the parameters say which PRF and cipher.
            val pbes2 = AlgorithmParameters.getInstance("PBES2").apply { init(cipher.parameters.encoded) }
            return pem("ENCRYPTED PRIVATE KEY", EncryptedPrivateKeyInfo(pbes2, cipher.doFinal(key.encoded)).encoded)
        }

    private fun pem(
        label: String,
        der: ByteArray,
    ): String {
        val base64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der)
        return "-----BEGIN $label-----\n$base64\n-----END $label-----\n"
    }

    @Test
    fun `a self-signed certificate with its details`() {
        val certificate = describe("test-only-self-signed.crt").single()

        assertEquals("Certificate: sluide.test", certificate.title)
        assertEquals(Badge("Valid", "valid"), certificate.badge)
        assertNull(certificate.error)
        assertEquals("O=SluIde Test Fixtures,CN=sluide.test", certificate.field("Subject"))
        assertEquals("O=SluIde Test Fixtures,CN=sluide.test (self-signed)", certificate.field("Issuer"))
        assertEquals(
            "13:7F:16:91:A5:C3:29:A6:5C:AD:BF:26:36:F2:8E:C0:B8:B2:B0:2C",
            certificate.field("Serial number"),
        )
        assertEquals("2026-01-01 00:00:00 UTC", certificate.field("Valid from"))
        assertEquals("2036-01-01 00:00:00 UTC", certificate.field("Valid until"))
        assertEquals("RSA, 2048 bits", certificate.field("Public key"))
        assertEquals("SHA256withRSA", certificate.field("Signature algorithm"))
        assertEquals("DNS: sluide.test, DNS: www.sluide.test, IP: 127.0.0.1", certificate.field("Alternative names"))
        assertEquals("Digital signature, Key encipherment", certificate.field("Key usage"))
        assertEquals("TLS server", certificate.field("Extended key usage"))
        assertEquals("No", certificate.field("Certificate authority"))
        assertEquals(
            "00:A5:C5:4C:CD:17:93:7E:AF:4B:DD:2D:59:A6:FC:5B:15:5D:78:A5:88:82:52:74:FD:35:D8:6A:06:95:1F:5A",
            certificate.field("SHA-256 fingerprint"),
        )
    }

    @Test
    fun `validity badges`() {
        assertEquals(Badge("Expired", "expired"), describe("test-only-expired.pem").single().badge)
        val expiring = Instant.parse("2035-12-14T12:00:00Z")
        assertEquals(
            Badge("Expires in 17 days", "expiring"),
            describe("test-only-self-signed.crt", expiring).single().badge,
        )
        val early = Instant.parse("2025-06-01T00:00:00Z")
        assertEquals(Badge("Not yet valid", "expired"), describe("test-only-self-signed.crt", early).single().badge)
        assertEquals("EC, 256 bits", describe("test-only-expired.pem").single().field("Public key"))
    }

    @Test
    fun `each certificate of a chain`() {
        val (leaf, ca) = describe("test-only-chain.pem")

        assertEquals("Certificate 1 of 2: leaf.sluide.test", leaf.title)
        assertEquals("CN=SluIde Test CA", leaf.field("Issuer"))
        assertEquals("12:34", leaf.field("Serial number"))
        assertEquals("DNS: leaf.sluide.test", leaf.field("Alternative names"))
        assertEquals(Badge("Valid", "valid"), leaf.badge)
        assertEquals("Certificate 2 of 2: SluIde Test CA", ca.title)
        assertEquals("Yes, path length 0", ca.field("Certificate authority"))
        assertEquals("Certificate signing, CRL signing", ca.field("Key usage"))
        assertEquals("CN=SluIde Test CA (self-signed)", ca.field("Issuer"))
    }

    @Test
    fun `keys show their type and whether they are encrypted, nothing of the key`() {
        val plain = PemRenderer.describe(unencryptedKey, now).single()
        assertEquals(
            listOf("Type" to "Private key (PKCS #8)", "Algorithm" to "EC, 256 bits", "Encrypted" to "No"),
            plain.fields.map { it.name to it.value },
        )
        val encrypted = PemRenderer.describe(encryptedKey, now).single()
        assertEquals("Private key (PKCS #8)", encrypted.field("Type"))
        assertTrue(encrypted.field("Encrypted").orEmpty().startsWith("Yes ("), encrypted.field("Encrypted"))
        val traditional = describe("test-only-traditional-encrypted.key").single()
        assertEquals("EC private key (OpenSSL)", traditional.field("Type"))
        assertEquals("Yes (AES-128-CBC)", traditional.field("Encrypted"))
        assertEquals("EC, 256 bits", describe("test-only-public.pem").single().field("Algorithm"))

        for (key in listOf(unencryptedKey, encryptedKey, fixture("test-only-traditional-encrypted.key"))) {
            val page = PemRenderer.render(key, dark = false, now)
            val material = key.lines().filter { it.length > 20 && ':' !in it && !it.startsWith("-----") }
            assertTrue(material.isNotEmpty())
            assertTrue(material.none { it in page }, "no key material in the page")
        }
    }

    @Test
    fun `alternative names without a string value show only their type`() {
        assertEquals("DNS: a.test", PemRenderer.alternativeName(listOf(2, "a.test")))
        assertEquals("Other name (not shown)", PemRenderer.alternativeName(listOf(0, byteArrayOf(1, 2))))
        assertEquals("X.400 address (not shown)", PemRenderer.alternativeName(listOf(3, byteArrayOf(1))))
        assertEquals("EDI party name (not shown)", PemRenderer.alternativeName(listOf(5, byteArrayOf(1))))
    }

    @Test
    fun `many BEGIN lines without an END are found in linear time`() {
        val chain = fixture("test-only-chain.pem")
        val text = "-----BEGIN X509 CRL-----\n".repeat(20_000) + chain + "-----BEGIN PRIVATE KEY-----\nAAAA\n"

        val started = System.nanoTime()
        val page = PemRenderer.render(text, dark = false, now)
        val millis = (System.nanoTime() - started) / 1_000_000

        assertTrue("Certificate 1 of 2: leaf.sluide.test" in page && "Certificate 2 of 2: SluIde Test CA" in page)
        assertTrue("20001 blocks have no matching END line." in page, "the 20,000 above and the key below")
        assertTrue(millis < 2_000, "took $millis ms")
    }

    @Test
    fun `a certificate request shows its subject and key`() {
        val request = describe("test-only-request.csr").single()

        assertEquals("Certificate request: leaf.sluide.test", request.title)
        assertEquals("CN=leaf.sluide.test", request.field("Subject"))
        assertEquals("EC, 256 bits", request.field("Public key"))
    }

    @Test
    fun `blocks that cannot be read show their label and why`() {
        val text =
            """
            |-----BEGIN CERTIFICATE-----
            |not base64!
            |-----END CERTIFICATE-----
            |-----BEGIN CERTIFICATE-----
            |AAAA
            |-----END CERTIFICATE-----
            |-----BEGIN X509 CRL-----
            |AAAA
            |-----END X509 CRL-----
            """.trimMargin()

        val (garbled, truncated, crl) = PemRenderer.describe(text, now)

        assertEquals("CERTIFICATE", garbled.title)
        assertNotNull(garbled.error)
        assertNotNull(truncated.error)
        assertEquals("X509 CRL", crl.title)
        assertNull(crl.error)
    }

    @Test
    fun `the page escapes all text, follows the theme and notes what it cannot show`() {
        assertTrue("No PEM blocks found." in PemRenderer.render("", dark = false, now))
        val markup = fixture("test-only-markup-subject.pem")
        val page = PemRenderer.render("$markup-----BEGIN CERTIFICATE-----\nAAAA\n", dark = true, now)
        assertFalse("<script>esc" in page)
        assertTrue("<h2>Certificate: &lt;script&gt;esc&amp;ape <span" in page, page)
        assertTrue("1 block has no matching END line." in page)
        assertTrue("<body class=\"dark pem\">" in page)

        val certificate = PemRenderer.render(fixture("test-only-chain.pem"), dark = false, now)
        assertTrue("<body class=\"pem\">" in certificate)
        assertTrue("<span class=\"pem-badge valid\">Valid</span>" in certificate)
        assertTrue("<th>Subject</th><td>CN=leaf.sluide.test</td>" in certificate)
    }

    @Test
    fun `headers and markers are highlighted, base64 is not`() {
        val pem = Highlighted(fixture("test-only-traditional-encrypted.key"), RSyntaxHighlighter.pem)

        assertEquals(setOf(TokenStyle.KEYWORD), pem.stylesOf("-----BEGIN EC PRIVATE KEY-----"))
        assertEquals(setOf(TokenStyle.KEYWORD), pem.stylesOf("-----END EC PRIVATE KEY-----"))
        assertEquals(setOf(TokenStyle.PROPERTY), pem.stylesOf("Proc-Type:"))
        assertEquals(setOf(TokenStyle.PROPERTY), pem.stylesOf("DEK-Info:"))
        assertEquals(emptySet(), pem.stylesOf("4,ENCRYPTED"))
        val base64 = fixture("test-only-traditional-encrypted.key").lines()[4]
        assertEquals(emptySet(), pem.stylesOf(base64))
    }
}
