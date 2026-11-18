package dev.sluide.databases.sql

import dev.sluide.databases.connect.DatabaseKind
import dev.sluide.databases.connect.DatabaseKind.MARIADB
import dev.sluide.databases.connect.DatabaseKind.POSTGRESQL
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Completion by the tables a statement names, the qualifiers before a dot and the caret's place in quotes. */
class SqlCompletionReferencesTest {
    /** `schema -> table -> columns`; the first schema is the default one. */
    private class MapCatalog(
        private val data: Map<String, Map<String, List<String>>>,
    ) : CompletionCatalog {
        override val defaultSchema = data.keys.first()

        override fun schemas() = data.keys.toList()

        override fun tables(schema: String?) = data[schema ?: defaultSchema]?.keys?.toList() ?: emptyList()

        override fun columns(
            schema: String?,
            table: String,
        ) = data[schema ?: defaultSchema]?.get(table) ?: emptyList()
    }

    private val catalog =
        MapCatalog(
            linkedMapOf(
                "public" to
                    linkedMapOf(
                        "users" to listOf("id", "name"),
                        "orders" to listOf("id", "user_id"),
                        "left" to listOf("lc"),
                        "we\"ird" to listOf("w1"),
                        "we`ird" to listOf("b1"),
                        "a\$b" to listOf("d1"),
                        "1st" to listOf("f1"),
                        "has space" to listOf("s1"),
                        "select" to listOf("k1"),
                        "_x" to listOf("x1"),
                        "Select" to listOf("k2"),
                    ),
                "sales" to
                    linkedMapOf(
                        "invoices" to listOf("total", "inv_id"),
                        "users" to listOf("sales_user"),
                    ),
            ),
        )

    private fun complete(
        marked: String,
        kind: DatabaseKind = POSTGRESQL,
        using: CompletionCatalog = catalog,
    ): CompletionResult {
        val caret = marked.indexOf('|')
        return SqlCompletion.candidates(marked.replace("|", ""), caret, kind, using)
    }

    private fun CompletionResult.texts() = items.map { it.text }

    private fun CompletionResult.labels(of: CompletionKind) = items.filter { it.kind == of }.map { it.label }

    // ---- the caret and the replaced range ----

    @Test
    fun `the replaced range ends at the caret in the middle of a word`() {
        val result = complete("select u|ser from users")

        assertEquals(7, result.replaceFrom)
        assertEquals(8, result.replaceTo)
        assertEquals(listOf("users"), result.labels(CompletionKind.TABLE))
    }

    @Test
    fun `a caret in front of a quoted identifier is outside it and offers everything`() {
        val result = complete("select * from |\"users\"")

        assertEquals(14, result.replaceFrom)
        assertEquals(14, result.replaceTo)
        assertTrue(result.items.any { it.kind == CompletionKind.KEYWORD })
        assertTrue("users" in result.texts())
    }

    @Test
    fun `a caret right behind the opening quote replaces the whole closed identifier and offers no keywords`() {
        val result = complete("select * from \"|users\"")

        assertEquals(14, result.replaceFrom)
        assertEquals(21, result.replaceTo)
        assertTrue(result.items.none { it.kind == CompletionKind.KEYWORD })
        assertTrue("\"users\"" in result.texts())
    }

    @Test
    fun `a caret behind a closed quoted identifier is outside it`() {
        val result = complete("select * from \"users\"|")

        assertEquals(21, result.replaceFrom)
        assertEquals(21, result.replaceTo)
        assertTrue(result.items.any { it.kind == CompletionKind.KEYWORD })
    }

    @Test
    fun `in an unclosed quoted identifier only the text before the caret is replaced`() {
        val result = complete("select * from \"us|ers")

        assertEquals(14, result.replaceFrom)
        assertEquals(17, result.replaceTo)
        assertTrue("\"users\"" in result.texts())
    }

    @Test
    fun `doubled quotes in the typed prefix stand for one quote`() {
        assertEquals(listOf("\"we\"\"ird\""), complete("select * from \"we\"\"i|").texts())
        assertEquals(listOf("`we``ird`"), complete("select * from `we``i|", MARIADB).texts())
    }

    @Test
    fun `a caret behind a closed string or comment is code again, a caret behind an unclosed one is not`() {
        assertTrue(complete("select 'a'|").items.isNotEmpty())
        assertTrue(complete("select /* a */|").items.isNotEmpty())
        assertEquals(emptyList(), complete("select 'a|").items)
        assertEquals(emptyList(), complete("select 'a|b'").items)
        assertTrue(complete("select |'a'").items.isNotEmpty(), "in front of the string")
        assertEquals(emptyList(), complete("select 1 -- a|").items)
    }

