package dev.sluide.fileviewers.html

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.sluide.fileviewers.imageTypes
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.SecureRandom
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.io.path.extension

/**
 * Serves the files of a folder over HTTP on the loopback interface, for HTML previews (ADR 0018): a page loaded from
 * here can only reach what the server hands out, while a `file:` page's scripts can read every local file.
 *
 * Each root folder gets its own server, so its pages are their own origin. A file's URL is
 * `http://127.0.0.1:<port>/<token>/_/…/_/<path in the root>`:
 * - the random token keeps out other local users and pages in a browser that guess the port;
 * - one `_` per folder above the root and the root itself (`/home/me/site` gives three) stands in for those folders
 *   without naming them, so that a link climbing out of the root (`../other/page.html`) still leads to a file
 *   ([fileFor]), which the server does not serve but the IDE can open.
 *
 * The server serves regular files inside the root, and refuses:
 * - files outside the root, also through symbolic links (it serves the real path it checked);
 * - hidden files and everything in hidden folders (`.git`, `.env`), except the pages it gave a [url] for;
 * - requests other than GET and HEAD, and requests for another host name (DNS rebinding).
 *
 * A folder's URL ending in `/` serves its `index.html`. Responses are never cached, so a page reloads with its edited
 * styles. Servers run until the application exits.
 */
internal object PreviewServer {
    private val sites = ConcurrentHashMap<Path, Site>()

    /**
     * The folder whose files the page for [file] may use: [projectFolder] if it serves [file] (not in a hidden folder,
     * not through a symbolic link leading out of it), otherwise the folder [file] really is in.
     */
    fun rootFor(
        projectFolder: Path?,
        file: Path,
    ): Path {
        val path = file.toAbsolutePath().normalize()
        val real = realPath(path) ?: return path.parent
        val project = projectFolder?.toAbsolutePath()?.normalize()
        if (project != null && path.startsWith(project)) {
            val realProject = realPath(project)
            if (realProject != null &&
                real.startsWith(realProject) &&
                !isHidden(project.relativize(path)) &&
                !isHidden(realProject.relativize(real))
            ) {
                return project
            }
        }
        return path.parent.takeIf { realPath(it) == real.parent } ?: real.parent
    }

    /** The URL of [file], inside [root], on the server for [root] (started on first use). */
    fun url(
        root: Path,
        file: Path,
    ): URI = sites.computeIfAbsent(root.toAbsolutePath().normalize(), ::Site).url(file.toAbsolutePath().normalize())

    /** The file that [location], a URL on one of the servers, stands for, maybe outside its root; else `null`. */
    fun fileFor(location: String): Path? =
        try {
            val uri = URI(location)
            val site = sites.values.firstOrNull { uri.scheme == "http" && uri.authority == it.authority }
            site?.file(uri.path.orEmpty())
        } catch (_: URISyntaxException) {
            null
        }

    private fun realPath(path: Path): Path? =
        try {
            path.toRealPath()
        } catch (_: IOException) {
            null
        }

    private fun isHidden(path: Path) = path.any { it.toString().startsWith(".") }

