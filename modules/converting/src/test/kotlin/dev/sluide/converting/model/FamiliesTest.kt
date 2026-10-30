package dev.sluide.converting.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class FamiliesTest {
    private val data =
        FakeFamily("Data", listOf(FakeFormat("json", listOf("json", "jsonc")), FakeFormat("ini"), FakeFormat("toml")))
    private val single = FakeFamily("Single", listOf(FakeFormat("only")))
    private val families = Families(listOf(data, single))

    @ParameterizedTest
    @CsvSource(
        "json, '', ini",
        "toml, '', json",
        "ini, '', json",
        "ini, toml json, json",
        "ini, toml ini only, toml",
        "only, json, only",
    )
    fun `suggests the last other target, else the default partner, else the first other format`(
        source: String,
        history: String,
        expected: String,
    ) {
        val formats = families.formats.associateBy { it.id }
        val used = history.split(' ').filter { it.isNotEmpty() }.map { formats.getValue(it) }

        assertEquals(expected, families.suggestion(formats.getValue(source), used).id)
    }

    @Test
    fun `finds a format by any of its extensions, with or without the dot, ignoring case`() {
        assertEquals("json", families.formatOf(".JSONC")?.id)
        assertNull(families.formatOf("yaml"))
    }
}
