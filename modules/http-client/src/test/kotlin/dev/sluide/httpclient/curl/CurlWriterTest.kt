package dev.sluide.httpclient.curl

import dev.sluide.converting.ReadResult
import dev.sluide.converting.WriteResult
import dev.sluide.httpclient.parse.HttpVersion
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [CurlWriter]: the options a request becomes, the quoting per shell, and that what is written reads back as the same
 * words and requests.
 *
 * What the options do was checked against curl 8.14.1 and a local echo server: `-I` and `-X HEAD` send HEAD, `-X POST`
 * without a body sends an empty POST, `-d a -X GET` sends a GET with the body, `-d` adds the form content type,
 * `-d @f` sends the file's content, `--data-raw @f` the text `@f`, `-H 'X;'` an empty header, `-F 'g=@f;type=t;
 * filename=n'` a file part with that type and name, `--form-string 'f=@x;y'` the text as it is, `-F 'f=<f'` the
 * file's content as a value, `-u 'a b:c d'` a Basic header.
 * The quoted forms of the `-F` rows were sent to the same server: `-F 'p=@"a,b\"c\\d.txt";type=t/x;filename="x;y"'`,
 * `--form-string 'l="x"'`, `--form-string 'm= x'`, `-F 'q=<"a;b.txt"'` and `-F 'o=@"a;b.txt"'` all arrived as written.
 */
internal class CurlWriterTest {
    private fun write(
        vararg requests: CurlRequest,
        shell: Shell = Shell.POSIX,
    ) = CurlWriter.write(requests.toList(), shell) as WriteResult.Written

    private val continued = " \\\n  "

    private fun posix(vararg lines: String) = lines.joinToString(continued) + "\n"

    @ParameterizedTest(name = "{0}")
    @MethodSource("requests")
    fun `writes a request as the options curl needs`(
        name: String,
        request: CurlRequest,
        lines: List<String>,
    ) {
        assertEquals(posix(*lines.toTypedArray()), write(request).text, name)
    }

    @Test
    fun `writes no command for no request and separates several with a blank line`() {
        assertEquals("\n", write().text)
        assertEquals(
            "curl a\n\ncurl b\n",
            write(CurlRequest("a"), CurlRequest("b")).text,
        )
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Shell::class)
    fun `continues lines and starts the program as the shell wants it`(shell: Shell) {
        val program = if (shell == Shell.POWERSHELL) "curl.exe" else "curl"
        val continuation = mapOf(Shell.POSIX to "\\", Shell.CMD to "^", Shell.POWERSHELL to "`").getValue(shell)
        val comment = if (shell == Shell.CMD) "REM " else "# "

        assertEquals(
            "${comment}a b\n$program x $continuation\n  -L\n",
            write(CurlRequest("x", followRedirects = true, name = "a\nb"), shell = shell).text,
        )
    }

    @Test
    fun `PowerShell 5_1 drops double quotes, which is noted once for any number of requests`() {
        val quoted = CurlRequest("x", headers = listOf("A" to "say \"hi\""))

        val twice = write(quoted, quoted, shell = Shell.POWERSHELL)

        assertEquals(
            listOf(
                "Windows PowerShell 5.1 drops double quotes in the arguments of curl.exe; PowerShell 7.3 keeps them",
            ),
            twice.notes,
        )
        // Single quotes keep everything else and need no note; other shells quote differently.
        assertEquals(
            emptyList(),
            write(CurlRequest("x", headers = listOf("A" to "it's")), shell = Shell.POWERSHELL).notes,
        )
        assertEquals(emptyList(), write(quoted, shell = Shell.POSIX).notes)
        assertEquals(emptyList(), write(quoted, shell = Shell.CMD).notes)
    }

