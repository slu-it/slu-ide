package dev.sluide.codecs.detect

import dev.sluide.codecs.codec.CodecId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class CodecDetectorTest {
    private companion object {
        const val JWT =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9." +
                "eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ." +
                "SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"
    }

    @ParameterizedTest
    @CsvSource(
        "'$JWT',JWT,true",
        "'Bearer $JWT',JWT,true",
        "'data:text/plain;base64,aGVsbG8=',BASE64,true",
        "'<~87cURD]i,\"Ebo80~>',ASCII85,true",
        "'48656c6c6f20776f726c64',HEX,false",
        "'48 65 6c 6c 6f 20 77 6f',HEX,false",
        "'0x48 0x65 0x6c 0x6c 0x6f',HEX,false",
        "'12:34:56:78',HEX,false",
        "'JBSWY3DPEBLW64TMMQ======',BASE32,false",
        "'aGVsbG8gd29ybGQ=',BASE64,false",
        "'aGVsbG8g\nd29ybGQ=',BASE64,false",
        "'0xdeadbeef',HEX,false",
        "'48656c6c6f20776f\n726c64210a',HEX,false",
        "'4865 6c6c 6f20 776f',HEX,false",
        "'48656c6c 6f20776f',HEX,false",
        "'JBSWY3DP',BASE32,false",
        "'aGVsbG8_d29ybGQ-',BASE64,false",
        "'iVBORw0KGgoAAAANSUhEUgAAAAEAAAAB',BASE64,false",
        "'a%20b%26c',URL,false",
        "'Fish &amp; Chips',HTML,false",
        "'caf&#233;',HTML,false",
        "'caf\\u00e9',UNICODE,false",
        "'\\u{1F600}',UNICODE,false",
        "'line1\\nline2',JSON,false",
        "'say \\\"hi\\\"',JSON,false",
    )
    fun `detects the codec`(
        text: String,
        codec: CodecId,
        strong: Boolean,
    ) {
        val detection = CodecDetector.detect(text)
        assertEquals(codec, detection?.codec, text)
        assertEquals(strong, detection?.strong, text)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "   ",
            "username",
            "12345678",
            "hello",
            "abc",
            "deadbee",
            "The quick brown fox",
            "Sale: 50% off today",
            "100%",
            "just a & b; fine",
            "2026-10-30",
            "555-123-4567",
            "4111 1111 1111 1111",
            "2026-10-30 12:00",
            "PASSWORD",
            "ABCDEFGH",
            "USERNAME",
            "ME======",
        ],
    )
    fun `plain text is not detected`(text: String) {
        assertNull(CodecDetector.detect(text), text)
    }

    @Test
    fun `descriptions complete the sentence Looks like`() {
        assertEquals("a JWT", CodecDetector.detect(JWT)?.description)
        assertEquals("hex", CodecDetector.detect("48656c6c6f20776f")?.description)
        assertEquals("Base64URL", CodecDetector.detect("aGVsbG8_d29ybGQ-")?.description)
    }
}
