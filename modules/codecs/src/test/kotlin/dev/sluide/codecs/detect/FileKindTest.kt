package dev.sluide.codecs.detect

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class FileKindTest {
    private fun bytes(hex: String) =
        java.util.HexFormat
            .of()
            .parseHex(hex)

    @ParameterizedTest
    @CsvSource(
        "89504e470d0a1a0a0000,PNG",
        "ffd8ffe000104a46,JPEG",
        "474946383961,GIF",
        "474946383761,GIF",
        "52494646240000005745425056503820,WEBP",
        "255044462d312e34,PDF",
        "504b0304140000,ZIP",
        "1f8b0800000000,GZIP",
        "789c4b4c4a0600,ZLIB",
        "7801000000,ZLIB",
        "785e00,ZLIB",
        "78da00,ZLIB",
        "cafebabe00000041,CLASS",
    )
    fun `recognises the magic bytes`(
        hex: String,
        kind: FileKind,
    ) {
        assertEquals(kind, FileKind.of(bytes(hex)))
    }

    @ParameterizedTest
    @CsvSource("''", "89", "52494646240000004156495020", "7800", "474946", "68656c6c6f")
    fun `anything else is unknown`(hex: String) {
        assertNull(FileKind.of(bytes(hex)))
    }

    @Test
    fun `titles and mime types are as the data URI needs them`() {
        assertEquals("PNG image", FileKind.PNG.title)
        assertEquals("image/png", FileKind.PNG.mime)
        assertEquals("zlib data", FileKind.ZLIB.title)
        assertEquals("application/java-vm", FileKind.CLASS.mime)
    }
}
