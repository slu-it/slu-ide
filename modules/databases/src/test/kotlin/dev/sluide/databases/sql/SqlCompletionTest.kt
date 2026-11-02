package dev.sluide.databases.sql

import dev.sluide.databases.connect.DatabaseKind
import dev.sluide.databases.connect.DatabaseKind.MARIADB
import dev.sluide.databases.connect.DatabaseKind.POSTGRESQL
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SqlCompletionTest {
    private val catalog =
        object : CompletionCatalog {
            override val defaultSchema = "public"

            override fun schemas() = listOf("public", "sales")

            override fun tables(schema: String?) =
                when (schema ?: defaultSchema) {
                    "public" -> listOf("users", "Orders", "order")
                    "sales" -> listOf("invoices")
                    else -> emptyList()
                }

            override fun columns(
                schema: String?,
                table: String,
            ) = when ("${schema ?: defaultSchema}.$table") {
                "public.users" -> listOf("id", "name", "Full Name")
                "public.Orders" -> listOf("id", "user_id")
                "sales.invoices" -> listOf("total", "id")
                else -> emptyList()
            }
        }

    /** Completes at the `|` in [marked]. */
    private fun complete(
        marked: String,
        kind: DatabaseKind = POSTGRESQL,
    ): CompletionResult {
        val caret = marked.indexOf('|')
        return SqlCompletion.candidates(marked.replace("|", ""), caret, kind, catalog)
    }

    private fun CompletionResult.texts() = items.map { it.text }

    @Test
    fun `alias qualifier gives the columns of its table`() {
        val result = complete("select u.na| from users u")
        assertEquals(9, result.replaceFrom)
        assertEquals(listOf(Completion("name", "name", CompletionKind.COLUMN)), result.items)
    }

    @Test
    fun `table name, AS alias, schema-qualified and comma list resolve`() {
        assertEquals(listOf("id", "name", "\"Full Name\""), complete("select users.| from users").texts())
        assertEquals(listOf("id", "user_id"), complete("select o.| from public.\"Orders\" AS o").texts())
        assertEquals(listOf("total", "id"), complete("select i.| from users u, sales.invoices i").texts())
        assertEquals(listOf("id", "name", "\"Full Name\""), complete("update users set x = 1 where users.|").texts())
        assertEquals(listOf("id", "name", "\"Full Name\""), complete("select u.| from a join users u on 1 = 1").texts())
    }

    @Test
    fun `schema qualifier gives its tables, unknown qualifier nothing`() {
        assertEquals(listOf("invoices"), complete("select * from sales.|").texts())
        assertEquals(emptyList(), complete("select * from nope.|").texts())
        assertEquals(listOf("total", "id"), complete("select sales.invoices.| from sales.invoices").texts())
    }

    @Test
    fun `unqualified order is columns, tables, schemas, keywords`() {
        val items = complete("select | from users").items
        assertEquals(
            listOf(CompletionKind.COLUMN, CompletionKind.TABLE, CompletionKind.SCHEMA, CompletionKind.KEYWORD),
            items.map { it.kind }.distinct(),
        )
        assertTrue(items.any { it.kind == CompletionKind.KEYWORD && it.label == "INSERT" })
        assertTrue(complete("select | from users").items.none { it.text == "total" })
    }

    @Test
    fun `quotes identifiers only when needed`() {
        assertEquals(
            listOf("users", "\"Orders\"", "\"order\""),
            complete("select * from |").items.filter { it.kind == CompletionKind.TABLE }.map { it.text },
        )
        val maria =
            complete(
                "select * from |",
                MARIADB,
            ).items.filter { it.kind == CompletionKind.TABLE }.map { it.text }
        assertEquals(listOf("users", "Orders", "`order`"), maria)
    }

    @Test
    fun `quoted prefix replaces the quote and quotes the result`() {
        val result = complete("select * from \"Or|")
        assertEquals(14, result.replaceFrom)
        assertEquals(listOf("\"Orders\"", "\"order\""), result.texts())
    }

    @Test
    fun `completion inside a closed quoted identifier replaces up to its end`() {
        val result = complete("select * from \"Or|ders\"")
        assertEquals(14, result.replaceFrom)
        assertEquals(22, result.replaceTo)
        assertEquals(listOf("\"Orders\"", "\"order\""), result.texts())
    }

    @Test
    fun `backtick prefix is replaced and quoted in MariaDB`() {
        val result = complete("select * from `Or|", MARIADB)
        assertEquals(14, result.replaceFrom)
        assertEquals(listOf("`Orders`", "`order`"), result.texts())
    }

    @Test
    fun `table names fold by kind and the schema of a reference is resolved`() {
        val users = listOf("id", "name", "\"Full Name\"")
        assertEquals(users, complete("select u.| from PUBLIC.users u").texts())
        assertEquals(users, complete("select u.| from \"public\".users u").texts())
        assertEquals(emptyList(), complete("select u.| from \"PUBLIC\".users u").texts())
        assertEquals(emptyList(), complete("select o.| from orders o").texts())
        assertEquals(listOf("id", "user_id"), complete("select o.| from \"Orders\" o").texts())
        assertEquals(listOf("id", "user_id"), complete("select orders.| from orders", MARIADB).texts())
        assertEquals(emptyList(), complete("select \"orders\".| from \"Orders\"").texts())
    }

    @ParameterizedTest
    @MethodSource("keywordCases")
    fun `keywords take the case of the prefix`(
        marked: String,
        expected: String,
    ) {
        val keywords = complete(marked).items.filter { it.kind == CompletionKind.KEYWORD }
        assertTrue(expected in keywords.map { it.text }, keywords.toString())
    }

    @Test
    fun `keywords differ per kind`() {
        assertTrue("RETURNING" in SqlKeywords.all(POSTGRESQL) && "ILIKE" in SqlKeywords.all(POSTGRESQL))
        assertTrue("ILIKE" !in SqlKeywords.all(MARIADB) && "REPLACE" in SqlKeywords.all(MARIADB))
        assertTrue(SqlKeywords.all(MARIADB).all { it == it.uppercase() })
    }

    @ParameterizedTest
    @MethodSource("emptyCases")
    fun `nothing inside strings and comments`(
        marked: String,
        kind: DatabaseKind,
    ) {
        val result = complete(marked, kind)
        assertEquals(emptyList(), result.items, marked)
        assertEquals(marked.indexOf('|'), result.replaceFrom)
    }

    @ParameterizedTest
    @MethodSource("autoOpenCases")
    fun `shouldAutoOpen`(
        marked: String,
        expected: Boolean,
    ) {
        assertEquals(
            expected,
            SqlCompletion.shouldAutoOpen(marked.replace("|", ""), marked.indexOf('|'), POSTGRESQL),
            marked,
        )
    }

    @Test
    fun `caps at 200 items and removes duplicates`() {
        val big =
            object : CompletionCatalog by catalog {
                override fun tables(schema: String?) = List(500) { "t$it" } + "t1"
            }
        val result = SqlCompletion.candidates("select * from t", 15, POSTGRESQL, big)
        assertEquals(200, result.items.size)
        assertEquals(result.items.size, result.texts().toSet().size)
    }

    companion object {
        @JvmStatic
        fun keywordCases() =
            listOf(
                Arguments.of("sel|", "select"),
                Arguments.of("SEL|", "SELECT"),
                Arguments.of("Sel|", "SELECT"),
            )

        @JvmStatic
        fun emptyCases() =
            listOf(
                Arguments.of("select 'se|", POSTGRESQL),
                Arguments.of("select 'a', 'se|' from t", POSTGRESQL),
                Arguments.of("select 1 -- se|", POSTGRESQL),
                Arguments.of("select /* se| */ 1", POSTGRESQL),
                Arguments.of("select 'se|", MARIADB),
                Arguments.of("select \"se|\"", MARIADB),
                Arguments.of("select 1 # se|", MARIADB),
            )

        @JvmStatic
        fun autoOpenCases() =
            listOf(
                Arguments.of("select u.|", true),
                Arguments.of("select us|", true),
                Arguments.of("select u|", false),
                Arguments.of("select * from t limit 10|", false),
                Arguments.of("select ${'$'}12|", false),
                Arguments.of("select a1|", true),
                Arguments.of("select _a|", true),
                Arguments.of("select |", false),
                Arguments.of("select 'a.|'", false),
                Arguments.of("select 'ab|'", false),
                Arguments.of("select 1 -- ab|", false),
                Arguments.of("select 1 /* a.| */", false),
                Arguments.of("|", false),
            )
    }
}
