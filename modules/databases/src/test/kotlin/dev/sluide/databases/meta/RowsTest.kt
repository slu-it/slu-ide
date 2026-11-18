package dev.sluide.databases.meta

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RowsTest {
    @Test
    fun `str is the text of a value and null for a missing or null value`() {
        val row = mapOf("a" to 5, "b" to null, "c" to "x")

        assertEquals("5", row.str("a"))
        assertNull(row.str("b"))
        assertNull(row.str("missing"))
        assertEquals("x", row.str("c"))
    }

    @Test
    fun `text is empty for a missing or null value`() {
        val row = mapOf("a" to 5, "b" to null)

        assertEquals("5", row.text("a"))
        assertEquals("", row.text("b"))
        assertEquals("", row.text("missing"))
    }

    @Test
    fun `flag reads booleans, numbers and text`() {
        listOf(true, 1, 2, -1, 1L, 1.0, "t", "T", "true", "TRUE", "1", "yes", "YES").forEach {
            assertTrue(mapOf("k" to it).flag("k"), "$it")
        }
        listOf(false, 0, 0L, 0.0, "f", "false", "0", "no", "", "maybe").forEach {
            assertFalse(mapOf("k" to it).flag("k"), "$it")
        }
        assertFalse(mapOf("k" to null).flag("k"))
        assertFalse(emptyMap<String, Any?>().flag("k"))
    }

    @Test
    fun `int reads numbers and numeric text and is zero otherwise`() {
        assertEquals(7, mapOf("k" to 7).int("k"))
        assertEquals(7, mapOf("k" to 7L).int("k"))
        assertEquals(2, mapOf("k" to 2.9).int("k"))
        assertEquals(12, mapOf("k" to "12").int("k"))
        assertEquals(-3, mapOf("k" to "-3").int("k"))
        assertEquals(0, mapOf("k" to "x").int("k"))
        assertEquals(0, mapOf("k" to null).int("k"))
        assertEquals(0, emptyMap<String, Any?>().int("k"))
    }

    @Test
    fun `rows reads every row with lower-case labels and binds the parameters in order`() {
        val jdbc = CatalogJdbc()
        jdbc.results["q"] = CatalogJdbc.Result(listOf("Name", "QTY"), listOf(listOf("a", 1), listOf("b", null)))

        val rows = jdbc.connection.rows("q", "x", null, 3)

        assertEquals(listOf(mapOf("name" to "a", "qty" to 1), mapOf("name" to "b", "qty" to null)), rows)
        assertEquals(listOf("q" to listOf<Any?>("x", null, 3)), jdbc.calls)
        assertEquals(2, jdbc.closed)
    }

    @Test
    fun `rows of a query without results is empty and without parameters binds none`() {
        val jdbc = CatalogJdbc()

        assertEquals(emptyList(), jdbc.connection.rows("q"))
        assertEquals(listOf("q" to emptyList<Any?>()), jdbc.calls)
    }
}
