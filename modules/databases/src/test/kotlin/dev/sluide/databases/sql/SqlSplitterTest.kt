package dev.sluide.databases.sql

import dev.sluide.databases.connect.DatabaseKind
import dev.sluide.databases.connect.DatabaseKind.MARIADB
import dev.sluide.databases.connect.DatabaseKind.POSTGRESQL
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.assertEquals

class SqlSplitterTest {
    private fun texts(
        sql: String,
        kind: DatabaseKind,
    ) = SqlSplitter.split(sql, kind).map { it.text }

    @Test
    fun `splits at semicolons with exact ranges`() {
        val sql = "  select 1 ;\n select 2;select 3  "
        assertEquals(
            listOf(
                SqlStatement("select 1", 2, 10),
                SqlStatement("select 2", 14, 22),
                SqlStatement("select 3", 23, 31),
            ),
            SqlSplitter.split(sql, POSTGRESQL),
        )
    }

    @Test
    fun `drops blank and comment-only pieces`() {
        assertEquals(
            listOf("select 1"),
            texts(";; -- nothing\n; /* x */ ; select 1; ", POSTGRESQL),
        )
        assertEquals(emptyList(), texts("   ", MARIADB))
    }

    @Test
    fun `keeps a piece that has a comment and a statement`() {
        assertEquals(listOf("-- c\nselect 1"), texts("-- c\nselect 1;", POSTGRESQL))
    }

    @ParameterizedTest
    @MethodSource("quotingCases")
    fun `quoting and comment rules per kind`(
        kind: DatabaseKind,
        sql: String,
        count: Int,
    ) {
        assertEquals(count, SqlSplitter.split(sql, kind).size, sql)
    }

    @Test
    fun `unterminated string and comment run to the end`() {
        assertEquals(listOf("select 'a; b"), texts("select 'a; b", POSTGRESQL))
        assertEquals(listOf("select 1 /* a; b"), texts("select 1 /* a; b", MARIADB))
    }

    @Test
    fun `delimiter is plain text`() {
        assertEquals(listOf("DELIMITER //", "select 1 //"), texts("DELIMITER //;select 1 //", MARIADB))
    }

    @ParameterizedTest
    @MethodSource("queryCases")
    fun `isQuery`(
        sql: String,
        expected: Boolean,
    ) {
        assertEquals(expected, SqlSplitter.isQuery(sql, POSTGRESQL), sql)
    }

    @Test
    fun `isQuery reads MariaDB comments and strings by MariaDB rules`() {
        assertEquals(true, SqlSplitter.isQuery("# c\nselect 1", MARIADB))
        assertEquals(true, SqlSplitter.isQuery("/* a /* b */ select 1 */ delete from t", MARIADB))
        assertEquals(false, SqlSplitter.isQuery("select 'a\\\\' into x", MARIADB))
        assertEquals(false, SqlSplitter.isQuery("select 1 into outfile 'x'", MARIADB))
        assertEquals(false, SqlSplitter.isQuery("select * from t lock in share mode", MARIADB))
        assertEquals(true, SqlSplitter.isQuery("/*M!100100 SELECT 1 */", MARIADB))
        assertEquals(true, SqlSplitter.isQuery("/*!40101 select 1 */", MARIADB))
        assertEquals(false, SqlSplitter.isQuery("/*!40101 select 1 into outfile 'x' */", MARIADB))
    }

    @Test
    fun `executable comments are code and keep their piece`() {
        assertEquals(listOf("/*!40101 SET NAMES utf8 */"), texts("/*!40101 SET NAMES utf8 */;", MARIADB))
        assertEquals(listOf("/*M!100100 SET x = 1 */"), texts("/*M!100100 SET x = 1 */;", MARIADB))
        assertEquals(emptyList(), texts("/* SET NAMES utf8 */;", MARIADB))
        assertEquals(2, texts("/*!40101 select 1; select 2 */", MARIADB).size)
    }

