package dev.sluide.scratches

import kotlin.test.Test
import kotlin.test.assertEquals

class MatchingTypesTest {
    private fun names(text: String?) = matchingTypes(SCRATCH_TYPES, text).map { it.name }

    @Test
    fun `empty text matches every type`() {
        assertEquals(SCRATCH_TYPES.map { it.name }, names(""))
        assertEquals(SCRATCH_TYPES.map { it.name }, names(null))
        assertEquals(SCRATCH_TYPES.map { it.name }, names("  "))
    }

    @Test
    fun `a leading dot and surrounding blanks are ignored`() {
        assertEquals(listOf("Kotlin", "Kotlin Script"), names(" .kt"))
        assertEquals(listOf("Bicep"), names(".bicep"))
    }

    @Test
    fun `name or extension match ignoring case`() {
        assertEquals(listOf("Kotlin", "Kotlin Script"), names("KOT"))
        assertEquals(listOf("Markdown", "Mermaid"), names("md"))
        assertEquals(
            listOf("Markdown", "Mermaid", "Markdown (encrypted)"),
            matchingTypes(SCRATCH_TYPES + ENCRYPTED_SCRATCH_TYPES, "md").map { it.name },
        )
    }

    @Test
    fun `enc or sluenc finds the four encrypted types`() {
        val encrypted =
            listOf("Plain Text (encrypted)", "Markdown (encrypted)", "JSON (encrypted)", "YAML (encrypted)")
        val all = SCRATCH_TYPES + ENCRYPTED_SCRATCH_TYPES
        assertEquals(encrypted, matchingTypes(all, "enc").map { it.name })
        assertEquals(encrypted, matchingTypes(all, "sluenc").map { it.name })
    }

    @Test
    fun `no match gives nothing`() {
        assertEquals(emptyList(), names("zzz"))
    }
}
