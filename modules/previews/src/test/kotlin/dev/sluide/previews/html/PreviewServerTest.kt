package dev.sluide.previews.html

import org.junit.jupiter.api.io.TempDir
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreviewServerTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var project: Path
    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()

    @BeforeTest
    fun files() {
        project = dir.resolve("project").createDirectories()
        project.resolve("site/css").createDirectories()
        project.resolve("site/index.html").writeText("<h1>Index</h1>")
        project.resolve("site/css/style.css").writeText("h1 {}")
        project.resolve("site/data file #1.json").writeText("{}")
        project.resolve("empty.txt").writeText("")
        project.resolve(".env").writeText("TOKEN=secret")
        project
            .resolve(".git")
            .createDirectories()
            .resolve("config")
            .writeText("secret")
        dir.resolve("outside.txt").writeText("secret")
    }

    private fun get(
        uri: URI,
        method: String = "GET",
    ): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(uri).method(method, HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    /** The URL the server gives for [file], which it then serves even if hidden. */
    private fun url(file: String) = PreviewServer.url(project, project.resolve(file))

    /** The URL of [file] without asking the server for it, as a page would request it. */
    private fun request(file: String) = raw(prefix + file)

    /** The URL of [path], given as it goes over the wire, on the project's server. */
    private fun raw(path: String) = url("x").resolve(path)

    /** The token and the placeholders for the project's folders: the start of the path of the project's URLs. */
    private val prefix get() = url("x").rawPath.removeSuffix("x")

    @Test
    fun `serves the root's files with their types, never cached`() {
        val page = get(url("site/index.html"))
        assertEquals(200, page.statusCode())
        assertEquals("<h1>Index</h1>", page.body())
        assertEquals("text/html; charset=utf-8", page.headers().firstValue("Content-Type").orElse(null))
        assertEquals("no-store", page.headers().firstValue("Cache-Control").orElse(null))
        assertEquals("nosniff", page.headers().firstValue("X-Content-Type-Options").orElse(null))
        assertEquals("no-referrer", page.headers().firstValue("Referrer-Policy").orElse(null))
        assertEquals(
            "text/css; charset=utf-8",
            get(url("site/css/style.css")).headers().firstValue("Content-Type").orElse(null),
        )
        val json = get(url("site/data file #1.json"))
        assertEquals("{}", json.body())
        assertEquals("application/json", json.headers().firstValue("Content-Type").orElse(null))
        assertEquals(200, get(url("empty.txt")).statusCode())
    }

    @Test
    fun `pages are at a random path on the loopback interface, one server per root`() {
        val page = url("site/index.html")
        assertEquals("127.0.0.1", page.host)
        // One placeholder for each folder down to the project.
        val placeholders = "_/".repeat(project.nameCount)
        assertTrue(Regex("/[0-9a-f]{32}/${placeholders}site/index.html").matches(page.path), page.path)
        assertEquals(page, url("site/index.html"))
        assertNotEquals(page.port, PreviewServer.url(project.resolve("site"), project.resolve("site/index.html")).port)
    }

    @Test
    fun `a folder shows its index page`() {
        assertEquals("<h1>Index</h1>", get(url("site").resolve("site/")).body())
        val redirect = get(URI(url("site").toString()))
        assertEquals(301, redirect.statusCode())
        assertEquals(url("site").path + "/", redirect.headers().firstValue("Location").orElse(null))
        // The project folder has no index page.
        assertEquals(404, get(PreviewServer.url(project, project)).statusCode())
    }

    @Test
    fun `refuses hidden files and files outside the root`() {
        val token = url("x").path.split("/")[1]
        val refused =
            listOf(
                request(".env"),
                request(".git/config"),
                raw("$prefix../outside.txt"),
                raw("$prefix%2e%2e/outside.txt"),
                raw("/$token/../outside.txt"),
                raw("/$token/%2e%2e/outside.txt"),
                raw("/$token/site/%2e%2e/%2e%2e/outside.txt"),
                raw("/$token/%2Fetc%2Fpasswd"),
                raw("/$token/%00"),
                raw("/$token/missing.txt"),
                raw("/0123456789abcdef0123456789abcdef/site/index.html"),
                raw("/site/index.html"),
            )
        for (uri in refused) assertEquals(404, get(uri).statusCode(), uri.toString())
    }

    @Test
    fun `refuses symbolic links out of the root or to hidden files`() {
        Files.createSymbolicLink(project.resolve("out.txt"), dir.resolve("outside.txt"))
        Files.createSymbolicLink(project.resolve("out"), dir)
        Files.createSymbolicLink(project.resolve("env.txt"), project.resolve(".env"))
        Files.createSymbolicLink(project.resolve("style.css"), project.resolve("site/css/style.css"))

        assertEquals(404, get(request("out.txt")).statusCode())
        assertEquals(404, get(request("out/outside.txt")).statusCode())
        assertEquals(404, get(request("env.txt")).statusCode())
        assertEquals(200, get(request("style.css")).statusCode())
    }

    @Test
    fun `only reads, and only for its own host name`() {
        assertEquals(405, get(url("site/index.html"), "POST").statusCode())
        assertEquals(200, get(url("site/index.html"), "HEAD").statusCode())

        // As after DNS rebinding: a request for another host name that reaches the server.
        val page = url("site/index.html")
        Socket(page.host, page.port).use { socket ->
            socket.getOutputStream().write(
                "GET ${page.rawPath} HTTP/1.1\r\nHost: evil.example:${page.port}\r\n\r\n".toByteArray(),
            )
            val status = socket.getInputStream().bufferedReader().readLine()
            assertEquals("HTTP/1.1 403 Forbidden", status)
        }
    }

    @Test
    fun `maps its URLs back to files`() {
        val page = url("site/data file #1.json")
        assertEquals(project.resolve("site/data file #1.json"), PreviewServer.fileFor(page.toString()))
        assertEquals(project.resolve("site/index.html"), PreviewServer.fileFor("${url("site/index.html")}#part"))
        assertEquals(project.resolve("site"), PreviewServer.fileFor(url("site").toString() + "/"))
        val token = page.path.split("/")[1]
        // Links that climb out of the root still lead to their files, which are not served.
        assertEquals(dir.resolve("outside.txt"), PreviewServer.fileFor(raw("$prefix../outside.txt").toString()))
        assertEquals(
            dir.resolve("outside.txt"),
            PreviewServer.fileFor(raw("${prefix}site/../../outside.txt").toString()),
        )
        assertNull(PreviewServer.fileFor("http://127.0.0.1:1/$token/site/index.html"))
        assertNull(PreviewServer.fileFor("https://example.com/"))
        assertNull(PreviewServer.fileFor(project.resolve("site/index.html").toUri().toString()))
        assertNull(PreviewServer.fileFor("not a uri"))
    }

    @Test
    fun `serves hidden pages it gave a URL for, but not their hidden neighbours`() {
        val page = project.resolve(".preview.html").apply { writeText("<h1>Hidden</h1>") }

        assertEquals(404, get(request(".preview.html")).statusCode())
        assertEquals("<h1>Hidden</h1>", get(PreviewServer.url(project, page)).body())
        assertEquals(404, get(request(".env")).statusCode())
    }

    @Test
    fun `the root is the project folder if it serves the file, else the file's real folder`() {
        val page = project.resolve("site/index.html")
        val github = project.resolve(".github").createDirectories()
        val shared = dir.resolve("shared").createDirectories()
        shared.resolve("doc.html").writeText("<h1>Shared</h1>")
        Files.createSymbolicLink(project.resolve("docs"), shared)

        assertEquals(project, PreviewServer.rootFor(project, page))
        assertEquals(github, PreviewServer.rootFor(project, github.resolve("x.html")))
        // Through the link, which the server follows only to the folder it leads to.
        assertEquals(project.resolve("docs"), PreviewServer.rootFor(project, project.resolve("docs/doc.html")))
        assertEquals(project.resolve("site"), PreviewServer.rootFor(null, page))
        assertEquals(dir, PreviewServer.rootFor(project, dir.resolve("outside.txt")))

        // A page through a symbolic link is served from its real folder.
        val root = PreviewServer.rootFor(project, project.resolve("docs/doc.html"))
        assertEquals("<h1>Shared</h1>", get(PreviewServer.url(root, project.resolve("docs/doc.html"))).body())
    }

    @Test
    fun `files outside the root have no URL`() {
        val failure = runCatching { PreviewServer.url(project, dir.resolve("outside.txt")) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }
}
