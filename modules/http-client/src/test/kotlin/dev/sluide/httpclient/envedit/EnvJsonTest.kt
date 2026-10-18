package dev.sluide.httpclient.envedit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class EnvJsonTest {
    @Test
    fun `reads numbers with their spelling`() {
        assertEquals("1.50", EnvJson.number("1.50")?.let(EnvJson::numberText))
        assertEquals("1e3", EnvJson.number(" 1e3 ")?.let(EnvJson::numberText))
        assertEquals("-7", EnvJson.number("-7")?.let(EnvJson::numberText))
        assertEquals("12345678901234567890", EnvJson.number("12345678901234567890")?.let(EnvJson::numberText))
    }

    @Test
    fun `reads nothing else as a number`() {
        assertNull(EnvJson.number("abc"))
        assertNull(EnvJson.number(""))
        assertNull(EnvJson.number("1 2"))
        assertNull(EnvJson.number("\"1\""))
        assertNull(EnvJson.number("1e9999999999"))
    }

    @Test
    fun `refuses numbers out of range`() {
        val error = assertFailsWith<EnvJsonException> { EnvJson.parse("""{"a": [1e9999999999]}""") }

        assertEquals("Number out of range: 1e9999999999", error.message)
    }
}
