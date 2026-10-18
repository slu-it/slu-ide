package dev.sluide.fileviewers

import dev.sluide.fileviewers.html.HtmlFileViewer
import dev.sluide.fileviewers.image.ImageFileViewer
import dev.sluide.fileviewers.image.SvgFileViewer
import dev.sluide.fileviewers.markdown.MarkdownFileViewer
import dev.sluide.fileviewers.markdown.MdxFileViewer
import dev.sluide.fileviewers.markdown.MermaidFileViewer
import dev.sluide.fileviewers.pem.PemFileViewer
import dev.sluide.fileviewers.table.CookiesFileViewer
import dev.sluide.fileviewers.table.CsvFileViewer
import dev.sluide.textediting.CommentSyntax
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which parts of a `.env` file are masked ([EnvValues]), and which files [EnvFileViewer] opens. */
class EnvMaskingTest {
    @TempDir
    lateinit var dir: Path

    /** The masked texts of [text], with the [revealed] lines left out. */
    private fun masked(
        text: String,
        revealed: Set<Int> = emptySet(),
    ): List<String> = EnvValues.ranges(text) { it in revealed }.map { text.substring(it) }

    @Test
    fun `values after the first equals sign, without blanks around them`() {
        assertEquals(listOf("value"), masked("KEY=value"))
        assertEquals(listOf("a=b=c"), masked("KEY=a=b=c"))
        assertEquals(listOf("spaced value"), masked("SPACED =  spaced value  "))
        assertEquals(listOf("yes"), masked("  INDENTED=yes"))
        assertEquals(listOf("abc"), masked("export TOKEN=abc"))
        assertEquals(listOf("42", "x"), masked("NUM=42\r\nOTHER=x"))
    }

    @Test
    fun `quotes belong to the value, comments after it do not`() {
        assertEquals(listOf("abc123"), masked("API_KEY=abc123 # trailing"))
        assertEquals(listOf("\"a b # c\""), masked("QUOTED=\"a b # c\" # comment"))
        assertEquals(listOf("'x y'"), masked("SINGLE='x y'"))
        assertEquals(listOf("\"say \\\"hi\\\"\""), masked("ESCAPED=\"say \\\"hi\\\"\""))
        assertEquals(listOf("https://x.y/z?a=b#frag"), masked("URL=https://x.y/z?a=b#frag"), "# after no blank")
        assertEquals(listOf("#hash"), masked("PASSWORD=#hash"))
    }

    @Test
    fun `no value on comments, blank lines, empty values and lines without an equals sign`() {
        assertEquals(emptyList(), masked("# a comment\n\n   \nEMPTY=\nBLANK=   \nNOTE= # only a comment\njust text"))
    }

    @Test
    fun `commented-out assignments are masked, prose is not`() {
        assertEquals(listOf("old-secret", "x"), masked("# DB_PASSWORD=old-secret\n#export KEY = x"))
        assertEquals(emptyList(), masked("# set it to a=b\n  # a comment = no assignment"))
    }

    @Test
    fun `a quoted value goes on to its closing quote, line by line`() {
        val text =
            """
            |KEY="-----BEGIN KEY-----
            |  MIIBVQIBADANBgkqhkiG9w0BAQEFAASCAT8w
            |
            |-----END KEY-----" # the key
            |NEXT=after
            |SINGLE='a
            |b' tail
            """.trimMargin()

        assertEquals(
            listOf(
                "\"-----BEGIN KEY-----",
                "MIIBVQIBADANBgkqhkiG9w0BAQEFAASCAT8w",
                "-----END KEY-----\"",
                "after",
                "'a",
                "b'",
            ),
            masked(text),
        )
        assertEquals(listOf("MIIBVQIBADANBgkqhkiG9w0BAQEFAASCAT8w", "after"), masked(text, setOf(0, 3, 5, 6)))
    }

