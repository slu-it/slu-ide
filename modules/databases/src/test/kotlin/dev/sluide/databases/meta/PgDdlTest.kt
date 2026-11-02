package dev.sluide.databases.meta

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PgDdlTest {
    private val indexDef = "CREATE INDEX note_idx ON public.\"order\" (note)"
    private val triggerDef = "CREATE TRIGGER trg BEFORE INSERT ON public.\"order\" FOR EACH ROW EXECUTE FUNCTION f()"

    private val rows =
        PgSchemaRows(
            relations = listOf(mapOf("name" to "order", "kind" to "r"), mapOf("name" to "other", "kind" to "r")),
            columns =
                listOf(
                    mapOf("tbl" to "order", "name" to "id", "type" to "integer", "notnull" to true, "identity" to "a"),
                    mapOf("tbl" to "order", "name" to "note", "type" to "text", "dflt" to "'x'::text"),
                    mapOf("tbl" to "other", "name" to "zzz", "type" to "text", "notnull" to false),
                ),
            constraints =
                listOf(
                    mapOf("tbl" to "order", "name" to "order_pkey", "type" to "p", "def" to "PRIMARY KEY (id)"),
                ),
            indexes =
                listOf(
                    mapOf("tbl" to "order", "name" to "order_pkey", "backs" to true, "def" to "CREATE UNIQUE INDEX p"),
                    mapOf("tbl" to "order", "name" to "note_idx", "backs" to false, "def" to indexDef),
                ),
            triggers =
                listOf(mapOf("tbl" to "order", "name" to "trg", "def" to triggerDef)),
        )

    @Test
    fun `table ddl is create table then indexes without a constraint then triggers`() {
        val expected =
            """
            CREATE TABLE "public"."order" (
                "id" integer GENERATED ALWAYS AS IDENTITY NOT NULL,
                "note" text DEFAULT 'x'::text,
                CONSTRAINT "order_pkey" PRIMARY KEY (id)
            );

            CREATE INDEX note_idx ON public."order" (note);

            CREATE TRIGGER trg BEFORE INSERT ON public."order" FOR EACH ROW EXECUTE FUNCTION f();

            """.trimIndent()
        assertEquals(expected, pgTableDdl("public", "order", rows))
    }

    @Test
    fun `child ddl of constraint column and index`() {
        fun obj(
            kind: ObjectKind,
            name: String,
        ) = DbObject(kind, "public", name, "order")
        assertEquals(
            "ALTER TABLE \"public\".\"order\" ADD CONSTRAINT \"order_pkey\" PRIMARY KEY (id);\n",
            pgTableChildDdl(obj(ObjectKind.PRIMARY_KEY, "order_pkey"), rows),
        )
        assertEquals(
            "ALTER TABLE \"public\".\"order\" ADD COLUMN \"note\" text DEFAULT 'x'::text;\n",
            pgTableChildDdl(obj(ObjectKind.COLUMN, "note"), rows),
        )
        assertEquals(
            "CREATE INDEX note_idx ON public.\"order\" (note);\n",
            pgTableChildDdl(obj(ObjectKind.INDEX, "note_idx"), rows),
        )
    }

    @Test
    fun `views end in a semicolon and a newline`() {
        val view = DbObject(ObjectKind.VIEW, "public", "v")
        assertEquals("CREATE OR REPLACE VIEW \"public\".\"v\" AS\nSELECT 1;\n", pgViewDdl(view, " SELECT 1;\n"))
        val mv = DbObject(ObjectKind.MATERIALIZED_VIEW, "public", "m")
        assertEquals("CREATE MATERIALIZED VIEW \"public\".\"m\" AS\nSELECT 1\nWITH DATA;\n", pgViewDdl(mv, "SELECT 1;"))
    }

    @Test
    fun `enum labels are escaped and composite types list their attributes`() {
        val e = DbObject(ObjectKind.ENUM, "public", "mood")
        assertEquals(
            "CREATE TYPE \"public\".\"mood\" AS ENUM ('sad', 'it''s');\n",
            pgEnumDdl(e, listOf("'sad'", "'it''s'")),
        )
        val c = DbObject(ObjectKind.COMPOSITE_TYPE, "public", "pair")
        assertEquals(
            "CREATE TYPE \"public\".\"pair\" AS (\n    \"a\" integer,\n    \"b\" text\n);\n",
            pgCompositeDdl(
                c,
                listOf(mapOf("name" to "a", "type" to "integer"), mapOf("name" to "b", "type" to "text")),
            ),
        )
    }

    @Test
    fun `domain ddl has base default not null and constraints`() {
        val d = DbObject(ObjectKind.DOMAIN, "public", "pos")
        val domain = mapOf("base" to "integer", "notnull" to true, "dflt" to "1")
        assertEquals(
            "CREATE DOMAIN \"public\".\"pos\" AS integer\n    DEFAULT 1\n    NOT NULL\n" +
                "    CONSTRAINT \"pos_check\" CHECK (VALUE > 0);\n",
            pgDomainDdl(d, domain, listOf(mapOf("name" to "pos_check", "def" to "CHECK (VALUE > 0)"))),
        )
    }

    private fun col(
        name: String,
        type: String,
        vararg extra: Pair<String, Any?>,
    ): Row = mapOf("tbl" to "t", "name" to name, "type" to type) + extra

    @Test
    fun `partitioned table has partition by and a partition is partition of its parent`() {
        val rows =
            PgSchemaRows(
                relations =
                    listOf(
                        mapOf("name" to "m", "kind" to "p", "partkey" to "RANGE (d)"),
                        mapOf(
                            "name" to "m1",
                            "kind" to "r",
                            "ispart" to true,
                            "pschema" to "s",
                            "parent" to "m",
                            "bound" to "FOR VALUES FROM (1) TO (10)",
                        ),
                    ),
                columns = listOf(mapOf("tbl" to "m", "name" to "d", "type" to "integer", "notnull" to true)),
                indexes = listOf(mapOf("tbl" to "m1", "name" to "m1_idx", "backs" to false, "def" to "CREATE INDEX x")),
            )
        assertEquals(
            "CREATE TABLE \"s\".\"m\" (\n    \"d\" integer NOT NULL\n)\nPARTITION BY RANGE (d);\n",
            pgTableDdl("s", "m", rows),
        )
        assertEquals(
            "CREATE TABLE \"s\".\"m1\" PARTITION OF \"s\".\"m\" FOR VALUES FROM (1) TO (10);\n",
            pgTableDdl("s", "m1", rows),
        )
    }

    @Test
    fun `foreign table has server and options`() {
        val rows =
            PgSchemaRows(
                relations =
                    listOf(
                        mapOf(
                            "name" to "t",
                            "kind" to "f",
                            "fserver" to "files",
                            "foptions" to "filename '/x', format 'csv'",
                        ),
                    ),
                columns = listOf(col("a", "integer")),
            )
        assertEquals(
            "CREATE FOREIGN TABLE \"s\".\"t\" (\n    \"a\" integer\n)\nSERVER \"files\"\n" +
                "OPTIONS (filename '/x', format 'csv');\n",
            pgTableDdl("s", "t", rows),
        )
    }

    @Test
    fun `owned sequences come before the table and are attached after it`() {
        val seq =
            mapOf(
                "tbl" to "t",
                "col" to "id",
                "seqschema" to "s",
                "seq" to "t_id_seq",
                "type" to "integer",
                "start_value" to 1,
                "min_value" to 1,
                "max_value" to 2147483647,
                "increment_by" to 1,
                "cache_size" to 1,
                "cycle" to false,
            )
        val rows =
            PgSchemaRows(
                relations = listOf(mapOf("name" to "t", "kind" to "r")),
                columns = listOf(col("id", "integer", "dflt" to "nextval('s.t_id_seq'::regclass)")),
                ownedSequences = listOf(seq),
            )
        val ddl = pgTableDdl("s", "t", rows)
        val parts = ddl.split("\n\n")
        assertEquals(3, parts.size, ddl)
        assertTrue(parts[0].startsWith("CREATE SEQUENCE \"s\".\"t_id_seq\"") && parts[0].endsWith("NO CYCLE;"), ddl)
        assertTrue(parts[1].startsWith("CREATE TABLE \"s\".\"t\""), ddl)
        assertEquals("ALTER SEQUENCE \"s\".\"t_id_seq\" OWNED BY \"s\".\"t\".\"id\";\n", parts[2])
    }

    @ParameterizedTest
    @CsvSource(
        "s,'(a * 2)',,\"c\" integer GENERATED ALWAYS AS ((a * 2)) STORED",
        "v,'(a * 2)',,\"c\" integer GENERATED ALWAYS AS ((a * 2))",
        ",,d,\"c\" integer GENERATED BY DEFAULT AS IDENTITY",
        ",,a,\"c\" integer GENERATED ALWAYS AS IDENTITY",
    )
    fun `column line of generated and identity columns`(
        generated: String?,
        dflt: String?,
        identity: String?,
        expected: String,
    ) {
        assertEquals(
            expected,
            pgColumnLine(
                col(
                    "c",
                    "integer",
                    "generated" to generated,
                    "dflt" to dflt,
                    "identity" to identity,
                ),
            ),
        )
    }

    @Test
    fun `materialized view ddl lists its indexes`() {
        val mv = DbObject(ObjectKind.MATERIALIZED_VIEW, "s", "m")
        val rows =
            PgSchemaRows(
                indexes =
                    listOf(
                        mapOf(
                            "tbl" to "m",
                            "name" to "i",
                            "backs" to false,
                            "def" to "CREATE INDEX i ON s.m (a)",
                        ),
                    ),
            )
        assertEquals(
            "CREATE MATERIALIZED VIEW \"s\".\"m\" AS\nSELECT 1\nWITH DATA;\n\nCREATE INDEX i ON s.m (a);\n",
            pgViewDdl(mv, "SELECT 1", rows),
        )
    }

    @Test
    fun `a dropped object gives empty ddl`() {
        assertEquals("", pgTableDdl("s", "gone", PgSchemaRows()))
        assertEquals("", pgViewDdl(DbObject(ObjectKind.VIEW, "s", "v"), ""))
        assertEquals("", pgEnumDdl(DbObject(ObjectKind.ENUM, "s", "e"), emptyList()))
        assertEquals("", pgDomainDdl(DbObject(ObjectKind.DOMAIN, "s", "d"), null, emptyList()))
        assertEquals("", pgCompositeDdl(DbObject(ObjectKind.COMPOSITE_TYPE, "s", "c"), emptyList()))
        assertEquals("", pgTableChildDdl(DbObject(ObjectKind.COLUMN, "s", "a", "gone"), PgSchemaRows()).orEmpty())
    }
}
