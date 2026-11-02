package dev.sluide.databases.result

import dev.sluide.databases.connect.DatabaseKind
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger
import java.sql.Types
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DataEditsTest {
    private val cols = listOf(col("id"), col("name"), col("note"))
    private val rows = listOf(listOf<Any?>(1L, "a", "x"), listOf<Any?>(2L, "b", null), listOf<Any?>(3L, "c", "z"))

    private fun edits(kind: DatabaseKind = DatabaseKind.POSTGRESQL): DataEdits =
        DataEdits(Editability.Editable(TableRef(kind, "s", "t"), listOf("id")), cols, rows)

    @Test
    fun `nothing pending gives no statements`() {
        val e = edits()
        assertFalse(e.pending)
        assertEquals(emptyList(), e.statements())
        assertEquals(3, e.rowCount)
        assertEquals("b", e.value(1, 1))
    }

    @Test
    fun `update sets only changed columns and keys on the original`() {
        val e = edits()
        e.edit(0, 1, "A")
        e.edit(0, 0, 10L)
        e.edit(1, 2, "n")
        assertTrue(e.pending)
        assertTrue(e.isChanged(0, 1))
        assertFalse(e.isChanged(0, 2))
        assertEquals(
            listOf(
                BoundStatement(
                    "UPDATE \"s\".\"t\" SET \"id\" = ?, \"name\" = ? WHERE \"id\" = ?",
                    listOf(10L, "A", 1L),
                    listOf(null, Types.OTHER, null),
                    true,
                ),
                BoundStatement(
                    "UPDATE \"s\".\"t\" SET \"note\" = ? WHERE \"id\" = ?",
                    listOf("n", 2L),
                    listOf(Types.OTHER, null),
                    true,
                ),
            ),
            e.statements(),
        )
    }

    @Test
    fun `editing back to the original unmarks the cell`() {
        val e = edits()
        e.edit(0, 1, "A")
        e.edit(0, 1, "a")
        assertFalse(e.isChanged(0, 1))
        assertFalse(e.pending)
        e.edit(1, 2, "q")
        e.edit(1, 2, null)
        assertFalse(e.pending)
    }

    @Test
    fun `equal decimals and bytes count as unchanged`() {
        val e =
            DataEdits(
                Editability.Editable(TableRef(DatabaseKind.POSTGRESQL, null, "t"), listOf("id")),
                cols,
                listOf(listOf(BigDecimal("1.0"), byteArrayOf(1), null)),
            )
        e.edit(0, 0, BigDecimal("1.00"))
        e.edit(0, 1, byteArrayOf(1))
        assertFalse(e.pending)
    }

    @Test
    fun `delete uses the key and wins over edits`() {
        val e = edits(DatabaseKind.MARIADB)
        e.edit(2, 1, "C")
        e.deleteRows(listOf(2, 0))
        assertTrue(e.isDeleted(0))
        assertEquals(
            listOf(
                BoundStatement("DELETE FROM `s`.`t` WHERE `id` = ?", listOf(1L), listOf(null), true),
                BoundStatement("DELETE FROM `s`.`t` WHERE `id` = ?", listOf(3L), listOf(null), true),
            ),
            e.statements(),
        )
        assertFailsWith<IllegalStateException> { e.edit(0, 1, "x") }
    }

    @Test
    fun `composite keys are anded`() {
        val e =
            DataEdits(
                Editability.Editable(TableRef(DatabaseKind.POSTGRESQL, null, "t"), listOf("id", "name")),
                cols,
                rows,
            )
        e.deleteRows(listOf(1))
        assertEquals(
            listOf(
                BoundStatement(
                    "DELETE FROM \"t\" WHERE \"id\" = ? AND \"name\" = ?",
                    listOf(2L, "b"),
                    listOf(null, Types.OTHER),
                    true,
                ),
            ),
            e.statements(),
        )
    }

    @Test
    fun `insert only the given columns`() {
        val e = edits()
        val r = e.addRow()
        assertEquals(3, r)
        assertTrue(e.isAdded(r))
        assertFalse(e.isChanged(r, 1))
        e.edit(r, 1, "n")
        e.edit(r, 2, null)
        assertTrue(e.isChanged(r, 2))
        assertEquals(
            listOf(
                BoundStatement(
                    "INSERT INTO \"s\".\"t\" (\"name\", \"note\") VALUES (?, ?)",
                    listOf("n", null),
                    listOf(Types.OTHER, null),
                ),
            ),
            e.statements(),
        )
    }

    @Test
    fun `insert without values uses defaults per kind`() {
        val pg = edits()
        pg.addRow()
        assertEquals("INSERT INTO \"s\".\"t\" DEFAULT VALUES", pg.statements().single().sql)
        val maria = edits(DatabaseKind.MARIADB)
        maria.addRow()
        assertEquals("INSERT INTO `s`.`t` () VALUES ()", maria.statements().single().sql)
    }

    @Test
    fun `statements run deletes then updates then inserts`() {
        val e = edits()
        e.addRow().also { e.edit(it, 0, 9L) }
        e.edit(1, 1, "B")
        e.deleteRows(listOf(0))
        assertEquals(listOf("DELETE", "UPDATE", "INSERT"), e.statements().map { it.sql.substringBefore(' ') })
    }

    @Test
    fun `deleting an added row removes it and shifts later added rows`() {
        val e = edits()
        val first = e.addRow()
        val second = e.addRow()
        e.edit(second, 1, "two")
        e.deleteRows(listOf(first))
        assertEquals(4, e.rowCount)
        assertEquals("two", e.value(3, 1))
        assertEquals(1, e.statements().size)
    }

    @Test
    fun `revert restores rows and drops added ones`() {
        val e = edits()
        e.edit(0, 1, "A")
        e.deleteRows(listOf(1))
        val added = e.addRow()
        e.revert(listOf(1, added))
        assertFalse(e.isDeleted(1))
        assertEquals(3, e.rowCount)
        assertTrue(e.isChanged(0, 1))
        e.revert(listOf(0))
        assertEquals("a", e.value(0, 1))
        assertFalse(e.pending)
    }

    @Test
    fun `revert all`() {
        val e = edits()
        e.edit(0, 1, "A")
        e.deleteRows(listOf(1))
        e.addRow()
        e.revertAll()
        assertFalse(e.pending)
        assertEquals(3, e.rowCount)
        assertEquals("a", e.value(0, 1))
    }

    @Test
    fun `accepted makes edits the originals`() {
        val e = edits()
        e.edit(0, 1, "A")
        e.deleteRows(listOf(1))
        val added = e.addRow()
        e.edit(added, 0, 9L)
        e.edit(added, 1, "n")
        e.edit(added, 2, null)
        val accepted = e.accepted()
        assertFalse(accepted.needsRefresh)
        assertEquals(
            listOf(listOf<Any?>(1L, "A", "x"), listOf<Any?>(3L, "c", "z"), listOf<Any?>(9L, "n", null)),
            accepted.rows,
        )
        assertFalse(e.pending)
        assertEquals(3, e.rowCount)
        assertFalse(e.isAdded(2))
        e.edit(0, 1, "a")
        assertEquals(listOf<Any?>("a", 1L), e.statements().single().params)
    }

    @Test
    fun `mariadb binds without types and postgresql types only strings`() {
        val pg = edits()
        pg.edit(0, 0, 5L)
        pg.edit(0, 1, "x")
        pg.edit(0, 2, null)
        assertEquals(listOf(null, Types.OTHER, null, null), pg.statements().single().types)
        val maria = edits(DatabaseKind.MARIADB)
        maria.edit(0, 1, "x")
        maria.edit(0, 2, "y")
        assertEquals(listOf(null, null, null), maria.statements().single().types)
        val insert = edits(DatabaseKind.MARIADB)
        insert.edit(insert.addRow(), 1, "n")
        assertEquals(listOf<Int?>(null), insert.statements().single().types)
    }

    @Test
    fun `update and delete expect one row, insert does not`() {
        val e = edits()
        e.edit(0, 1, "A")
        e.deleteRows(listOf(1))
        e.edit(e.addRow(), 1, "n")
        assertEquals(listOf(true, true, false), e.statements().map { it.expectOneRow })
    }

    @Test
    fun `an added row without its generated key is dropped and asks for a refresh`() {
        val e = edits()
        e.edit(e.addRow(), 1, "new")
        e.edit(0, 1, "A")
        val accepted = e.accepted()
        assertTrue(accepted.needsRefresh)
        assertEquals(
            listOf(listOf<Any?>(1L, "A", "x"), listOf<Any?>(2L, "b", null), listOf<Any?>(3L, "c", "z")),
            accepted.rows,
        )
        assertEquals(3, e.rowCount)
        assertFalse(e.pending)
    }

    @Test
    fun `statements refuse an original with a null key`() {
        val e =
            DataEdits(
                Editability.Editable(TableRef(DatabaseKind.POSTGRESQL, null, "t"), listOf("id")),
                cols,
                listOf(listOf(null, "a", "x")),
            )
        e.edit(0, 1, "A")
        assertFailsWith<IllegalStateException> { e.statements() }
        e.revertAll()
        e.deleteRows(listOf(0))
        assertFailsWith<IllegalStateException> { e.statements() }
    }

    @Test
    fun `numbers count as unchanged when numerically equal`() {
        val e =
            DataEdits(
                Editability.Editable(TableRef(DatabaseKind.POSTGRESQL, null, "t"), listOf("id")),
                cols,
                listOf(listOf<Any?>(1, 2.0, BigInteger.TEN)),
            )
        e.edit(0, 0, 1L)
        e.edit(0, 1, BigDecimal("2.00"))
        e.edit(0, 2, 10L)
        assertFalse(e.pending)
        e.edit(0, 1, BigDecimal("2.5"))
        assertTrue(e.isChanged(0, 1))
    }

    @Test
    fun `an added row that left columns out asks for a refresh to show server defaults`() {
        val e = edits()
        e.edit(e.addRow(), 0, 9L)
        val accepted = e.accepted()
        assertTrue(accepted.needsRefresh)
        assertEquals(listOf<Any?>(9L, null, null), accepted.rows.last())
    }
}