    private class Site(
        val root: Path,
    ) {
        private val token = HexFormat.of().formatHex(ByteArray(16).also(SecureRandom()::nextBytes))
        private val prefix = "/$token/" + "_/".repeat(root.nameCount)

        /** Real paths of the pages this server gave URLs for; served even if hidden. */
        private val pages = ConcurrentHashMap.newKeySet<Path>()

        private val server =
            // 127.0.0.1, not getLoopbackAddress(): with IPv6 preferred that is ::1, which the URLs do not name.
            HttpServer.create(InetSocketAddress(InetAddress.getByName(HOST), 0), 0).apply {
                executor = Executors.newVirtualThreadPerTaskExecutor()
                createContext("/") { exchange ->
                    try {
                        handle(exchange)
                    } catch (_: IOException) {
                        // The client went away, or the file did.
                    } finally {
                        exchange.close()
                    }
                }
            }
        val authority = "$HOST:${server.address.port}"

        init {
            // The server's dispatcher thread is a daemon only if the thread starting it is, so it never keeps the
            // application from exiting.
            Thread
                .ofPlatform()
                .daemon()
                .start { server.start() }
                .join()
        }

        fun url(file: Path): URI {
            val real = realPath(file)
            val realRoot = realPath(root)
            val path =
                when {
                    file.startsWith(root) -> root.relativize(file)
                    real != null && realRoot != null && real.startsWith(realRoot) -> realRoot.relativize(real)
                    else -> throw IllegalArgumentException("$file is not in $root")
                }
            real?.let(pages::add)
            return URI("http", authority, prefix + path.joinToString("/"), null, null)
        }

        /**
         * The file at [path] (decoded) of a URL on this server: leading `_` stand for the root's folders, as many as
         * are left after climbing out of the root.
         */
        fun file(path: String): Path? {
            if (!path.startsWith("/$token/")) return null
            val segments = path.removePrefix("/$token/").split("/")
            val folders = segments.takeWhile { it == "_" }.size.coerceAtMost(root.nameCount)
            val base = if (folders == 0) root.root else root.root.resolve(root.subpath(0, folders))
            return try {
                base.resolve(segments.drop(folders).joinToString("/")).normalize()
            } catch (_: InvalidPathException) {
                null
            }
        }

        private fun handle(exchange: HttpExchange) {
            val headers = exchange.responseHeaders
            headers["Cache-Control"] = "no-store"
            headers["X-Content-Type-Options"] = "nosniff"
            // Requests to other sites would otherwise carry the token.
            headers["Referrer-Policy"] = "no-referrer"
            if (exchange.requestMethod != "GET" && exchange.requestMethod != "HEAD") {
                headers["Allow"] = "GET, HEAD"
                return exchange.sendResponseHeaders(405, -1)
            }
            if (exchange.requestHeaders.getFirst("Host") != authority) return exchange.sendResponseHeaders(403, -1)
            val path = exchange.requestURI.path.orEmpty()
            val file = file(path)?.takeIf { it.startsWith(root) } ?: return exchange.sendResponseHeaders(404, -1)
            val real = served(file) ?: return exchange.sendResponseHeaders(404, -1)
            if (Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
                if (!path.endsWith("/")) {
                    headers["Location"] = exchange.requestURI.rawPath + "/"
                    return exchange.sendResponseHeaders(301, -1)
                }
                return send(exchange, served(file.resolve("index.html")))
            }
            send(exchange, real)
        }

        /** Sends [real], a path from [served], or 404 if there is none. */
        private fun send(
            exchange: HttpExchange,
            real: Path?,
        ) {
            if (real == null || !Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
                return exchange.sendResponseHeaders(404, -1)
            }
            // The real path, without following links: what was checked is what is read.
            Files.newInputStream(real, LinkOption.NOFOLLOW_LINKS).use { input ->
                val size = Files.size(real)
                exchange.responseHeaders["Content-Type"] = contentType(real)
                // -1: no body; 0 would mean a body of unknown length.
                val length = if (exchange.requestMethod == "HEAD" || size == 0L) -1 else size
                exchange.sendResponseHeaders(200, length)
                if (length > 0) input.transferTo(exchange.responseBody)
            }
        }

        /**
         * The real path of [file] (normalised, in the root) if it is served: symbolic links followed, it is still in the
         * root, and it is no hidden file nor in a hidden folder, unless it is one of the [pages].
         */
        private fun served(file: Path): Path? {
            val real = realPath(file) ?: return null
            val realRoot = realPath(root) ?: return null
            if (!real.startsWith(realRoot)) return null
            val hidden = isHidden(root.relativize(file)) || isHidden(realRoot.relativize(real))
            return real.takeIf { !hidden || it in pages }
        }
    }

    /** The address the servers listen on, and the host name of their URLs. */
    private const val HOST = "127.0.0.1"

    /** The media type of [file]; text as UTF-8, like the IDE reads it, also for pages without `<meta charset>`. */
    private fun contentType(file: Path): String {
        val type =
            contentTypes[file.extension.lowercase()]
                ?: imageTypes[file.extension.lowercase()]
                ?: runCatching { Files.probeContentType(file) }.getOrNull()
                ?: "application/octet-stream"
        return if (type.startsWith("text/")) "$type; charset=utf-8" else type
    }

    private val contentTypes =
        mapOf(
            "html" to "text/html",
            "htm" to "text/html",
            "xhtml" to "application/xhtml+xml",
            "css" to "text/css",
            "js" to "text/javascript",
            "mjs" to "text/javascript",
            "json" to "application/json",
            "map" to "application/json",
            "xml" to "application/xml",
            "txt" to "text/plain",
            "csv" to "text/csv",
            "md" to "text/markdown",
            "wasm" to "application/wasm",
            "woff" to "font/woff",
            "woff2" to "font/woff2",
            "ttf" to "font/ttf",
            "otf" to "font/otf",
            "mp4" to "video/mp4",
            "webm" to "video/webm",
            "mp3" to "audio/mpeg",
            "pdf" to "application/pdf",
        )
}
