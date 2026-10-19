package dev.sluide.httpclient.send

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.sluide.httpclient.parse.HttpVersion
import dev.sluide.httpclient.parse.RequestTags
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A local HTTP server on 127.0.0.1 for exchange tests; `/echo` answers with method, headers and body. */
internal class TestServer : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

    /** Released on [close], so handlers that wait for it end with the test. */
    val released = CountDownLatch(1)

    init {
        server.executor = Executors.newCachedThreadPool { runnable -> Thread(runnable).apply { isDaemon = true } }
        handle("/echo") { exchange ->
            val headers =
                exchange.requestHeaders.entries
                    .flatMap { (name, values) -> values.map { "${name.lowercase()}: $it" } }
                    .sorted()
            val body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            respond(exchange, 200, "${exchange.requestMethod}\n${headers.joinToString("\n")}\n\n$body")
        }
        server.start()
    }

    val base get() = "http://127.0.0.1:${server.address.port}"

    fun handle(
        path: String,
        handler: (HttpExchange) -> Unit,
    ) {
        server.createContext(path) { exchange -> exchange.use(handler) }
    }

    fun respond(
        exchange: HttpExchange,
        status: Int,
        body: String,
        vararg headers: Pair<String, String>,
    ) {
        respond(exchange, status, body.toByteArray(), *headers)
    }

    fun respond(
        exchange: HttpExchange,
        status: Int,
        body: ByteArray,
        vararg headers: Pair<String, String>,
    ) {
        headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
        exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) exchange.responseBody.write(body)
    }

    fun request(
        path: String,
        method: String = "GET",
        headers: List<Pair<String, String>> = emptyList(),
        body: String = "",
    ) = PreparedRequest(URI("$base$path"), method, HttpVersion.HTTP_1_1, headers, body.toByteArray(), emptyList())

    override fun close() {
        released.countDown()
        server.stop(0)
    }
}

/** Runs [request], named [name] as in a `.http` file, and waits for its result. */
internal fun RequestExecutor.await(
    request: PreparedRequest,
    tags: RequestTags = RequestTags(),
    name: String? = null,
): ExchangeResult {
    val result = CompletableFuture<ExchangeResult>()
    run(request, tags, result::complete, name)
    return result.get(30, TimeUnit.SECONDS)
}
