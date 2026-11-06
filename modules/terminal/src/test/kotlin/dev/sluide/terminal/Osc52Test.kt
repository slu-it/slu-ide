package dev.sluide.terminal

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNull

class Osc52Test {
    @ParameterizedTest
    @CsvSource(
        "c, hello",
        "'', hello",
        "p, hello",
        "s0, hello",
        "c, Grüße ✓",
    )
    fun `the payload is decoded as UTF-8 for any targets`(
        targets: String,
        text: String,
    ) {
        assertEquals(text, clipboardText("$targets;${base64(text)}"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["\n", "\r\n"])
    fun `line breaks in the payload, as GNU base64 writes them, are skipped`(lineBreak: String) {
        val text = "a line long enough for base64 to break it after 76 characters, ".repeat(3)
        val broken = base64(text).chunked(76).joinToString(lineBreak)

        assertEquals(text, clipboardText("c;$broken"))
    }

    @ParameterizedTest
    @MethodSource("nothingToCopy")
    fun `queries and broken or oversized payloads copy nothing`(arguments: String) {
        assertNull(clipboardText(arguments))
    }

    companion object {
        private fun base64(text: String) = Base64.getEncoder().encodeToString(text.toByteArray())

        @JvmStatic
        fun nothingToCopy() =
            listOf(
                "?",
                "c;?",
                base64("no separator"),
                "c;not base64!",
                "c;",
                "c;" + "A".repeat(OSC_52_MAX_PAYLOAD + 4),
            )
    }
}
