package dev.sluide.databases.result

import dev.sluide.databases.connect.DatabaseKind
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.Types
import java.time.LocalDate
import kotlin.test.assertEquals

class ResultExportTest {
    private val cols =
        listOf(col("id", Types.INTEGER), col("t", label = "Text"), col("b", Types.BIT), col("d", Types.DATE))
    private val rows: List<List<Any?>> =
        listOf(
            listOf(1L, "plain", true, LocalDate.of(2024, 1, 2)),
            listOf(BigDecimal("2.50"), "a,\"q\"\nline", false, null),
            listOf(null, "it's a\\b|c", null, null),
        )

    private fun write(
        format: ExportFormat,
        kind: DatabaseKind = DatabaseKind.POSTGRESQL,
        table: String? = null,
    ) = ResultExport.write(format, kind, cols, rows, table)

    @Test
    fun `csv is rfc 4180 with crlf`() {
        assertEquals(
            "id,Text,b,d\r\n" +
                "1,plain,true,2024-01-02\r\n" +
                "2.50,\"a,\"\"q\"\"\nline\",false,\r\n" +
                ",it's a\\b|c,,\r\n",
            write(ExportFormat.CSV),
        )
    }

    @Test
    fun `tsv quotes tabs, newlines and quotes only`() {
        val out =
            ResultExport.write(
                ExportFormat.TSV,
                DatabaseKind.POSTGRESQL,
                listOf(col("a"), col("b")),
                listOf(listOf("x,y", "t\tab"), listOf("q\"", "n\nl")),
                null,
            )
        assertEquals("a\tb\nx,y\t\"t\tab\"\n\"q\"\"\"\t\"n\nl\"\n", out)
    }

    @Test
    fun `json keeps numbers and booleans native`() {
        val out =
            ResultExport.write(
                ExportFormat.JSON,
                DatabaseKind.POSTGRESQL,
                cols + col("x", Types.BLOB),
                listOf(
                    listOf(1L, "a\"\\\n\u0001", true, LocalDate.of(2024, 1, 2), byteArrayOf(10, -1)),
                    listOf(BigDecimal("2.50"), null, null, null, null),
                ),
                null,
            )
        assertEquals(
            "[\n" +
                "  {\"id\": 1, \"Text\": \"a\\\"\\\\\\n\\u0001\", \"b\": true, " +
                "\"d\": \"2024-01-02\", \"x\": \"0x0aff\"},\n" +
                "  {\"id\": 2.50, \"Text\": null, \"b\": null, \"d\": null, \"x\": null}\n" +
                "]\n",
            out,
        )
        assertEquals("[]\n", ResultExport.write(ExportFormat.JSON, DatabaseKind.MARIADB, cols, emptyList(), null))
    }

    @Test
    fun `sql inserts for postgresql`() {
        assertEquals(
            "INSERT INTO result (\"id\", \"Text\", \"b\", \"d\") VALUES (1, E'plain', TRUE, E'2024-01-02');\n" +
                "INSERT INTO result (\"id\", \"Text\", \"b\", \"d\") VALUES (2.50, E'a,\"q\"\nline', FALSE, NULL);\n" +
                "INSERT INTO result (\"id\", \"Text\", \"b\", \"d\") VALUES (NULL, E'it''s a\\\\b|c', NULL, NULL);\n",
            write(ExportFormat.SQL_INSERT),
        )
    }

    @Test
    fun `sql inserts for mariadb escape backslashes and use 1 and 0`() {
        assertEquals(
            "INSERT INTO `s`.`t` (`id`, `Text`, `b`, `d`) VALUES (1, 'plain', 1, '2024-01-02');\n" +
                "INSERT INTO `s`.`t` (`id`, `Text`, `b`, `d`) VALUES (2.50, 'a,\"q\"\nline', 0, NULL);\n" +
                "INSERT INTO `s`.`t` (`id`, `Text`, `b`, `d`) VALUES (NULL, 'it''s a\\\\b|c', NULL, NULL);\n",
            write(ExportFormat.SQL_INSERT, DatabaseKind.MARIADB, "`s`.`t`"),
        )
    }

    @Test
    fun `sql inserts write bytes per kind`() {
        val bytes = listOf(listOf<Any?>(byteArrayOf(0, -1)))
        val c = listOf(col("x", Types.BLOB))
        assertEquals(
            "INSERT INTO result (\"x\") VALUES (E'\\\\x00ff'::bytea);\n",
            ResultExport.write(ExportFormat.SQL_INSERT, DatabaseKind.POSTGRESQL, c, bytes, null),
        )
        assertEquals(
            "INSERT INTO result (`x`) VALUES (X'00ff');\n",
            ResultExport.write(ExportFormat.SQL_INSERT, DatabaseKind.MARIADB, c, bytes, null),
        )
    }

    @Test
    fun `markdown escapes pipes and newlines`() {
        assertEquals(
            "| id | Text | b | d |\n| --- | --- | --- | --- |\n" +
                "| 1 | plain | true | 2024-01-02 |\n" +
                "| 2.50 | a,\"q\"<br>line | false |  |\n" +
                "|  | it's a\\\\b\\|c |  |  |\n",
            write(ExportFormat.MARKDOWN),
        )
    }
}
