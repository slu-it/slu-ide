package dev.sluide.converting.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.BigIntegerNode
import tools.jackson.databind.node.BooleanNode
import tools.jackson.databind.node.DecimalNode
import tools.jackson.databind.node.IntNode
import tools.jackson.databind.node.LongNode
import tools.jackson.databind.node.StringNode
import java.math.BigDecimal
import java.math.BigInteger

class DataValuesTest {
    @ParameterizedTest
    @CsvSource("0", "7", "-12", "2147483647")
    fun `small integers become int nodes`(text: String) {
        assertEquals(IntNode.valueOf(text.toInt()), inferScalar(text))
    }

    @Test
    fun `larger integers become long, then big integer nodes`() {
        assertEquals(LongNode.valueOf(2147483648), inferScalar("2147483648"))
        assertEquals(LongNode.valueOf(Long.MIN_VALUE), inferScalar(Long.MIN_VALUE.toString()))
        assertEquals(
            BigIntegerNode.valueOf(BigInteger("123456789012345678901234567890")),
            inferScalar("123456789012345678901234567890"),
        )
    }

    @ParameterizedTest
    @CsvSource("1.0", "-0.50", "0.000001", "3.14159265358979323846264338327950288")
    fun `decimals become exact decimal nodes`(text: String) {
        val node = inferScalar(text)

        assertEquals(DecimalNode.valueOf(BigDecimal(text)), node)
        assertEquals(text, node.decimalValue().toPlainString())
    }

    @Test
    fun `only lowercase true and false become booleans`() {
        assertEquals(BooleanNode.TRUE, inferScalar("true"))
        assertEquals(BooleanNode.FALSE, inferScalar("false"))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "", "0123", "00", "+1", "-0", "1e3", "1E-3", "1.", ".5", "1.5e3", "0x1F", "1_000", "True", "TRUE", "yes",
            "null", "~", " 1", "1 ", "12:30", "2024-01-01", "NaN", "Infinity", "abc", "-0.0", "-0.000",
        ],
    )
    fun `everything else stays text`(text: String) {
        assertEquals(StringNode.valueOf(text), inferScalar(text))
    }

    @Test
    fun `numbers of more than 1000 digits stay text`() {
        val digits = "1".repeat(1000)

        assertEquals(BigIntegerNode.valueOf(BigInteger(digits)), inferScalar(digits))
        assertEquals(StringNode.valueOf("${digits}2"), inferScalar("${digits}2"))
        assertEquals(StringNode.valueOf("0.$digits"), inferScalar("0.$digits"))
        assertEquals(DecimalNode.valueOf(BigDecimal("-0.${digits.drop(1)}")), inferScalar("-0.${digits.drop(1)}"))
    }

    @Test
    fun `sort keys sorts case-insensitively, then naturally, and keeps array order`() {
        val mapper = JsonMapper.builder().build()
        val node = mapper.readTree("""{"b":1,"B":2,"a":[{"y":1,"X":2},3,1],"C":{"z":1,"Z":2}}""")

        assertEquals(
            """{"a":[{"X":2,"y":1},3,1],"B":2,"b":1,"C":{"Z":2,"z":1}}""",
            mapper.writeValueAsString(sortKeys(node)),
        )
    }

    @Test
    fun `sort keys leaves the original tree unchanged`() {
        val mapper = JsonMapper.builder().build()
        val node = mapper.readTree("""{"b":1,"a":2}""")

        sortKeys(node)

        assertEquals("""{"b":1,"a":2}""", mapper.writeValueAsString(node))
    }
}
