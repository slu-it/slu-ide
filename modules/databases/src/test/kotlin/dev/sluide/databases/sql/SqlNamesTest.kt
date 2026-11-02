package dev.sluide.databases.sql

import dev.sluide.databases.connect.DatabaseKind
import kotlin.test.Test
import kotlin.test.assertEquals

class SqlNamesTest {
    @Test
    fun `quotes with the kind's quote character and doubles inner quotes`() {
        assertEquals("\"a\"\"b\"", SqlNames.quote(DatabaseKind.POSTGRESQL, "a\"b"))
        assertEquals("`a``b`", SqlNames.quote(DatabaseKind.MARIADB, "a`b"))
    }

    @Test
    fun `qualifies with a schema and leaves it out without one`() {
        assertEquals("\"public\".\"t\"", SqlNames.qualified(DatabaseKind.POSTGRESQL, "public", "t"))
        assertEquals("`t`", SqlNames.qualified(DatabaseKind.MARIADB, null, "t"))
    }

    @Test
    fun `system schemas include prefixed ones`() {
        assert(DatabaseKind.POSTGRESQL.isSystemSchema("pg_toast_temp_1"))
        assert(!DatabaseKind.POSTGRESQL.isSystemSchema("public"))
        assert(DatabaseKind.MARIADB.isSystemSchema("performance_schema"))
    }
}
