package dev.sluide.httpclient

import dev.sluide.testsupport.LogCapture
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpCompletionTest {
    @TempDir
    lateinit var project: Path

    private fun complete(
        marked: String,
        folder: Path? = project,
    ): HttpCompletions {
        val caret = marked.indexOf('|')
        val text = marked.removeRange(caret, caret + 1)
        return HttpCompletion.complete(project.resolve("a.http"), text, caret, folder).get(30, TimeUnit.SECONDS)
    }

    @Test
    fun `completes normally with nothing when the work fails and logs no message`() {
        val logName = HttpCompletion::class.java.name
        LogCapture(logName).use { log ->
            for (failure in listOf<() -> Throwable>(
                { IllegalStateException("hunter2") },
                { StackOverflowError("hunter2") },
            )) {
                val result =
                    HttpCompletion
                        .complete("GET {{", 99) { throw failure() }
                        .get(30, TimeUnit.SECONDS)

                assertEquals(HttpCompletions(6, emptyList()), result)
            }

            assertEquals(2, log.lines.size)
            assertTrue(log.lines.all { it.first == Level.FINE && "hunter2" !in it.second }, log.messages().toString())
            assertEquals("Completion failed: java.lang.IllegalStateException", log.messages().first())
        }
    }

    @Test
    fun `opens at once on the trigger characters`() {
        assertEquals(listOf("{", "\$", ".", "-", "/"), HttpCompletion.triggerCharacters)
    }

    @Test
    fun `offers the names of the public and private files with their environments and no value`() {
        project.resolve("http-client.env.json").writeText(
            """{"dev": {"host": "PUBLIC-VALUE-1", "port": 8080}, "prod": {"host": "PUBLIC-VALUE-2"}}""",
        )
        project.resolve("http-client.private.env.json").writeText(
            """{"dev": {"token": "PRIVATE-VALUE-3"}, "local": {"host": "PRIVATE-VALUE-4"}}""",
        )

        val result = complete("GET {{|")

        val byLabel = result.items.associateBy { it.label }
        assertEquals("dev, prod, local", byLabel.getValue("host").detail)
        assertEquals("dev", byLabel.getValue("token").detail)
        assertEquals("dev", byLabel.getValue("port").detail)
        assertEquals(6, result.anchor)
        val everything = result.items.joinToString("\n") { "${it.label}|${it.insertText}|${it.detail}" }
        for (value in listOf("PUBLIC-VALUE", "PRIVATE-VALUE", "8080")) assertFalse(value in everything, value)
    }

    @Test
    fun `finds the files of a parent folder up to the project folder`() {
        project.resolve("http-client.env.json").writeText("""{"dev": {"top": 1}}""")
        val folder = project.resolve("api").createDirectories()

        val result = HttpCompletion.complete(folder.resolve("a.http"), "GET {{", 6, project).get(30, TimeUnit.SECONDS)

        assertTrue("top" in result.items.map { it.label })
    }

    @Test
    fun `still answers when the environment files are broken`() {
        project.resolve("http-client.env.json").writeText("{ not json")
        project.resolve("http-client.private.env.json").writeText("[1, 2]")

        val result = complete("GET {{\$uu|")

        assertTrue("\$uuid" in result.items.map { it.label })
    }

    @Test
    fun `still answers when the environment file is nested far too deep`() {
        val depth = 5000
        project.resolve("http-client.env.json").writeText("""{"dev": """ + "[".repeat(depth) + "]".repeat(depth) + "}")

        val result = complete("GET {{\$uu|")

        assertTrue("\$uuid" in result.items.map { it.label })
    }

    @Test
    fun `answers without a project folder`() {
        val result = complete("GET /a\nContent-T|", folder = null)

        assertEquals(
            listOf(
                "Content-Disposition",
                "Content-Encoding",
                "Content-Length",
                "Content-Type",
            ),
            result.items.map {
                it.label
            },
        )
        assertEquals(7, result.anchor)
    }

    @Test
    fun `offers no environment names when the env file path is a folder`() {
        project.resolve("http-client.env.json").createDirectories()

        val result = complete("GET {{ho|")

        assertTrue(result.items.all { it.label.startsWith("\$") }, result.items.toString())
    }
}
