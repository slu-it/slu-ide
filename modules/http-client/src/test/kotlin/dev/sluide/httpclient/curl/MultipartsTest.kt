package dev.sluide.httpclient.curl

import dev.sluide.httpclient.parse.BodyPart
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [Multiparts.parts]: a `.http` multipart body (RFC 7578 `multipart/form-data`, delimiters and CRLF as RFC 2046 5.1
 * defines them) read back into curl's `-F` fields.
 */
internal class MultipartsTest {
    private val type = listOf(CONTENT_TYPE to "multipart/form-data; boundary=b")

    private fun text(vararg lines: String) = BodyPart.Text(lines.joinToString("\n"))

    private fun file(path: String) = BodyPart.FileRef(path, 1)

    private fun parts(
        vararg body: BodyPart,
        headers: List<Pair<String, String>> = type,
    ) = Multiparts.parts(body.toList(), headers)

    private fun field(
        name: String,
        value: String,
    ) = listOf("--b", "Content-Disposition: form-data; name=\"$name\"", "", value)

    @Test
    fun `reads a field, a file and a text field read from a file`() {
        val result =
            parts(
                text("--b", "Content-Disposition: form-data; name=\"f\"", "", "v", "--b"),
                text("Content-Disposition: form-data; name=\"g\"; filename=\"n.txt\"", "Content-Type: text/x-y", ""),
                file("./d/f.txt"),
                text("--b", "Content-Disposition: form-data; name=\"h\"", ""),
                file("t.txt"),
                text("--b--"),
            )

        assertEquals(
            listOf(
                FormPart("f", FormContent.Value("v")),
                FormPart("g", FormContent.File("d/f.txt", "n.txt"), "text/x-y"),
                FormPart("h", FormContent.FileText("t.txt")),
            ),
            result,
        )
    }

    @Test
    fun `reads the parts one after the other, each with its own content`() {
        val result =
            parts(text(*field("a", "1").toTypedArray(), *field("b", "2").toTypedArray(), "--b--"))

        assertEquals(listOf(FormPart("a", FormContent.Value("1")), FormPart("b", FormContent.Value("2"))), result)
    }

    @Test
    fun `takes the boundary from the last content type, quoted or not, in any case and before other parameters`() {
        val body = text(*field("a", "1").toTypedArray(), "--b--")
        val expected = listOf(FormPart("a", FormContent.Value("1")))

        assertEquals(
            expected,
            parts(
                body,
                headers = listOf(CONTENT_TYPE to "x", "content-type" to "multipart/form-data; BOUNDARY = \"b\""),
            ),
        )
        assertEquals(expected, parts(body, headers = listOf(CONTENT_TYPE to "multipart/form-data; boundary=b; x=y")))
        assertNull(parts(body, headers = listOf(CONTENT_TYPE to "multipart/form-data; boundary=other")))
        assertNull(parts(body, headers = listOf(CONTENT_TYPE to "multipart/form-data")))
        assertNull(parts(body, headers = emptyList()))
    }

    @Test
    fun `a boundary with a blank is matched whole (RFC 2046 5-1-1 allows a space inside)`() {
        val headers = listOf(CONTENT_TYPE to "multipart/form-data; boundary=\"a b\"")
        val body =
            text("--a b", "Content-Disposition: form-data; name=\"f\"", "", "--b", "--a b--")

        assertEquals(listOf(FormPart("f", FormContent.Value("--b"))), parts(body, headers = headers))
    }

    @Test
    fun `without a boundary no body is read, not even one with delimiters named after the missing value`() {
        val body = text(*field("a", "1").toTypedArray().also { it[0] = "--null" }, "--null--")

        assertNull(parts(body, headers = listOf(CONTENT_TYPE to "multipart/form-data")))
        assertNull(parts(body, headers = emptyList()))
    }

    @Test
    fun `takes CRLF line ends and keeps the line breaks inside a value as line feeds`() {
        val body =
            BodyPart.Text(
                "--b\r\nContent-Disposition: form-data; name=\"f\"\r\n\r\nline 1\r\nline 2\r\n\r\n--b--\r\n",
            )

        assertEquals(listOf(FormPart("f", FormContent.Value("line 1\nline 2\n"))), parts(body))
    }