    @Test
    fun `a quote left open masks to the end`() {
        assertEquals(listOf("\"open", "rest", "OTHER=1"), masked("KEY=\"open\nrest\nOTHER=1"))
    }

    @Test
    fun `revealed lines are not masked, each range stays on its line`() {
        val text = "A=1\nB=2\nC=3"
        val ranges = EnvValues.ranges(text) { it == 1 }

        assertEquals(listOf(2..2, 10..10), ranges)
    }

    @Test
    fun `placeholder files are not masked`() {
        val names = listOf(".env", ".env.local", "prod.env", ".env.example", ".ENV.Sample", ".env.template")
        assertEquals(listOf(true, true, true, false, false, false), names.map(EnvValues::masks))
        assertFalse(EnvValues.masks(".env.local.example"))
    }

    @Test
    fun `the viewer opens env files and comments with hashes`() {
        val viewer = EnvFileViewer()
        val names = listOf(".env", ".env.local", ".env.production", "docker.env", "x.ENV", ".envrc", "env", "env.txt")
        val opened = names.filter { viewer.canOpen(dir.resolve(it).apply { writeText("A=1\n") }) }

        assertEquals(listOf(".env", ".env.local", ".env.production", "docker.env", "x.ENV"), opened)
        assertEquals(CommentSyntax.HASH, viewer.comment)
        assertTrue(FileViewers(listOf(viewer), DefaultTextFileViewer()).showsDiffs(viewer))
    }

    @Test
    fun `among all viewers, env files open as env unless their extension names another language`() {
        val viewers = FileViewers(allViewers(), DefaultTextFileViewer())
        val env = listOf(".env", ".env.local", ".env.production", "prod.env")
        for (name in env) {
            assertTrue(viewers.viewerFor(dir.resolve(name).apply { writeText("A=1\n") }) is EnvFileViewer, name)
        }
        val json = dir.resolve(".env.json").apply { writeText("{}\n") }
        assertTrue(viewers.viewerFor(json) is JsonFileViewer, "an extension wins over a name pattern")
    }

    @Test
    fun `among all viewers, PEM files open as PEM details`() {
        val viewers = FileViewers(allViewers(), DefaultTextFileViewer())
        val pem = "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n"
        for (name in listOf("cert.pem", "site.crt", "ca.cer", "tls.key")) {
            assertTrue(viewers.viewerFor(dir.resolve(name).apply { writeText(pem) }) is PemFileViewer, name)
        }
        val raw = dir.resolve("raw.key").apply { writeText("not PEM\n") }
        assertTrue(viewers.viewerFor(raw) is DefaultTextFileViewer, "a key without PEM text is plain text")
    }

    /** Every viewer of the module, the env and PEM viewers first, so that their rank and not the order decides. */
    private fun allViewers(): List<FileViewer> =
        listOf(
            EnvFileViewer(),
            PemFileViewer(),
            CaddyfileFileViewer(),
            ConfigFileViewer(),
            CookiesFileViewer(),
            CssFileViewer(),
            CsvFileViewer(),
            DockerfileFileViewer(),
            GitBlameIgnoreRevsFileViewer(),
            HtmlFileViewer(),
            IgnoreFileViewer(),
            ImageFileViewer(),
            IniFileViewer(),
            JavaFileViewer(),
            JavaScriptFileViewer(),
            JsonFileViewer(),
            JsxFileViewer(),
            KotlinFileViewer(),
            LessFileViewer(),
            MakefileFileViewer(),
            MarkdownFileViewer(),
            MdxFileViewer(),
            MermaidFileViewer(),
            NotebookFileViewer(),
            PowerShellFileViewer(),
            PythonFileViewer(),
            SassFileViewer(),
            ScssFileViewer(),
            ShellFileViewer(),
            SingleFileComponentFileViewer(),
            SqlFileViewer(),
            SshConfigFileViewer(),
            SvgFileViewer(),
            TsxFileViewer(),
            TypeScriptFileViewer(),
            XmlFileViewer(),
            YamlFileViewer(),
        )
}