    @Test
    fun `notes the type a --form-string field cannot carry, once per field`() {
        val request =
            CurlRequest(
                "x",
                method = "POST",
                body =
                    RequestBody.Multipart(
                        listOf(
                            FormPart("a", FormContent.Value("@x"), "text/plain"),
                            FormPart("a", FormContent.Value("<x"), "text/plain"),
                            FormPart("b", FormContent.Value("y;z"), "text/plain"),
                            FormPart("c", FormContent.Value("@x")),
                        ),
                    ),
            )

        assertEquals(
            listOf(
                "The type of field a is left out: --form-string cannot carry one",
                "The type of field b is left out: --form-string cannot carry one",
            ),
            write(request).notes,
        )
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("quotes")
    fun `quotes a word only where the shell would read it otherwise`(
        shell: Shell,
        word: String,
        quoted: String,
    ) {
        assertEquals(quoted, CurlWriter.quote(word, shell))
        assertEquals(
            listOf(word),
            CurlTokenizer
                .tokenize("curl $quoted", shell)
                .single()
                .words
                .drop(1)
                .map { it.text },
        )
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Shell::class)
    fun `a hostile word is read back as it was`(shell: Shell) {
        for (word in HOSTILE) {
            val read = CurlTokenizer.tokenize("curl ${CurlWriter.quote(word, shell)}", shell).single()

            assertEquals(listOf(word), read.words.drop(1).map { it.text }, word)
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Shell::class)
    fun `a request with hostile values is read back as the same request`(shell: Shell) {
        for (word in HOSTILE.filter { it.isNotBlank() && it == it.trim() && '\u0000' !in it }) {
            val request =
                CurlRequest(
                    url = "https://example.com/?q=$word",
                    method = "POST",
                    headers = listOf("X" to word, "Content-Type" to FORM_TYPE),
                    body = RequestBody.Data(listOf(DataPiece.Text(word))),
                    credentials = Credentials("u $word", word),
                    name = "n",
                )
            val text = write(request, shell = shell).text

            val read = CurlReader.read(text) as ReadResult.Read
            // The shell is told by the line continuations; Windows quoting is read as cmd.
            assertEquals(listOf(request), read.content, "$shell: $word\n$text")
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Shell::class)
    fun `a multipart request with hostile names, values, file names and attributes is read back`(shell: Shell) {
        // What curl does with these (a local server, curl 8.14.1): `-F 'k="x"'` sends x but `--form-string` sends
        // "x"; `-F 'c=@a;b.txt'` and `-F 'c=@a,b.txt'` fail (exit 26) while `-F 'c=@"a;b.txt"'` uploads a;b.txt;
        // `-F 'k= x'` sends x; `;filename=x,y` fails and `;filename="x;y"` sends x;y.
        val fields =
            HOSTILE +
                listOf(
                    "@x",
                    "<x",
                    "\"x\"",
                    " x",
                    "a;b",
                    "a\"b",
                    "a;b.txt",
                    "a,b",
                    "\"q\"",
                    "a\\\"b",
                    "a ",
                    "a\n",
                    "a\t",
                    "(abc",
                    " a",
                    "\ta",
                    " a ",
                )
        // curl splits -F and --form-string at the first =, so a name with one cannot be written.
        val names = fields.filter { it.isNotEmpty() && '=' !in it }
        val files = fields.filter { it.isNotEmpty() }
        val parts =
            names.map { FormPart(it, FormContent.Value(it)) } +
                fields.map { FormPart("v", FormContent.Value(it)) } +
                files.flatMap {
                    listOf(
                        FormPart("f", FormContent.File(it)),
                        FormPart("f", FormContent.File(it, it), "t/x"),
                        FormPart("f", FormContent.FileText(it), "t/x"),
                    )
                }
        val request = CurlRequest("https://x", "POST", body = RequestBody.Multipart(parts))

        val read = CurlReader.read(write(request, shell = shell).text) as ReadResult.Read

        val readParts = (read.content.single().body as RequestBody.Multipart).parts
        parts.forEachIndexed { index, part -> assertEquals(part, readParts[index], "$shell: part $index") }
        assertEquals(request, read.content.single())
    }

    @Test
    fun `a POSIX shell reads the quoted hostile words as they were`() {
        // The real sh (dash, bash or zsh in sh mode): the same words through printf, one per NUL.
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")))
        val words = HOSTILE.filter { '\u0000' !in it }
        val script =
            "set --${words.joinToString("") { " " + CurlWriter.quote(it, Shell.POSIX) }}; " +
                "for a in \"\$@\"; do printf '%s\\0' \"\$a\"; done"
        // The script goes in through stdin: the JVM would encode arguments in the platform's charset.
        val process = ProcessBuilder("/bin/sh").start()
        try {
            process.outputStream.use { it.write(script.toByteArray(Charsets.UTF_8)) }
            val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
            assertTrue(process.waitFor(30, TimeUnit.SECONDS))

            assertEquals(words, output.removeSuffix("\u0000").split('\u0000'))
        } finally {
            process.destroyForcibly()
        }
    }

    companion object {
        private val HOSTILE =
            listOf(
                "a b",
                "it's",
                "say \"hi\"",
                "\$HOME \${x} \$(id)",
                "`id`",
                "a\nb",
                "line\r\nbreak",
                "tab\there",
                "\u00fcn\u00ef\u00a9ode \u2713 \uD83D\uDE00",
                "50%",
                "%PATH% !x!",
                "back\\slash",
                "ends\\",
                "ends\\\\",
                "\\\"",
                "\\\\\"x",
                "''",
                "\"\"",
                "^&|<>()",
                "=cmd",
                "~/x",
                "#c",
                "*?[]{}",
                "a;b",
                "\u2018q\u2019 \u201Cd\u201D \u201A\u201B \u201E",
                "-n",
                "",
                " ",
            )

        @JvmStatic
        fun requests() =
            listOf(
                Arguments.of("a GET", CurlRequest("https://x"), listOf("curl https://x")),
                Arguments.of("HEAD is -I", CurlRequest("x", method = "HEAD"), listOf("curl -I x")),
                Arguments.of(
                    "HEAD with a body keeps the method",
                    CurlRequest("x", method = "HEAD", body = RequestBody.Data(listOf(DataPiece.Text("a")))),
                    listOf("curl -X HEAD x", "-d a"),
                ),
                Arguments.of("POST without a body", CurlRequest("x", method = "POST"), listOf("curl -X POST x")),
                Arguments.of(
                    "GET with a body",
                    CurlRequest("x", body = RequestBody.Data(listOf(DataPiece.Text("a"))), method = "GET"),
                    listOf("curl -X GET x", "-d a"),
                ),
                Arguments.of(
                    "POST with a body needs no -X",
                    CurlRequest("x", method = "POST", body = RequestBody.Data(listOf(DataPiece.Text("a")))),
                    listOf("curl x", "-d a"),
                ),
                Arguments.of(
                    "DELETE with a body",
                    CurlRequest("x", method = "DELETE", body = RequestBody.Data(listOf(DataPiece.Text("a")))),
                    listOf("curl -X DELETE x", "-d a"),
                ),
                Arguments.of("redirects", CurlRequest("x", followRedirects = true), listOf("curl x", "-L")),
                Arguments.of("HTTP/2", CurlRequest("x", version = HttpVersion.HTTP_2), listOf("curl x", "--http2")),
                Arguments.of(
                    "HTTP/1.1",
                    CurlRequest("x", version = HttpVersion.HTTP_1_1),
                    listOf("curl x", "--http1.1"),
                ),
                Arguments.of(
                    "both timeouts",
                    CurlRequest("x", timeout = Duration.ofMillis(2500), connectTimeout = Duration.ofSeconds(3)),
                    listOf("curl x", "-m 2.5", "--connect-timeout 3"),
                ),
                Arguments.of(
                    "a connect timeout alone, in a fraction",
                    CurlRequest("x", connectTimeout = Duration.ofMillis(100)),
                    listOf("curl x", "--connect-timeout 0.1"),
                ),
                Arguments.of(
                    "a timeout of whole tens of seconds has no exponent",
                    CurlRequest("x", timeout = Duration.ofSeconds(60)),
                    listOf("curl x", "-m 60"),
                ),
                Arguments.of("insecure", CurlRequest("x", insecure = true), listOf("curl x", "-k")),
                Arguments.of(
                    "Basic credentials",
                    CurlRequest("x", credentials = Credentials("us er", "pa:ss")),
                    listOf("curl x", "-u 'us er:pa:ss'"),
                ),
                Arguments.of(
                    "Digest credentials",
                    CurlRequest("x", credentials = Credentials("u", "p", digest = true)),
                    listOf("curl x", "--digest", "-u u:p"),
                ),
                Arguments.of(
                    "headers, also without a value",
                    CurlRequest("x", headers = listOf("A" to "b c", "Empty" to "")),
                    listOf("curl x", "-H 'A: b c'", "-H 'Empty;'"),
                ),
                Arguments.of(
                    "the form content type of -d is left out in any case",
                    CurlRequest(
                        "x",
                        method = "POST",
                        headers = listOf("X" to "1", "content-type" to "Application/X-WWW-Form-Urlencoded"),
                        body = RequestBody.Data(listOf(DataPiece.Text("a"))),
                    ),
                    listOf("curl x", "-H 'X: 1'", "-d a"),
                ),
                Arguments.of(
                    "another content type of a -d body stays",
                    CurlRequest(
                        "x",
                        method = "POST",
                        headers = listOf("Content-Type" to "application/json"),
                        body = RequestBody.Data(listOf(DataPiece.Text("a"))),
                    ),
                    listOf("curl x", "-H 'Content-Type: application/json'", "-d a"),
                ),
                Arguments.of(
                    "the form content type with parameters stays, as curl's own has none",
                    CurlRequest(
                        "x",
                        method = "POST",
                        headers = listOf("Content-Type" to "$FORM_TYPE; charset=utf-8"),
                        body = RequestBody.Data(listOf(DataPiece.Text("a"))),
                    ),
                    listOf("curl x", "-H 'Content-Type: $FORM_TYPE; charset=utf-8'", "-d a"),
                ),
                Arguments.of(
                    "the form content type stays without a body and with a multipart one",
                    CurlRequest("x", headers = listOf("Content-Type" to FORM_TYPE)),
                    listOf("curl x", "-H 'Content-Type: $FORM_TYPE'"),
                ),
                Arguments.of(
                    "the form content type stays with a multipart body",
                    CurlRequest(
                        "x",
                        method = "POST",
                        headers = listOf("Content-Type" to FORM_TYPE),
                        body = RequestBody.Multipart(listOf(FormPart("f", FormContent.Value("v")))),
                    ),
                    listOf("curl x", "-H 'Content-Type: $FORM_TYPE'", "-F f=v"),
                ),
                Arguments.of(
                    "data pieces: text, text with @, files",
                    CurlRequest(
                        "x",
                        method = "POST",
                        body =
                            RequestBody.Data(
                                listOf(
                                    DataPiece.Text("a"),
                                    DataPiece.Text("@a"),
                                    DataPiece.File("f.bin"),
                                    DataPiece.File("g.txt", binary = false),
                                ),
                            ),
                    ),
                    listOf("curl x", "-d a", "--data-raw @a", "--data-binary @f.bin", "-d @g.txt"),
                ),
                Arguments.of(
                    "form fields",
                    CurlRequest(
                        "x",
                        method = "POST",
                        body =
                            RequestBody.Multipart(
                                listOf(
                                    FormPart("a", FormContent.Value("v")),
                                    FormPart("b", FormContent.Value("v"), "text/plain"),
                                    FormPart("c", FormContent.File("d/f.txt")),
                                    FormPart("d", FormContent.File("d/f.txt"), "text/x-y"),
                                    FormPart("e", FormContent.File("d/f.txt", "n.txt"), "text/x-y"),
                                    FormPart("f", FormContent.FileText("f.txt")),
                                    FormPart("g", FormContent.FileText("f.txt"), "text/x-y"),
                                    FormPart("h", FormContent.Value("@x")),
                                    FormPart("i", FormContent.Value("<x")),
                                    FormPart("j", FormContent.Value("x;y")),
                                    FormPart("k", FormContent.Value("a<b@c")),
                                    FormPart("l", FormContent.Value("\"x\"")),
                                    FormPart("m", FormContent.Value(" x")),
                                    FormPart("n", FormContent.Value("a\"b")),
                                    FormPart("o", FormContent.File("a;b.txt")),
                                    FormPart("p", FormContent.File("a,b\"c\\d.txt", "x;y"), "t/x"),
                                    FormPart("q", FormContent.FileText("a;b.txt")),
                                    FormPart("r", FormContent.Value("a ")),
                                    FormPart("s", FormContent.Value("a\n")),
                                    FormPart("t", FormContent.Value("(abc")),
                                    FormPart("u", FormContent.File(" a", " a")),
                                    FormPart("v", FormContent.FileText("a ")),
                                    FormPart("w", FormContent.File("")),
                                ),
                            ),
                    ),
                    listOf(
                        "curl x",
                        "-F a=v",
                        "-F 'b=v;type=text/plain'",
                        "-F c=@d/f.txt",
                        "-F 'd=@d/f.txt;type=text/x-y'",
                        "-F 'e=@d/f.txt;type=text/x-y;filename=n.txt'",
                        "-F 'f=<f.txt'",
                        "-F 'g=<f.txt;type=text/x-y'",
                        "--form-string h=@x",
                        "--form-string 'i=<x'",
                        "--form-string 'j=x;y'",
                        "-F 'k=a<b@c'",
                        "--form-string 'l=\"x\"'",
                        "--form-string 'm= x'",
                        "-F 'n=a\"b'",
                        "-F 'o=@\"a;b.txt\"'",
                        "-F 'p=@\"a,b\\\"c\\\\d.txt\";type=t/x;filename=\"x;y\"'",
                        "-F 'q=<\"a;b.txt\"'",
                        "--form-string 'r=a '",
                        "--form-string 's=a\n'",
                        "--form-string 't=(abc'",
                        "-F 'u=@\" a\";filename=\" a\"'",
                        "-F 'v=<\"a \"'",
                        "-F w=@",
                    ),
                ),
            )

        @JvmStatic
        fun quotes() =
            listOf(
                // POSIX: letters, digits and _@%+=:,./- stay; = only inside (zsh expands a leading one).
                Arguments.of(Shell.POSIX, "aZ09_@%+=:,./-", "aZ09_@%+=:,./-"),
                Arguments.of(Shell.POSIX, "=a", "'=a'"),
                Arguments.of(Shell.POSIX, "", "''"),
                Arguments.of(Shell.POSIX, "a'b", "'a'\\''b'"),
                Arguments.of(Shell.POSIX, "a b", "'a b'"),
                Arguments.of(Shell.POSIX, "a\$b", "'a\$b'"),
                Arguments.of(Shell.POSIX, "a!b", "'a!b'"),
                Arguments.of(Shell.POSIX, "a\\b", "'a\\b'"),
                // PowerShell: letters, digits and _+=:./- stay; @ , and % are special there.
                Arguments.of(Shell.POWERSHELL, "aZ09_+=:./-", "aZ09_+=:./-"),
                Arguments.of(Shell.POWERSHELL, "", "''"),
                Arguments.of(Shell.POWERSHELL, "a@b", "'a@b'"),
                Arguments.of(Shell.POWERSHELL, "a,b", "'a,b'"),
                Arguments.of(Shell.POWERSHELL, "a%b", "'a%b'"),
                Arguments.of(Shell.POWERSHELL, "a'b", "'a''b'"),
                Arguments.of(Shell.POWERSHELL, "a\"b", "'a\"b'"),
                // cmd: letters, digits and _@+:./- stay; = , % ! are not safe there.
                Arguments.of(Shell.CMD, "aZ09_@+:./-", "aZ09_@+:./-"),
                Arguments.of(Shell.CMD, "", "\"\""),
                Arguments.of(Shell.CMD, "a=b", "\"a=b\""),
                Arguments.of(Shell.CMD, "a,b", "\"a,b\""),
                Arguments.of(Shell.CMD, "a b", "\"a b\""),
                Arguments.of(Shell.CMD, "a&b", "\"a&b\""),
                Arguments.of(Shell.CMD, "C:\\dir\\", "\"C:\\dir\\\\\""),
                Arguments.of(Shell.CMD, "C:\\dir \\\\", "\"C:\\dir \\\\\\\\\""),
                Arguments.of(Shell.CMD, "50%", "^\"50^%^\""),
                Arguments.of(Shell.CMD, "a!b", "^\"a^!b^\""),
                Arguments.of(Shell.CMD, "50%\\", "^\"50^%\\\\^\""),
                Arguments.of(Shell.CMD, "a\"b", "^\"a\\^\"b^\""),
                Arguments.of(Shell.CMD, "a\\\"b", "^\"a\\\\\\^\"b^\""),
                Arguments.of(Shell.CMD, "a\\b%", "^\"a\\b^%^\""),
                Arguments.of(Shell.CMD, "a\nb", "^\"a^\n\nb^\""),
                Arguments.of(Shell.CMD, "^&|<>()%", "^\"^^^&^|^<^>^(^)^%^\""),
            )
    }
}