    @Test
    fun `a value can be empty or hold lines that only start like the boundary`() {
        val result =
            parts(
                text(*field("e", "").toTypedArray(), *field("d", "--bx").toTypedArray(), "--b--"),
            )

        assertEquals(listOf(FormPart("e", FormContent.Value("")), FormPart("d", FormContent.Value("--bx"))), result)
    }

    @Test
    fun `a file whose name looks like the delimiter is content`() {
        val result =
            parts(
                text("--b", "Content-Disposition: form-data; name=\"f\"", ""),
                file("--b"),
                text("--b--"),
            )

        assertEquals(listOf(FormPart("f", FormContent.FileText("--b"))), result)
    }

    @Test
    fun `a file name that is the file's own name is not repeated, another one is kept`() {
        val head = "Content-Disposition: form-data; name=\"f\"; filename="
        val same = parts(text("--b", "${head}\"f.txt\"", ""), file("dir/f.txt"), text("--b--"))
        val windows = parts(text("--b", "${head}\"f.txt\"", ""), file("dir\\f.txt"), text("--b--"))
        val other = parts(text("--b", "${head}\"g.txt\"", ""), file("dir/f.txt"), text("--b--"))

        assertEquals(listOf(FormPart("f", FormContent.File("dir/f.txt"))), same)
        assertEquals(listOf(FormPart("f", FormContent.File("dir\\f.txt"))), windows)
        assertEquals(listOf(FormPart("f", FormContent.File("dir/f.txt", "g.txt"))), other)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dispositions")
    fun `reads the name and the file name from the content disposition`(
        disposition: String,
        expected: FormPart?,
    ) {
        val result = parts(text("--b", disposition, "", "v", "--b--"))

        assertEquals(expected?.let { listOf(it) }, result)
    }

    @Test
    fun `reads a file name in any case of the header and takes the content type of the part`() {
        val result =
            parts(
                text(
                    "--b",
                    "content-type:  text/plain ",
                    "CONTENT-DISPOSITION :  FORM-DATA ; NAME=\"f\"",
                    "",
                    "v",
                    "--b--",
                ),
            )

        assertEquals(listOf(FormPart("f", FormContent.Value("v"), "text/plain")), result)
    }

    @Test
    fun `a quoted name keeps a semicolon and the percent-encoded quotes and line breaks of RFC 7578 as written`() {
        // RFC 7578 4.2 percent-encodes them; read as written, a quoted value ends at the next quote.
        val hostile = "Content-Disposition: form-data; name=\"a; b\""

        assertEquals(
            listOf(FormPart("a; b", FormContent.Value("v"))),
            parts(text("--b", hostile, "", "v", "--b--")),
        )
        assertEquals(
            listOf(FormPart("a%0D%0A--b%22", FormContent.Value("v"))),
            parts(text("--b", "Content-Disposition: form-data; name=\"a%0D%0A--b%22\"", "", "v", "--b--")),
        )
    }

    @Test
    fun `keeps a name and a value with non-ASCII characters as they are`() {
        val result = parts(text(*field("nämé-日本", "wert ü").toTypedArray(), "--b--"))

        assertEquals(listOf(FormPart("nämé-日本", FormContent.Value("wert ü"))), result)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("notFields")
    fun `a body that is no list of fields is not read`(
        name: String,
        body: List<BodyPart>,
    ) {
        assertNull(Multiparts.parts(body, type), name)
    }

    @Test
    fun `what follows the closing delimiter may only be blank`() {
        val head = text(*field("a", "1").toTypedArray(), "--b--")
        val expected = listOf(FormPart("a", FormContent.Value("1")))

        assertEquals(expected, parts(BodyPart.Text(head.text + "\n\n  \n")))
        assertNull(parts(BodyPart.Text(head.text + "\nepilogue")))
        assertNull(parts(head, file("x.txt")))
    }

    companion object {
        private const val NAME_START = "Content-Disposition: form-data; name="
        private const val NAME = "Content-Disposition: form-data; name=\"f\""

        @JvmStatic
        fun dispositions() =
            listOf(
                Arguments.of("Content-Disposition: form-data; name=\"f\"", FormPart("f", FormContent.Value("v"))),
                Arguments.of("Content-Disposition: form-data; name=f", FormPart("f", FormContent.Value("v"))),
                Arguments.of("Content-Disposition: form-data; name = f ;", FormPart("f", FormContent.Value("v"))),
                Arguments.of("Content-Disposition: form-data; name=\"\"", FormPart("", FormContent.Value("v"))),
                Arguments.of("Content-Disposition: form-data; name=\"a b\"", FormPart("a b", FormContent.Value("v"))),
                Arguments.of("Content-Disposition: form-data; Name=\"f\"", FormPart("f", FormContent.Value("v"))),
                Arguments.of("Content-Disposition: form-data", null),
                Arguments.of("Content-Disposition: form-data; filename=\"f\"", null),
                Arguments.of("Content-Disposition: attachment; name=\"f\"", null),
                Arguments.of("Content-Disposition: ; name=\"f\"", null),
                Arguments.of("Content-Disposition: form-data-x; name=\"f\"", null),
                Arguments.of("Content-Disposition: form-data; name=\"f\"; filename=\"a.txt\"", null),
            )

        @JvmStatic
        fun notFields(): List<Arguments> {
            val one = listOf("--b", NAME, "", "v")

            fun lines(vararg l: String) = listOf<BodyPart>(BodyPart.Text(l.joinToString("\n")))
            return listOf(
                Arguments.of("an empty body", emptyList<BodyPart>()),
                Arguments.of("text before the first delimiter", lines("x", *one.toTypedArray(), "--b--")),
                Arguments.of("a blank line before the first delimiter", lines("", *one.toTypedArray(), "--b--")),
                Arguments.of("no closing delimiter", lines(*one.toTypedArray())),
                Arguments.of("no closing delimiter after a part", lines(*one.toTypedArray(), "--b")),
                Arguments.of("a delimiter that is longer", lines(*one.toTypedArray(), "--bb--")),
                Arguments.of(
                    "a part that starts with its header instead of a delimiter",
                    lines("junk", NAME, "", "v", "--b--"),
                ),
                Arguments.of(
                    "a file where the headers end",
                    listOf(BodyPart.Text("--b"), BodyPart.FileRef("f", 1), BodyPart.Text("\nv\n--b--")),
                ),
                Arguments.of(
                    "a file between the headers",
                    listOf(BodyPart.Text("--b\n$NAME"), BodyPart.FileRef("f", 1), BodyPart.Text("\nv\n--b--")),
                ),
                Arguments.of("a part without the blank line", lines("--b", NAME, "--b--")),
                Arguments.of("a part with no line at all", lines("--b", "--b--")),
                Arguments.of("a header that is none", lines("--b", NAME, "no colon", "", "v", "--b--")),
                Arguments.of("another header", lines("--b", NAME, "X-Y: 1", "", "v", "--b--")),
                Arguments.of("a part without a name", lines("--b", "Content-Type: a/b", "", "v", "--b--")),
                Arguments.of("a file in the headers", listOf(BodyPart.Text("--b\n$NAME"), BodyPart.FileRef("f", 1))),
                Arguments.of(
                    "text and a file in one field",
                    listOf(BodyPart.Text("--b\n$NAME\n\nv"), BodyPart.FileRef("f", 1), BodyPart.Text("--b--")),
                ),
                Arguments.of(
                    "two files in one field",
                    listOf(
                        BodyPart.Text("--b\n$NAME\n"),
                        BodyPart.FileRef("f", 1),
                        BodyPart.FileRef("g", 2),
                        BodyPart.Text("--b--"),
                    ),
                ),
                Arguments.of(
                    "a file name on a field with text",
                    lines("--b", "$NAME; filename=\"a\"", "", "v", "--b--"),
                ),
                // curl has no escape for `=` in a -F name: `-F a=b=v` sends the field a with the value b=v.
                Arguments.of("a name with an equals sign", lines("--b", "$NAME_START\"a=b\"", "", "v", "--b--")),
                Arguments.of("a second part that is bad", lines(*one.toTypedArray(), "--b", "X: 1", "", "--b--")),
            )
        }
    }
}
