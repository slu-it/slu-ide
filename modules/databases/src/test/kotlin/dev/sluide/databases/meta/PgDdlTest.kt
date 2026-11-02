package dev.sluide.databases.meta

import kotlin.test.Test
import kotlin.test.assertEquals

class PgDdlTest {
    private val indexDef = "CREATE INDEX note_idx ON public.\"order\" (note)"
    private val triggerDef = "CREATE TRIGGER trg BEFORE INSERT ON public.\"order\" FOR EACH ROW EXECUTE FUNCTION f()"

    private val rows =
        PgSchemaRows(
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
        assertEquals("CREATE TYPE \"public\".\"mood\" AS ENUM ('sad', 'it''s');\n", pgEnumDdl(e, listOf("sad", "it's")))
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
}
