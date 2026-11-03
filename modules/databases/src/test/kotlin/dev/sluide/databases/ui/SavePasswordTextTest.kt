package dev.sluide.databases.ui

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals

class SavePasswordTextTest {
    @Test
    fun `the label names the reason only when there is one`() {
        assertEquals("Save password", savePasswordLabel(null))
        assertEquals("Save password (no key store)", savePasswordLabel("no key store"))
    }

    @ParameterizedTest
    @CsvSource("true,Saved; type to replace", "false,Asked on connect when empty")
    fun `the field's prompt text follows the saved state`(
        saved: Boolean,
        expected: String,
    ) {
        assertEquals(expected, passwordPromptText(saved))
    }
}
