package dev.sluide.databases.result

import dev.sluide.databases.connect.DatabaseKind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EditabilitiesTest {
    private val kind = DatabaseKind.POSTGRESQL
    private val cols = listOf(col("id"), col("name"))

    @Test
    fun `single table from columns of one table`() {
        assertEquals(TableRef(kind, "s", "t"), Editabilities.singleTable(kind, cols))
    }

    @Test
    fun `no single table for joins, expressions or nothing`() {
        assertNull(Editabilities.singleTable(kind, cols + col("x", table = "other")))
        assertNull(Editabilities.singleTable(kind, cols + col("x", table = null)))
        assertNull(Editabilities.singleTable(kind, cols + col("x", schema = "other")))
        assertNull(Editabilities.singleTable(kind, emptyList()))
    }

    @Test
    fun `editable with a complete key`() {
        assertEquals(
            Editability.Editable(TableRef(kind, "s", "t"), listOf("id")),
            Editabilities.check(kind, cols, listOf("id"), false),
        )
    }

    @Test
    fun `read-only reasons in order`() {
        fun reason(
            columns: List<dev.sluide.databases.session.ColumnInfo>,
            key: List<String>,
            readOnly: Boolean,
        ) = (Editabilities.check(kind, columns, key, readOnly) as Editability.ReadOnly).reason
        assertEquals("Read-only connection", reason(cols, listOf("id"), true))
        assertEquals(
            "The result comes from several tables or expressions",
            reason(cols + col("x", table = null), listOf("id"), false),
        )
        assertEquals("The table has no primary key", reason(cols, emptyList(), false))
        assertEquals("The primary key column id2 is not in the result", reason(cols, listOf("id", "id2"), false))
    }
}
