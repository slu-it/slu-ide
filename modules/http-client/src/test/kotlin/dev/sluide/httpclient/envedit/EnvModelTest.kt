package dev.sluide.httpclient.envedit

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The small types of the environment table model: paths, edits and the keys of the table's lines. */
internal class EnvModelTest {
    @Test
    fun `writes a path with its keys joined by dots and its indexes in brackets`() {
        assertEquals("", ValuePath.ROOT.toString())
        assertEquals("a.b", ValuePath.of("a", "b").toString())
        assertEquals("a[0]", ValuePath.of("a").index(0).toString())
        assertEquals(
            "a[0].b[12]",
            ValuePath
                .of("a")
                .index(0)
                .key("b")
                .index(12)
                .toString(),
        )
        assertEquals(
            "[3].a",
            ValuePath.ROOT
                .index(3)
                .key("a")
                .toString(),
        )
    }

    @Test
    fun `an edit is empty only when neither file changes`() {
        assertTrue(EnvEdit(null, null).isEmpty)
        assertFalse(EnvEdit("a", null).isEmpty)
        assertFalse(EnvEdit(null, "b").isEmpty)
        assertFalse(EnvEdit("a", "b").isEmpty)
    }

    @Test
    fun `tells the lines of the table apart by a key that stays the same across refreshes`() {
        val row =
            Row(
                ValuePath.of("a", "b"),
                "b",
                ValueType.TEXT,
                emptyMap(),
                emptyList(),
                FileSide.PUBLIC,
                false,
                false,
                null,
                emptySet(),
            )

        // A refresh builds new rows with other values; the key still finds the line again.
        val refreshed = row.copy(label = "renamed", type = ValueType.NUMBER, readOnly = true)
        val other = row.copy(path = ValuePath.of("a", "c"))

        assertEquals("variables", EnvEntry.Variables.key)
        assertEquals("auth:api", EnvEntry.Auth(AuthGroup("api", emptyList())).key)
        assertEquals("auth:api", EnvEntry.Auth(AuthGroup("api", listOf(row))).key)
        assertNotEquals(
            EnvEntry.Auth(AuthGroup("api", emptyList())).key,
            EnvEntry.Auth(AuthGroup("x", emptyList())).key,
        )
        assertEquals("certificate", EnvEntry.Certificate(emptyList()).key)
        assertEquals("certificate", EnvEntry.Certificate(listOf(row)).key)
        assertEquals(EnvEntry.Item(row, variable = true).key, EnvEntry.Item(refreshed, variable = false).key)
        assertEquals(ValuePath.of("a", "b"), EnvEntry.Item(row, variable = true).key)
        assertNotEquals(EnvEntry.Item(row, variable = true).key, EnvEntry.Item(other, variable = true).key)
    }
}
