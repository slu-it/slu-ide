package dev.sluide.codecs.conversion

import dev.sluide.codecs.codec.Decoded
import dev.sluide.codecs.codec.Jwt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

class JwtViewTest {
    private val sample =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9." +
            "eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ." +
            "SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"
    private val now = Instant.parse("2026-10-30T12:00:00Z")

    private fun clock(at: Instant) = Clock.fixed(at, ZoneOffset.ofHours(1))

    private fun part(json: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())

    private fun view(
        token: String,
        at: Instant = now,
    ) = jwtView((Jwt.decode(token) as Decoded.Value).value, clock(at))

    private fun token(
        claims: String,
        header: String = "{\"alg\":\"HS256\"}",
        signature: String = "abcd",
    ) = "${part(header)}.${part(claims)}.$signature"

    @Test
    fun `the jwt io sample has the exact summary`() {
        val view = view(sample, Instant.parse("2018-01-20T01:30:22Z"))
        assertEquals(
            """
            Header
            {
              "alg": "HS256",
              "typ": "JWT"
            }

            Payload
            {
              "sub": "1234567890",
              "name": "John Doe",
              "iat": 1516239022
            }

            iat  2018-01-18T01:30:22Z · 2018-01-18 02:30:22 local · 2 days ago

            Signature (HS256): not verified
            """.trimIndent(),
            view.summary,
        )
        assertFalse(view.expired)
    }

    @Test
    fun `exp in the past is expired, iat and nbf follow in the order exp iat nbf`() {
        val exp = now.minusSeconds(3 * 3600).epochSecond
        val iat = now.minusSeconds(2 * 86_400).epochSecond
        val nbf = now.plusSeconds(5 * 60).epochSecond
        val view = view(token("{\"nbf\":$nbf,\"iat\":$iat,\"exp\":$exp}"))
        val lines =
            view.summary.lines().filter {
                it.startsWith("exp ") || it.startsWith("iat ") ||
                    it.startsWith("nbf ")
            }
        assertEquals(
            listOf(
                "exp  2026-10-30T09:00:00Z · 2026-10-30 10:00:00 local · expired 3 h ago",
                "iat  2026-10-28T12:00:00Z · 2026-10-28 13:00:00 local · 2 days ago",
                "nbf  2026-10-30T12:05:00Z · 2026-10-30 13:05:00 local · in 5 min",
            ),
            lines,
        )
        assertTrue(view.expired)
    }

    @Test
    fun `exp in the future is not expired and reads in X`() {
        val view = view(token("{\"exp\":${now.plusSeconds(90 * 60).epochSecond}}"))
        assertTrue(view.summary.contains("· in 1 h\n"))
        assertFalse(view.expired)
    }

    @ParameterizedTest
    @CsvSource(
        "-30,30 s ago",
        "45,in 45 s",
        "-59,59 s ago",
        "-60,1 min ago",
        "-3599,59 min ago",
        "-3600,1 h ago",
        "86399,in 23 h",
        "86400,in 1 day",
        "-172800,2 days ago",
    )
    fun `the relative time uses the largest unit`(
        offset: Long,
        expected: String,
    ) {
        assertEquals(expected, relativeTime(now.plusSeconds(offset), now))
    }

    @Test
    fun `an unsigned token says so`() {
        val none = view(token("{}", header = "{\"alg\":\"none\"}", signature = ""))
        assertTrue(none.summary.endsWith("Not signed (alg: none)"))
        val noAlg = view(token("{}", header = "{}", signature = ""))
        assertTrue(noAlg.summary.endsWith("Not signed"))
        assertFalse(noAlg.summary.contains("alg: none"))
    }

    @Test
    fun `a token without time claims has no claims block`() {
        assertEquals(
            "Header\n{\n  \"alg\": \"HS256\"\n}\n\nPayload\n{ }\n\nSignature (HS256): not verified",
            view(token("{}")).summary,
        )
    }
}