    companion object {
        private fun q(
            kind: DatabaseKind,
            sql: String,
            count: Int,
        ) = Arguments.of(kind, sql, count)

        @JvmStatic
        fun quotingCases() =
            listOf(
                q(POSTGRESQL, "select 'a;b', 2", 1),
                q(POSTGRESQL, "select 'it''s;'; select 2", 2),
                q(POSTGRESQL, "select \"a;b\"; select 2", 2),
                q(POSTGRESQL, "select \"a\"\"; b\"; select 2", 2),
                q(POSTGRESQL, "select E'a\\';b'; select 2", 2),
                q(POSTGRESQL, "select 'a\\'; select 2", 2),
                q(POSTGRESQL, "select 1 -- a;b\n; select 2", 2),
                q(POSTGRESQL, "select /* a; /* b; */ c; */ 1; select 2", 2),
                q(POSTGRESQL, "select `a;b`; select 2", 3),
                q(POSTGRESQL, "select 1 # a;b", 2),
                q(POSTGRESQL, "do $$ begin a; b; end $$; select 2", 2),
                q(POSTGRESQL, "do ${'$'}t${'$'} a; $$ b; ${'$'}t${'$'}; select 2", 2),
                q(POSTGRESQL, "select $1; select 2", 2),
                q(MARIADB, "select 'a;b'", 1),
                q(MARIADB, "select 'a\\';b'; select 2", 2),
                q(MARIADB, "select 'a\\\\'; select 2", 2),
                q(MARIADB, "select 'it''s;'; select 2", 2),
                q(MARIADB, "select `a;b`; select 2", 2),
                q(MARIADB, "select \"a;b\"; select 2", 2),
                q(MARIADB, "select 1 -- a;b\n; select 2", 2),
                q(MARIADB, "select 1 --a;b", 2),
                q(MARIADB, "select 1 # a;b\n; select 2", 2),
                q(MARIADB, "select /* a; */ 1; select 2", 2),
                q(MARIADB, "select /* a; /* b; */ c; */ 1", 2),
            )

        @JvmStatic
        fun queryCases() =
            listOf(
                "select 1" to true,
                "  SELECT 1" to true,
                "-- c\nselect 1" to true,
                "/* c */ select 1" to true,
                "(select 1) union (select 2)" to true,
                "((select 1))" to true,
                "show tables" to true,
                "values (1)" to true,
                "table t" to true,
                "describe t" to true,
                "desc t" to true,
                "with a as (select 1) select * from a" to true,
                "with recursive a(x) as (select 1) select * from a" to true,
                "with a as (select 1) delete from t" to false,
                "with a as (select 1) insert into t select * from a" to false,
                "with d as (delete from t returning *) select * from d" to false,
                "with a as (select 1) select * into t2 from a" to false,
                "explain select 1" to true,
                "explain update t set a = 1" to true,
                "explain analyze select 1" to false,
                "explain (analyze, verbose) select 1" to false,
                "explain (analyse) select 1" to false,
                "explain analyse select 1" to false,
                "explain analyze update t set a = 1" to false,
                "explain analyze with d as (delete from t returning *) select * from d" to false,
                "explain (analyze) delete from t" to false,
                "select * into t2 from t" to false,
                "(select * into t2 from t)" to false,
                "(select 1 into t) union select 2" to false,
                "select * from t for update" to false,
                "select * from t for share" to false,
                "select * from t for no key update" to false,
                "select * from t for key share" to false,
                "select substring(a for 2) from t" to true,
                "/* old /* note */ select */ delete from t" to false,
                "select ${'$'}${'$'} into ${'$'}${'$'}" to true,
                "insert into t values (1)" to false,
                "update t set a = 1" to false,
                "delete from t" to false,
                "create table t(a int)" to false,
                "" to false,
                "-- only comment" to false,
            ).map { Arguments.of(it.first, it.second) }
    }
}
