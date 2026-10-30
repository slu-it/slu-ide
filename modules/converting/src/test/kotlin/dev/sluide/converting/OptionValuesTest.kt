package dev.sluide.converting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OptionValuesTest {
    private val compact = ConversionOption.Toggle("compact", "Compact", false)
    private val indent = ConversionOption.Choice("indent", "Indent", listOf("2", "4"), "2")

    @Test
    fun `missing values are the defaults`() {
        assertEquals(false, OptionValues().flag(compact))
        assertEquals("2", OptionValues().choice(indent))
    }

    @Test
    fun `set values are returned by id`() {
        val values = OptionValues().with("compact", true).with("indent", "4")

        assertEquals(true, values.flag(compact))
        assertEquals("4", values.choice(indent))
    }

    @Test
    fun `a value of the wrong type or outside the choices is the default`() {
        val values = OptionValues(mapOf("compact" to "yes", "indent" to "3"))

        assertEquals(false, values.flag(compact))
        assertEquals("2", values.choice(indent))
    }

    @Test
    fun `with leaves the original values unchanged`() {
        val original = OptionValues()

        original.with("compact", true)

        assertEquals(OptionValues(), original)
    }
}
