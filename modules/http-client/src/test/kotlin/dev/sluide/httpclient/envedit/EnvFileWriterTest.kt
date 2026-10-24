package dev.sluide.httpclient.envedit

import dev.sluide.fileviewers.FileViewHost
import dev.sluide.httpclient.env.EnvironmentFiles
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnvFileWriterTest {
    @TempDir
    lateinit var dir: Path

    private val publicFile get() = dir.resolve(EnvironmentFiles.PUBLIC)
    private val privateFile get() = dir.resolve(EnvironmentFiles.PRIVATE)

    private val publicText = "{\"dev\": {\"host\": \"a\"}}\n"
    private val privateText = "{\"dev\": {\"token\": \"t\"}}\n"

    /** A host whose tabs are a map of texts, recording what [editText] changed. */
    private class Host : FileViewHost {
        val tabs = mutableMapOf<Path, String>()
        val edited = mutableListOf<Path>()
        var canEdit = true

        override fun openFile(file: Path) = false

        override fun openWebPage(uri: URI) {}

        override fun <T : Any> windowService(type: Class<T>): T? = null

        override fun currentText(file: Path): String? = tabs[file]

        override fun editText(
            file: Path,
            edit: (String) -> String,
        ): Boolean {
            if (!canEdit || !Files.exists(file)) return false
            tabs[file] = edit(tabs[file] ?: Files.readString(file))
            edited.add(file)
            return true
        }
    }

    private fun writer(side: FileSide = FileSide.PUBLIC) =
        if (side == FileSide.PUBLIC) {
            EnvFileWriter(side, publicFile, privateFile)
        } else {
            EnvFileWriter(side, privateFile, publicFile)
        }

    private fun document(
        public: String? = publicText,
        private: String? = privateText,
    ) = EnvDocument(public, private)

    private fun apply(
        edit: EnvEdit,
        host: FileViewHost?,
        side: FileSide = FileSide.PUBLIC,
        document: EnvDocument = document(),
        editorText: String? = null,
        canReplace: Boolean = true,
    ) = writer(side).apply(
        edit,
        document,
        editorText ?: if (side == FileSide.PUBLIC) publicText else privateText,
        canReplace,
        host,
    )

    @Test
    fun `an edit of the shown file only needs no host`() {
        val outcome = apply(EnvEdit("new", null), host = null)

        assertEquals(EnvFileWriter.Outcome("new", null), outcome)
    }

    @Test
    fun `changes the other file through its tab, then hands over the shown file's text`() {
        privateFile.writeText(privateText)
        val host = Host()

        val outcome = apply(EnvEdit("new public", "new private"), host)

        assertEquals(EnvFileWriter.Outcome("new public", null), outcome)
        assertEquals("new private", host.tabs[privateFile])
        assertEquals(listOf(privateFile), host.edited)
    }

    @Test
    fun `the shown side can be the private file`() {
        publicFile.writeText(publicText)
        val host = Host()

        val outcome = apply(EnvEdit("new public", "new private"), host, side = FileSide.PRIVATE)

        assertEquals(EnvFileWriter.Outcome("new private", null), outcome)
        assertEquals("new public", host.tabs[publicFile])
    }

    @Test
    fun `reads the tab of the other file without a byte order mark and with unix line breaks`() {
        privateFile.writeText(privateText)
        val host = Host().apply { tabs[privateFile] = "\uFEFF" + privateText.replace("\n", "\r\n") }

        val outcome = apply(EnvEdit(null, "new private"), host)

        assertNull(outcome.failure)
        assertEquals("new private", host.tabs[privateFile])
    }

    @Test
    fun `changes nothing when the shown text changed since the form read it`() {
        privateFile.writeText(privateText)
        val host = Host()

        val outcome = apply(EnvEdit("x", "y"), host, editorText = publicText + " ")

        assertEquals(
            EnvFileWriter.Outcome(null, "http-client.env.json changed meanwhile; nothing was changed, try again"),
            outcome,
        )
        assertTrue(host.edited.isEmpty())
    }

    @Test
    fun `changes nothing when the shown file is read-only and the edit changes it`() {
        privateFile.writeText(privateText)
        val host = Host()

        val outcome = apply(EnvEdit("x", "y"), host, canReplace = false)

        assertEquals(EnvFileWriter.Outcome(null, "http-client.env.json is read-only"), outcome)
        assertTrue(host.edited.isEmpty())
        assertNull(apply(EnvEdit(null, "y"), host, canReplace = false).failure, "the other file may still change")
    }

    @Test
    fun `changes nothing when the other file's tab differs from what the form read`() {
        privateFile.writeText(privateText)
        val host = Host().apply { tabs[privateFile] = "{\"dev\": {\"token\": \"changed\"}}" }

        val outcome = apply(EnvEdit("x", "y"), host)

        assertEquals(
            EnvFileWriter.Outcome(
                null,
                "http-client.private.env.json changed meanwhile; nothing was changed, try again",
            ),
            outcome,
        )
        assertEquals("{\"dev\": {\"token\": \"changed\"}}", host.tabs[privateFile])
    }

    @Test
    fun `creates a missing other file as an empty object and writes to it`() {
        val host = Host()

        val outcome = apply(EnvEdit("x", "y"), host, document = document(private = null))

        assertEquals(EnvFileWriter.Outcome("x", null), outcome)
        assertEquals("y", host.tabs[privateFile])
        assertTrue(privateFile.readText().startsWith("{}"))
    }

    @Test
    fun `changes nothing when the missing other file appeared with content`() {
        privateFile.writeText("{\"dev\": {\"x\": 1}}")
        val host = Host()

        val outcome = apply(EnvEdit("x", "y"), host, document = document(private = null))

        assertEquals(
            EnvFileWriter.Outcome(
                null,
                "http-client.private.env.json changed meanwhile; nothing was changed, try again",
            ),
            outcome,
        )
        assertEquals("{\"dev\": {\"x\": 1}}", host.tabs[privateFile])
    }

    @Test
    fun `reports a tab that cannot be edited`() {
        privateFile.writeText(privateText)
        val host = Host().apply { canEdit = false }

        val outcome = apply(EnvEdit("x", "y"), host)

        assertEquals(
            EnvFileWriter.Outcome(
                null,
                "http-client.private.env.json cannot be changed: it cannot be opened as text or is read-only",
            ),
            outcome,
        )
    }

    @Test
    fun `reports that the other file can only be changed in an IDE window`() {
        privateFile.writeText(privateText)

        val outcome = apply(EnvEdit("x", "y"), host = null)

        assertEquals(
            EnvFileWriter.Outcome(null, "http-client.private.env.json can only be changed in an IDE window"),
            outcome,
        )
    }

    @Test
    fun `reports a missing other file that cannot be created`() {
        val missingFolder = dir.resolve("gone")
        val writer = EnvFileWriter(FileSide.PUBLIC, publicFile, missingFolder.resolve(EnvironmentFiles.PRIVATE))

        val outcome = writer.apply(EnvEdit("x", "y"), document(private = null), publicText, true, Host())

        assertNull(outcome.ownText)
        assertTrue(outcome.failure.orEmpty().startsWith("http-client.private.env.json cannot be created: "), "$outcome")
    }
}
