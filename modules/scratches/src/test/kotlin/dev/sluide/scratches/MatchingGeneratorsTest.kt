package dev.sluide.scratches

import kotlin.test.Test
import kotlin.test.assertEquals

class MatchingGeneratorsTest {
    private val generators =
        listOf(
            ScratchGenerator(GeneratorGroup.UUIDS, "UUID v7", "uuid-v7") { "" },
            ScratchGenerator(GeneratorGroup.IDS, "ULID", "ulid") { "" },
            ScratchGenerator(GeneratorGroup.SECRETS, "Password 20", "password-20-alphanumeric") { "" },
            ScratchGenerator(GeneratorGroup.TIME, "Current Time", "time", "log") { "" },
        )

    private fun names(text: String?) = matchingGenerators(generators, text).map { it.name }

    @Test
    fun `empty or blank text matches every generator`() {
        val all = generators.map { it.name }
        assertEquals(all, names(""))
        assertEquals(all, names(null))
        assertEquals(all, names("  "))
    }

    @Test
    fun `the name matches ignoring case and surrounding blanks`() {
        assertEquals(listOf("ULID"), names(" uLi "))
        assertEquals(listOf("Current Time"), names("CURRENT"))
    }

    @Test
    fun `the group title matches`() {
        assertEquals(listOf("Password 20"), names("secrets"))
        assertEquals(listOf("UUID v7", "ULID"), names("IDs"))
    }

    @Test
    fun `the file name matches, extension included`() {
        assertEquals(listOf("Password 20"), names("alphanumeric"))
        assertEquals(listOf("Current Time"), names("time.log"))
        assertEquals(listOf("UUID v7"), names("v7.txt"))
    }

    @Test
    fun `no match gives nothing`() {
        assertEquals(emptyList(), names("zzz"))
    }
}
