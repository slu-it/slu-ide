package dev.sluide.httpclient

import dev.sluide.httpclient.env.EnvironmentFiles
import dev.sluide.httpclient.env.Environments
import dev.sluide.httpclient.parse.HttpFileParser
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpClientSetupTest {
    @TempDir
    lateinit var root: Path

    private val publicEnv get() = root.resolve(EnvironmentFiles.PUBLIC)
    private val privateEnv get() = root.resolve(EnvironmentFiles.PRIVATE)
    private val gitignore get() = root.resolve(".gitignore")
    private val example get() = root.resolve(HttpClientSetup.EXAMPLE)
    private val all get() = listOf(publicEnv, privateEnv, gitignore, example)

    @Test
    fun `creates the four files in an empty folder`() {
        val result = HttpClientSetup.create(root)

        assertEquals(all, result.created)
        assertTrue(result.kept.isEmpty())
        assertNull(result.note)
        assertEquals(example, result.example)
        assertEquals("{\n  \"local\": {\n    \"host\": \"https://example.com\"\n  }\n}\n", publicEnv.readText())
        assertEquals("{\n  \"local\": {}\n}\n", privateEnv.readText())
        assertEquals("http-client.private.env.json\n", gitignore.readText())
        assertEquals("### Example request\nGET {{host}}\nAccept: text/html\n", example.readText())
    }

    @Test
    fun `the environment files define local with a host`() {
        val result = HttpClientSetup.create(root)

        val environments = Environments.load(EnvironmentFiles.find(result.example, root))
        val local = assertNotNull(environments["local"])
        assertEquals("https://example.com", local.values.get("host").asString())
        assertTrue(environments.problems.isEmpty())
    }

    @Test
    fun `the example parses to one GET request on the host variable`() {
        val file = HttpFileParser.parse(HttpClientSetup.create(root).example.readText())

        val request = file.requests.single()
        assertEquals("GET", request.method)
        assertEquals("{{host}}", request.url)
        assertEquals(listOf("Accept" to "text/html"), request.headers.map { it.name to it.value })
    }

    @Test
    fun `leaves existing files as they are and names them`() {
        publicEnv.writeText("mine")
        example.writeText("mine too")

        val result = HttpClientSetup.create(root)

        assertEquals("mine", publicEnv.readText())
        assertEquals("mine too", example.readText())
        assertEquals(listOf(publicEnv, example), result.kept)
        assertEquals(listOf(privateEnv, gitignore), result.created)
        assertEquals("Left as they were: http-client.env.json, example.http", result.note)
    }

    @Test
    fun `appends the line after a newline when the gitignore has none at the end`() {
        gitignore.writeText("build")

        val result = HttpClientSetup.create(root)

        assertEquals("build\nhttp-client.private.env.json\n", gitignore.readText())
        assertTrue(gitignore in result.created)
    }

    @Test
    fun `appends the line directly when the gitignore ends with a newline`() {
        gitignore.writeText("build\n")

        HttpClientSetup.create(root)

        assertEquals("build\nhttp-client.private.env.json\n", gitignore.readText())
    }

    @Test
    fun `keeps a gitignore that lists the private file`() {
        for (line in listOf("http-client.private.env.json", "  /http-client.private.env.json  ")) {
            val text = "build\n$line\n"
            gitignore.writeText(text)

            val result = HttpClientSetup.create(root)

            assertEquals(text, gitignore.readText())
            assertTrue(gitignore in result.kept)
        }
    }

    @Test
    fun `an empty gitignore gets just the line`() {
        gitignore.writeText("")

        HttpClientSetup.create(root)

        assertEquals("http-client.private.env.json\n", gitignore.readText())
    }

    @Test
    fun `keeps a CRLF gitignore that lists the private file`() {
        gitignore.writeText("build\r\nhttp-client.private.env.json\r\n")

        val result = HttpClientSetup.create(root)

        assertEquals("build\r\nhttp-client.private.env.json\r\n", gitignore.readText())
        assertTrue(gitignore in result.kept)
    }

    @Test
    fun `appends with CRLF to a CRLF gitignore`() {
        gitignore.writeText("build\r\nout")

        HttpClientSetup.create(root)

        assertEquals("build\r\nout\r\nhttp-client.private.env.json\r\n", gitignore.readText())
    }

    @Test
    fun `keeps the bytes of a gitignore that is not UTF-8`() {
        val original = byteArrayOf('a'.code.toByte(), 0xE4.toByte(), '\n'.code.toByte())
        gitignore.writeBytes(original)

        val result = HttpClientSetup.create(root)

        assertTrue(gitignore in result.created)
        val expected = original + "http-client.private.env.json\n".toByteArray()
        assertEquals(expected.toList(), gitignore.readBytes().toList())
    }

    @Test
    fun `fails with the file name when the folder is missing`() {
        val e = assertFailsWith<IOException> { HttpClientSetup.create(root.resolve("missing")) }

        assertTrue("http-client.env.json" in e.message.orEmpty(), e.message)
    }

    @Test
    fun `fails naming the gitignore when it is a directory`() {
        gitignore.createDirectory()

        val e = assertFailsWith<IOException> { HttpClientSetup.create(root) }

        assertTrue(".gitignore" in e.message.orEmpty(), e.message)
    }

    @Test
    fun `a second run keeps everything`() {
        HttpClientSetup.create(root)
        val before = all.map { it.readText() }

        val result = HttpClientSetup.create(root)

        assertEquals(all, result.kept)
        assertTrue(result.created.isEmpty())
        assertEquals(before, all.map { it.readText() })
    }
}