    @ParameterizedTest
    @ValueSource(strings = ["us|", "u|", "select |", "select 1|", ".|"])
    fun `shouldAutoOpen reads the word to the start of the text`(marked: String) {
        val text = marked.replace("|", "")
        val caret = marked.indexOf('|')
        val expected = marked == "us|" || marked == ".|"

        assertEquals(expected, SqlCompletion.shouldAutoOpen(text, caret, POSTGRESQL), marked)
    }

    @Test
    fun `shouldAutoOpen refuses a caret outside the text and opens inside a quoted identifier`() {
        assertFalse(SqlCompletion.shouldAutoOpen("ab", 0, POSTGRESQL))
        assertFalse(SqlCompletion.shouldAutoOpen("ab", -1, POSTGRESQL))
        assertFalse(SqlCompletion.shouldAutoOpen("ab", 3, POSTGRESQL))
        assertFalse(SqlCompletion.shouldAutoOpen("", 0, POSTGRESQL))
        assertTrue(SqlCompletion.shouldAutoOpen("ab", 2, POSTGRESQL))
        assertTrue(SqlCompletion.shouldAutoOpen("select \"ab", 10, POSTGRESQL))
        assertFalse(SqlCompletion.shouldAutoOpen("select \$\$ ab", 12, POSTGRESQL), "inside a dollar quote")
    }

    // ---- keywords and duplicates ----

    @Test
    fun `keywords are upper case unless the typed prefix is lower case`() {
        val none = complete("select | from users").items.filter { it.kind == CompletionKind.KEYWORD }
        assertTrue(none.isNotEmpty())
        assertTrue(none.all { it.text == it.text.uppercase() && it.text == it.label })
        val lower = complete("select fro| from users").items.filter { it.kind == CompletionKind.KEYWORD }
        assertEquals(listOf("from"), lower.map { it.text })
        assertEquals(listOf("FROM"), lower.map { it.label })
        assertEquals(
            listOf("FROM"),
            complete("select Fro|").items.filter { it.kind == CompletionKind.KEYWORD }.map { it.text },
        )
        assertEquals(
            listOf("FROM"),
            complete("select FRO|").items.filter { it.kind == CompletionKind.KEYWORD }.map { it.text },
        )
    }

    @Test
    fun `a name offered as column is not offered again as table`() {
        val both = complete("select * from lef|").items
        assertEquals(listOf(CompletionKind.TABLE, CompletionKind.KEYWORD), both.map { it.kind })
        assertEquals(listOf("\"left\"", "left"), both.map { it.text })

        val twice =
            object : CompletionCatalog by catalog {
                override fun tables(schema: String?) = listOf("dup", "dup")

                override fun columns(
                    schema: String?,
                    table: String,
                ) = listOf("dup")
            }
        val items = complete("select * from dup|", using = twice).items
        assertEquals(listOf(CompletionKind.COLUMN), items.map { it.kind }, "the first kind of a name wins")
    }

    @Test
    fun `an identifier is quoted when it is not plain lower case, starts with a digit or is reserved`() {
        val texts =
            complete("select * from |").items.filter { it.kind == CompletionKind.TABLE }.associate {
                it.label to
                    it.text
            }

        assertEquals("a\$b", texts["a\$b"])
        assertEquals("_x", texts["_x"])
        assertEquals("\"1st\"", texts["1st"])
        assertEquals("\"has space\"", texts["has space"])
        assertEquals("\"select\"", texts["select"])
        assertEquals("\"Select\"", texts["Select"])
        assertEquals("\"we\"\"ird\"", texts["we\"ird"])
        val maria =
            complete("select * from |", MARIADB).items.filter { it.kind == CompletionKind.TABLE }.associate {
                it.label to
                    it.text
            }
        assertEquals("`Select`", maria["Select"], "reserved in any case")
        assertEquals("`1st`", maria["1st"])
        assertEquals("a\$b", maria["a\$b"])
    }

    // ---- the qualifier before the dot ----

    @Test
    fun `a dot without a name before it offers nothing`() {
        assertEquals(emptyList(), complete("select .|").items)
        assertEquals(emptyList(), complete(".|").items)
        assertEquals(emptyList(), complete("select (a).|").items)
    }

    @Test
    fun `a schema at the very start of the text gives its tables`() {
        assertEquals(listOf("invoices", "users"), complete("sales.|").texts())
        assertEquals(listOf("invoices", "users"), complete("`sales`.|", MARIADB).texts())
        assertEquals(listOf("invoices", "users"), complete("\"sales\".|").texts())
    }

    @Test
    fun `a closing quote without its opening quote is no name`() {
        assertEquals(emptyList(), complete("select a`.|").items)
    }

