package dev.sluide.httpclient.auth

import dev.sluide.httpclient.auth.DigestAuth.Challenge
import dev.sluide.httpclient.auth.DigestAuth.Credentials
import java.net.URI
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DigestAuthTest {
    // RFC 7616, section 3.9.1.
    private val nonce = "7ypf/xlj9XXwfDPEoM4URrv/xwf94BcCAzFZH4GiTo0v"
    private val opaque = "FQhe/qaU925kfnzjCev0ciny7QMkPqMAFRtzCUYo5tdS"
    private val cnonce = "f2/wE4q74E6zIJEtWaHKaf5wv/H5QzzpXusqGemxURZJ"
    private val credentials = Credentials("Mufasa", "Circle of Life")
    private val uri = URI("http://www.example.org/dir/index.html")
    private val digest = DigestAuth { cnonce }

    private fun challenge(algorithm: String?) =
        Challenge("http-auth@example.org", nonce, opaque, algorithm, listOf("auth", "auth-int"))

    private fun hash(
        algorithm: String,
        text: String,
    ) = HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(text.toByteArray()))

    @Test
    fun `answers the RFC 7616 example with MD5`() {
        assertEquals(
            "Digest username=\"Mufasa\", realm=\"http-auth@example.org\", uri=\"/dir/index.html\", algorithm=MD5, " +
                "nonce=\"$nonce\", nc=00000001, cnonce=\"$cnonce\", qop=auth, " +
                "response=\"8ca523f5e9506fed4657c9700eebdbec\", opaque=\"$opaque\"",
            digest.authorization(challenge("MD5"), credentials, "GET", uri),
        )
    }

    @Test
    fun `answers the RFC 7616 example with SHA-256`() {
        val authorization = digest.authorization(challenge("SHA-256"), credentials, "GET", uri)

        assertEquals(
            "Digest username=\"Mufasa\", realm=\"http-auth@example.org\", uri=\"/dir/index.html\", " +
                "algorithm=SHA-256, nonce=\"$nonce\", nc=00000001, cnonce=\"$cnonce\", qop=auth, " +
                "response=\"753927fa0e85d155564e2e272a28d1802ca10daf4496794697cf8db5856cb6c1\", opaque=\"$opaque\"",
            authorization,
        )
    }

    @Test
    fun `answers session variants`() {
        val authorization = digest.authorization(challenge("SHA-256-sess"), credentials, "POST", URI("http://x/a?b=1"))

        val ha1 = hash("SHA-256", hash("SHA-256", "Mufasa:http-auth@example.org:Circle of Life") + ":$nonce:$cnonce")
        val ha2 = hash("SHA-256", "POST:/a?b=1")
        val expected = hash("SHA-256", "$ha1:$nonce:00000001:$cnonce:auth:$ha2")
        assertEquals(expected, Regex("response=\"(\\w+)\"").find(authorization.orEmpty())?.groupValues?.get(1))
    }

    @Test
    fun `answers legacy challenges without qop`() {
        val legacy = Challenge("r", "n", null, null, emptyList())

        val authorization = digest.authorization(legacy, Credentials("u", "p"), "GET", URI("http://x"))

        val expected = hash("MD5", hash("MD5", "u:r:p") + ":n:" + hash("MD5", "GET:/"))
        assertEquals(
            "Digest username=\"u\", realm=\"r\", uri=\"/\", nonce=\"n\", response=\"$expected\"",
            authorization,
        )
    }

    @Test
    fun `does not answer unsupported challenges`() {
        assertNull(digest.authorization(challenge("SHA-512-256"), credentials, "GET", uri))
        assertNull(digest.authorization(Challenge("r", "n", null, null, listOf("auth-int")), credentials, "GET", uri))
    }

    @Test
    fun `refuses session algorithms without qop, which would need a client nonce`() {
        val challenge = Challenge("r", "n", null, "MD5-sess", emptyList())

        assertNull(digest.authorization(challenge, credentials, "GET", uri))
        assertEquals("MD5-sess needs a qop, but the server offers none", challenge.problem)
        assertNull(DigestAuth.challenge(listOf("Digest realm=\"r\", nonce=\"n\", algorithm=MD5-sess")))
    }

    @Test
    fun `reads credentials from a written header`() {
        assertEquals(Credentials("user", "pass word"), DigestAuth.credentials("Digest user pass word"))
        assertEquals(Credentials("user", "pass"), DigestAuth.credentials("digest user:pass"))
        assertNull(DigestAuth.credentials("Digest user"))
        assertNull(DigestAuth.credentials("Basic user pass"))
    }

    @Test
    fun `takes a complete Digest answer for no credentials`() {
        assertNull(DigestAuth.credentials("Digest username=\"x\", realm=\"r\", nonce=\"n\", response=\"abc\""))
    }

    @Test
    fun `picks the strongest usable challenge`() {
        val challenge =
            DigestAuth.challenge(
                listOf(
                    "Basic realm=\"b\"",
                    "Digest realm=\"r\", qop=\"auth,auth-int\", algorithm=MD5, nonce=\"n1\", opaque=\"o\"",
                    "Digest realm=\"r\", qop=\"auth, auth-int\", algorithm=SHA-256, nonce=\"n2\", opaque=\"o\"",
                    "Digest realm=\"r\", algorithm=SHA-512-256, nonce=\"n3\"",
                ),
            )

        assertEquals(Challenge("r", "n2", "o", "SHA-256", listOf("auth", "auth-int")), challenge)
    }

    @Test
    fun `reads challenges among other schemes in one header`() {
        assertEquals(
            Challenge("a \"quoted\" realm", "n", null, null, emptyList()),
            DigestAuth.challenge(listOf("Basic realm=\"b\", Digest realm=\"a \\\"quoted\\\" realm\", nonce=n, Bearer")),
        )
        assertNull(DigestAuth.challenge(listOf("Basic realm=\"b\"", "Digest realm=\"r\"")))
    }

    @Test
    fun `makes a fresh random client nonce of 16 bytes per answer unless one is given`() {
        // RFC 7616 3.4: the cnonce is chosen by the client; here 128 bits from SecureRandom.
        val random = DigestAuth()
        val cnonces =
            List(2) {
                val authorization = random.authorization(challenge("MD5"), credentials, "GET", uri).orEmpty()
                Regex("cnonce=\"(\\w*)\"")
                    .find(authorization)
                    ?.groupValues
                    ?.get(1)
                    .orEmpty()
            }

        assertTrue(cnonces.all { Regex("[0-9a-f]{32}").matches(it) }, cnonces.toString())
        assertNotEquals(cnonces[0], cnonces[1])
        assertNotEquals("0".repeat(32), cnonces[0])
    }

    @Test
    fun `names why an unsupported or insufficient challenge cannot be answered`() {
        // RFC 7616 3.3: only the MD5 and SHA-256 families are implemented here.
        assertEquals(
            "algorithm SHA-512-256 is not supported",
            Challenge("r", "n", null, "SHA-512-256", listOf("auth")).problem,
        )
        // RFC 7616 3.3: qop-options lists what the server accepts; without "auth" nothing can be answered.
        assertEquals(
            "only qop auth-int, other is offered, not auth",
            Challenge("r", "n", null, null, listOf("auth-int", "other")).problem,
        )
        assertNull(Challenge("r", "n", null, "sha-256", listOf("auth")).problem)
    }

    @Test
    fun `answers an empty or missing path with a slash and keeps the query`() {
        // RFC 7616 3.4: the digest-uri is the request target, "/" for an empty path (RFC 7230 5.3.1).
        fun uriOf(target: URI): String? {
            val authorization = digest.authorization(challenge("MD5"), credentials, "GET", target).orEmpty()
            return Regex("uri=\"([^\"]*)\"").find(authorization)?.groupValues?.get(1)
        }

        assertEquals("/?a=1", uriOf(URI("http://x?a=1")))
        assertEquals("/", uriOf(URI("urn:x:y")))
        assertEquals("/a%20b", uriOf(URI("http://x/a%20b")))
    }

    @Test
    fun `leaves out challenges without a realm or without a nonce`() {
        // RFC 7616 3.3: realm and nonce are required parameters of a challenge.
        assertEquals(emptyList(), DigestAuth.challenges(listOf("Digest nonce=\"n\"")))
        assertEquals(emptyList(), DigestAuth.challenges(listOf("Digest realm=\"r\"")))
    }

    @Test
    fun `prefers the first of equally strong challenges`() {
        fun nonceOf(vararg algorithms: String) =
            DigestAuth
                .challenge(algorithms.mapIndexed { i, a -> "Digest realm=\"r\", algorithm=$a, nonce=\"n$i\"" })
                ?.nonce

        assertEquals("n0", nonceOf("SHA-256", "SHA-256"))
        assertEquals("n0", nonceOf("MD5", "MD5"))
        assertEquals("n1", nonceOf("MD5", "SHA-256"))
        assertEquals("n0", nonceOf("SHA-256", "MD5"))
    }

    @Test
    fun `reads hostile challenge parameters without failing`() {
        // RFC 7230 3.2.6: a quoted-string may hold commas, escaped quotes and backslashes.
        assertEquals(
            Challenge("a, b=c \\ d", "n", null, null, emptyList()),
            DigestAuth.challenge(listOf("Digest realm=\"a, b=c \\\\ d\", nonce=\"n\"")),
        )
        // Trailing separators, a missing value, an unterminated quote and a trailing backslash end the text early.
        assertEquals(
            Challenge("r", "n", null, null, emptyList()),
            DigestAuth.challenge(listOf("Digest realm=\"r\", nonce=\"n\", , ")),
        )
        assertEquals(mapOf("realm" to ""), DigestAuth.parameters("realm=", 0))
        assertEquals(mapOf("realm" to "ab"), DigestAuth.parameters("realm=\"ab", 0))
        assertEquals(mapOf("realm" to "ab\\"), DigestAuth.parameters("realm=\"ab\\", 0))
        // A header that starts with something else than a scheme, and a last token without parameters.
        assertEquals(
            Challenge("r", "n", null, null, emptyList()),
            DigestAuth.challenge(listOf("=x, Digest realm=\"r\", nonce=\"n\", Bearer")),
        )
    }

    @Test
    fun `stops the parameters at the next scheme`() {
        val challenges = DigestAuth.challenges(listOf("Digest realm=r, nonce=n, Bearer realm=\"other\", opaque=x"))

        assertEquals(listOf(Challenge("r", "n", null, null, emptyList())), challenges)
    }
}
