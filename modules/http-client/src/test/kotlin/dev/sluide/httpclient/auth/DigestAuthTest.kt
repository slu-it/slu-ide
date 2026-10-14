package dev.sluide.httpclient.auth

import dev.sluide.httpclient.auth.DigestAuth.Challenge
import dev.sluide.httpclient.auth.DigestAuth.Credentials
import java.net.URI
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}