    @Test
    fun `schema and table before the dot are found without being in the statement, by their folding`() {
        assertEquals(listOf("total", "inv_id"), complete("select sales.invoices.| from users").texts())
        assertEquals(listOf("total", "inv_id"), complete("select SALES.INVOICES.| from users").texts())
        assertEquals(listOf("total", "inv_id"), complete("select \"sales\".invoices.|").texts())
        assertEquals(emptyList(), complete("select \"SALES\".invoices.|").items)
        assertEquals(emptyList(), complete("select nope.invoices.|").items)
        assertEquals(listOf("total", "inv_id"), complete("select SALES.Invoices.|", MARIADB).texts())
        assertEquals(listOf("w1"), complete("select public.\"we\"\"ird\".|").texts())
        assertEquals(listOf("b1"), complete("select public.`we``ird`.|", MARIADB).texts())
    }

    @Test
    fun `a quoted qualifier keeps doubled quotes inside its name`() {
        assertEquals(listOf("w1"), complete("select \"we\"\"ird\".| from \"we\"\"ird\"").texts())
        assertEquals(listOf("b1"), complete("select `we``ird`.| from `we``ird`", MARIADB).texts())
    }

    @Test
    fun `an alias wins over a table of the same name, an unaliased table over an aliased one`() {
        assertEquals(listOf("id", "user_id"), complete("select users.| from orders users, users").texts())
        assertEquals(listOf("sales_user"), complete("select users.| from public.users x, sales.users").texts())
        assertEquals(
            listOf("id", "name"),
            complete("select users.| from users u").texts(),
            "aliased, but the only match",
        )
        assertEquals(listOf("sales_user"), complete("select u.| from public.users p, sales.users u").texts())
    }

    @Test
    fun `a qualifier that is a table without columns falls back to the schema of that name`() {
        assertEquals(listOf("invoices", "users"), complete("select sales.| from sales").texts())
        assertEquals(emptyList(), complete("select nothing.| from nothing").items)
    }

    // ---- the tables a statement names ----

    @Test
    fun `only the statement at the caret counts, up to and including the caret's edges`() {
        assertEquals(emptyList(), complete("select 1 from users u; select u.|").items)
        assertEquals(listOf("id", "name"), complete("select u.| from users u;select 1").texts())
        assertEquals(emptyList(), complete("select u.|; select 1 from orders u").items)
        assertTrue(
            "id" in complete("select * from users u |;select 1").labels(CompletionKind.COLUMN),
            "the caret at the end",
        )
        assertTrue("id" in complete("select 1;|from users u").texts(), "the first character of the next statement")
        assertTrue(complete("   |").items.isNotEmpty(), "no statement at all")
    }

    @Test
    fun `strings and comments of earlier statements and of the statement are skipped`() {
        val text = "select 'x'; select * from users u where n = 'join sales.invoices i' and |"
        assertTrue(complete(text).labels(CompletionKind.COLUMN).none { it == "total" })
        assertTrue("id" in complete(text).labels(CompletionKind.COLUMN))
        assertEquals(emptyList(), complete("/* a */ select -- c\n i.| from /* from sales.invoices i */ users u").items)
    }

    @Test
    fun `a string behind a table name is no alias`() {
        assertEquals(emptyList(), complete("select u.| from users 'x' u").items)
        assertEquals(listOf("id", "name"), complete("select u.| from users /* c */ u").texts())
    }

    @Test
    fun `a quoted word is a name, never a keyword of the statement`() {
        assertEquals(listOf("id", "name"), complete("select \"as\".| from users \"as\"").texts())
        assertTrue(
            complete("select \"from\" sales.invoices from users u where |").labels(CompletionKind.COLUMN).none {
                it ==
                    "total"
            },
        )
        assertEquals(listOf("id", "name"), complete("select \"U\".| from users \"U\"").texts())
        assertEquals(listOf("w1"), complete("select \"x\"\"y\".| from \"we\"\"ird\" \"x\"\"y\"").texts())
    }

    @Test
    fun `an unclosed quoted table name is read to the end`() {
        assertEquals(listOf("id", "name"), complete("select | from \"users").labels(CompletionKind.COLUMN))
    }

    @Test
    fun `ONLY is skipped in any case, AS may follow a table, a reserved word may be an alias after AS`() {
        assertEquals(listOf("id", "name"), complete("select u.| from only users u").texts())
        assertEquals(listOf("id", "name"), complete("select u.| from ONLY users u").texts())
        assertEquals(listOf("id", "name"), complete("select u.| from users as u").texts())
        assertEquals(listOf("id", "name"), complete("select u.| from users AS u").texts())
        assertEquals(listOf("id", "name"), complete("select order.| from users as order").texts())
        assertEquals(listOf("id", "user_id"), complete("select i.| from users as u, orders as i").texts())
        assertEquals(listOf("id", "name"), complete("select u.| from users as u, orders as i").texts())
    }

