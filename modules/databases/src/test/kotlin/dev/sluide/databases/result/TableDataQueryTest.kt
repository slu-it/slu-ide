package dev.sluide.databases.result

import dev.sluide.databases.connect.DatabaseKind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TableDataQueryTest {
    private val pg = TableRef(DatabaseKind.POSTGRESQL, "public", "users")
    private val maria = TableRef(DatabaseKind.MARIADB, null, "us`ers")

    @Test
    fun `selects with quoted names and leaves out blank parts`() {
        assertEquals("SELECT * FROM \"public\".\"users\"", TableDataQuery.select(pg, "", "  "))
        assertEquals("SELECT * FROM `us``ers`", TableDataQuery.select(maria, " ", ""))
        assertEquals("\"public\".\"users\"", pg.qualified)
    }

    @Test
    fun `adds where in parentheses and order by`() {
        assertEquals(
            "SELECT * FROM \"public\".\"users\"\nWHERE (a = 1 OR b = 2\n)\nORDER BY \"id\" DESC\n",
            TableDataQuery.select(pg, " a = 1 OR b = 2 ", "\"id\" DESC"),
        )
        assertEquals("SELECT * FROM `us``ers`\nORDER BY x\n", TableDataQuery.select(maria, "", "x"))
    }

    @Test
    fun `trailing semicolons go and a trailing comment cannot swallow the rest`() {
        assertEquals(
            "SELECT * FROM \"public\".\"users\"\nWHERE (a = 1 -- only a\n)\nORDER BY b\n",
            TableDataQuery.select(pg, "a = 1 -- only a;", " b ; ; "),
        )
        assertEquals("SELECT * FROM `us``ers`", TableDataQuery.select(maria, ";", " ; "))
    }

    @Test
    fun `order by of a header click`() {
        assertEquals("\"na\"\"me\" ASC", TableDataQuery.orderBy(DatabaseKind.POSTGRESQL, "na\"me", true))
        assertEquals("`name` DESC", TableDataQuery.orderBy(DatabaseKind.MARIADB, "name", false))
    }

    @Test
    fun `default order applies only while order by is blank`() {
        assertEquals(
            "SELECT * FROM \"public\".\"users\"\nORDER BY \"a\", \"b\"\n",
            TableDataQuery.select(pg, "", " ; ", listOf("a", "b")),
        )
        assertEquals("SELECT * FROM `us``ers`\nORDER BY `id`\n", TableDataQuery.select(maria, "", "", listOf("id")))
        assertEquals("SELECT * FROM `us``ers`\nORDER BY x\n", TableDataQuery.select(maria, "", "x", listOf("id")))
        assertEquals("SELECT * FROM \"public\".\"users\"", TableDataQuery.select(pg, "", "", emptyList()))
    }

    @Test
    fun `default order text quotes the key columns`() {
        assertEquals("\"a\"\"b\", \"c\"", TableDataQuery.defaultOrderText(DatabaseKind.POSTGRESQL, listOf("a\"b", "c")))
        assertEquals("`id`", TableDataQuery.defaultOrderText(DatabaseKind.MARIADB, listOf("id")))
    }
}
