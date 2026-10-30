package dev.sluide.codecs.codec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import java.util.Base64

class JwtTest {
    private val sample =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9." +
            "eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ." +
            "SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"

    private fun decoded(token: String): DecodedJwt = (Jwt.decode(token) as Decoded.Value).value

    private fun part(json: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())

    @Test
    fun `decodes the jwt io sample`() {
        val jwt = decoded(sample)
        assertEquals("HS256", jwt.algorithm)
        assertEquals("{\n  \"alg\": \"HS256\",\n  \"typ\": \"JWT\"\n}", jwt.header)
        assertEquals(
            "{\n  \"sub\": \"1234567890\",\n  \"name\": \"John Doe\",\n  \"iat\": 1516239022\n}",
            jwt.payload,
        )
        assertEquals(listOf(JwtTime("iat", Instant.parse("2018-01-18T01:30:22Z"))), jwt.times)
        assertTrue(jwt.signed)
    }

    @Test
    fun `a Bearer prefix in any case and surrounding whitespace are ignored`() {
        assertEquals(decoded(sample), decoded("  bEaReR  $sample \n"))
    }

    @Test
    fun `alg none with an empty signature is not signed`() {
        val jwt = decoded("${part("{\"alg\":\"none\"}")}.${part("{\"exp\":1.5,\"nbf\":2,\"iat\":3}")}.")
        assertEquals("none", jwt.algorithm)
        assertFalse(jwt.signed)
        assertEquals(
            listOf(
                JwtTime("exp", Instant.ofEpochMilli(1500)),
                JwtTime("iat", Instant.ofEpochSecond(3)),
                JwtTime("nbf", Instant.ofEpochSecond(2)),
            ),
            jwt.times,
        )
    }

    @Test
    fun `a payload that is no JSON is shown as text`() {
        val jwt = decoded("${part("{}")}.${part("hello")}.abcd")
        assertEquals("hello", jwt.payload)
        assertNull(jwt.algorithm)
        assertTrue(jwt.times.isEmpty())
    }

    @Test
    fun `padded parts are accepted`() {
        val padded = Base64.getUrlEncoder().encodeToString("{\"a\":1}".toByteArray())
        assertEquals("{\n  \"a\": 1\n}", decoded("$padded.$padded.").header)
    }

    @Test
    fun `five parts are an encrypted JWT`() {
        assertEquals(Decoded.Error("Encrypted JWT (JWE), cannot be decoded"), Jwt.decode("a.b.c.d.e"))
    }

    @Test
    fun `two parts are an error`() {
        assertEquals(Decoded.Error("A JWT has three parts separated by dots"), Jwt.decode("a.b"))
    }

    @Test
    fun `an invalid Base64url character is reported at its position in the input`() {
        val token = "Bearer ${part("{}")}.ab+d.sig"
        val error = Jwt.decode(token) as Decoded.Error
        assertEquals(token.indexOf('+'), error.position)
    }

    @Test
    fun `a header that is no JSON object is an error`() {
        assertEquals(
            Decoded.Error("The header is not a JSON object", 0),
            Jwt.decode("${part("[1]")}.${part("{}")}."),
        )
        assertEquals(
            Decoded.Error("The header is not a JSON object", 7),
            Jwt.decode("Bearer ${part("nope")}.${part("{}")}."),
        )
    }

    @Test
    fun `a payload with invalid UTF-8 is an error at the start of the payload`() {
        val bad = Base64.getUrlEncoder().withoutPadding().encodeToString(byteArrayOf(0xC3.toByte(), 0x28))
        val header = part("{}")
        assertEquals(
            Decoded.Error("The payload is not valid UTF-8", header.length + 1),
            Jwt.decode("$header.$bad."),
        )
    }

    @Test
    fun `an invalid character in the signature is reported at its position in the input`() {
        val prefix = "${part("{}")}.${part("{}")}."
        assertEquals(
            Decoded.Error("Invalid Base64url character '+' in the signature", prefix.length + 2),
            Jwt.decode("${prefix}ab+d"),
        )
    }

    @Timeout(2)
    @ParameterizedTest
    @ValueSource(strings = ["1e400", "-1e400", "1e999999999", "99999999999999999999", "1e-200000000"])
    fun `a time claim that is beyond the time range is skipped`(number: String) {
        val jwt = decoded("${part("{}")}.${part("{\"exp\":$number,\"iat\":5}")}.")
        assertEquals(listOf(JwtTime("iat", Instant.ofEpochSecond(5))), jwt.times)
    }

    @Test
    fun `numbers are printed with the digits they were written with`() {
        val payload =
            decoded(
                "${part("{}")}.${part("{\"a\":1.10,\"b\":0.1000000000000000055511151231257827}")}.",
            ).payload
        assertEquals("{\n  \"a\": 1.10,\n  \"b\": 0.1000000000000000055511151231257827\n}", payload)
    }
}