    @Test
    fun `a stop word is no alias, but a table after a dot may be named like one`() {
        assertTrue(
            complete("select * from users where |").labels(CompletionKind.COLUMN).containsAll(listOf("id", "name")),
        )
        assertEquals(
            listOf("total", "inv_id"),
            complete("select i.| from users left join sales.invoices i on i.id = 1").texts(),
        )
        assertEquals(listOf("lc"), complete("select left.| from public.left").texts())
        assertEquals(emptyList(), complete("select where.| from users where").items)
    }

    @Test
    fun `a comma continues the list after FROM and UPDATE, not after INTO or JOIN`() {
        assertEquals(
            listOf("total", "inv_id"),
            complete("update users u, sales.invoices i set x = 1 where i.|").texts(),
        )
        assertEquals(listOf("total", "inv_id"), complete("select i.| from users, sales.invoices i").texts())
        assertEquals(emptyList(), complete("insert into users, sales.invoices i select i.|").items)
        assertEquals(emptyList(), complete("select i.| from users u join users, sales.invoices i").items)
    }

    @Test
    fun `a list that ends after its comma or before a stop word keeps the references so far`() {
        assertEquals(listOf("id", "name"), complete("select u.| from users u, ").texts())
        assertEquals(listOf("id", "name"), complete("select u.| from users u, where").texts())
        assertEquals(emptyList(), complete("select | from").items.filter { it.kind == CompletionKind.COLUMN })
    }

    @Test
    fun `hostile text with thousands of tables and words completes quickly`() {
        assertTimeoutPreemptively(Duration.ofSeconds(30)) {
            val many = "select u.| from " + (0 until 5_000).joinToString(", ") { "users u$it" }
            assertEquals(emptyList(), complete(many).items)
            val unclosed = "select * from \"" + "a".repeat(100_000) + "|"
            assertEquals(emptyList(), complete(unclosed).items.filter { it.kind == CompletionKind.COLUMN })
            val words = "from ".repeat(20_000) + "|"
            assertTrue(complete(words).items.isNotEmpty())
        }
    }

    @Test
    fun `a table named like a keyword in upper case is offered next to the keyword`() {
        val shouting =
            object : CompletionCatalog by catalog {
                override fun tables(schema: String?) = listOf("LEFT")
            }

        val items = complete("select * from LEF|", using = shouting).items

        assertEquals(listOf(CompletionKind.TABLE, CompletionKind.KEYWORD), items.map { it.kind })
        assertEquals(listOf("LEFT", "LEFT"), items.map { it.label })
    }

    @Test
    fun `columns of a table with a schema are offered without a qualifier`() {
        assertEquals(listOf("total", "inv_id"), complete("select | from sales.invoices").labels(CompletionKind.COLUMN))
        assertEquals(listOf("sales_user"), complete("select | from \"sales\".users").labels(CompletionKind.COLUMN))
    }

    @Test
    fun `a closing backtick without an opening one is no name, at any place of the text`() {
        assertEquals(emptyList(), complete("xsales`.|").items)
        assertEquals(emptyList(), complete("sales`.|").items)
    }

    @Test
    fun `a dot behind a word-less place does not match a schema with an empty name`() {
        val odd =
            MapCatalog(
                linkedMapOf(
                    "public" to linkedMapOf("t" to listOf("c")),
                    "" to linkedMapOf("ghost" to listOf("g")),
                ),
            )

        assertEquals(emptyList(), complete("select (a).|", using = odd).items)
    }

    @Test
    fun `a caret in a gap between statements has no table references`() {
        assertTrue(complete("  |; select * from users u").labels(CompletionKind.COLUMN).isEmpty())
        assertTrue(complete(" ;|  ; select * from users u").labels(CompletionKind.COLUMN).isEmpty())
    }

    @Test
    fun `AS at the end of the statement names no alias`() {
        assertEquals(listOf("id", "name"), complete("select | from users as").labels(CompletionKind.COLUMN))
        assertEquals(emptyList(), complete("select u.| from users as").items)
    }

    @Test
    fun `a caret on the line after a line comment is code again`() {
        assertTrue(complete("-- c\nsel|").items.any { it.label == "SELECT" })
        assertTrue(complete("select 1 -- c\n\n  sel|").items.any { it.label == "SELECT" })
        assertEquals(emptyList(), complete("select 1 -- c|\nselect").items)
    }
}
