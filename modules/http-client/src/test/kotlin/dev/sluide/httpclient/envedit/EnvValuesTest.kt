package dev.sluide.httpclient.envedit

import tools.jackson.databind.JsonNode
import kotlin.test.Test
import kotlin.test.assertEquals

class EnvValuesTest {
    private fun convert(
        json: String,
        type: ValueType,
    ): Pair<String, Boolean> {
        val conversion = EnvValues.convert(EnvJson.parse(json) as JsonNode, type)
        return EnvJson.print(conversion.value).trimEnd() to conversion.lossy
    }

    @Test
    fun `converts what can be converted without loss`() {
        assertEquals("30" to false, convert("\"30\"", ValueType.NUMBER))
        assertEquals("1e3" to false, convert("\" 1e3 \"", ValueType.NUMBER))
        assertEquals("\"1.50\"" to false, convert("1.50", ValueType.TEXT))
        assertEquals("true" to false, convert("\"True\"", ValueType.YES_NO))
        assertEquals("\"false\"" to false, convert("false", ValueType.TEXT))
        assertEquals("\"x\"" to false, convert("\"x\"", ValueType.TEXT))
    }

    @Test
    fun `replaces what cannot be converted by the empty value, lossy unless it was empty`() {
        assertEquals("false" to true, convert("1", ValueType.YES_NO))
        assertEquals("0" to true, convert("\"abc\"", ValueType.NUMBER))
        assertEquals("0" to true, convert("true", ValueType.NUMBER))
        assertEquals("null" to true, convert("\"x\"", ValueType.NULL))
        assertEquals("\"\"" to true, convert("""{"a": 1}""", ValueType.TEXT))
        assertEquals("[]" to true, convert("1", ValueType.LIST))
        assertEquals("0" to false, convert("\"\"", ValueType.NUMBER))
        assertEquals("{}" to false, convert("null", ValueType.OBJECT))
        assertEquals("{}" to false, convert("[]", ValueType.OBJECT))
        assertEquals("\"\"" to false, convert("{}", ValueType.TEXT))
    }
}
