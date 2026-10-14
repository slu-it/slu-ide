package dev.sluide.httpclient.auth

import dev.sluide.httpclient.env.Environment
import dev.sluide.httpclient.env.EnvironmentFiles
import dev.sluide.httpclient.env.Environments
import dev.sluide.httpclient.send.TestServer
import java.net.URLDecoder
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText

/** A local OAuth2 token endpoint at [url] that records each request and answers with [respond]. */
internal class TokenEndpoint : AutoCloseable {
    data class TokenRequest(
        val form: List<Pair<String, String>>,
        val authorization: String?,
        val contentType: String?,
    ) {
        fun param(name: String) = params(name).singleOrNull()

        fun params(name: String) = form.filter { it.first == name }.map { it.second }
    }

    val server = TestServer()
    val requests = CopyOnWriteArrayList<TokenRequest>()

    /** The status and JSON body for a request; by default a new access token `t<n>` per call. */
    @Volatile
    var respond: (TokenRequest) -> Pair<Int, String> = {
        200 to
            """{"access_token": "t$calls", "token_type": "Bearer"}"""
    }

    init {
        server.handle("/token") { exchange ->
            val body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            val form =
                body.split('&').filter { it.isNotEmpty() }.map {
                    URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to
                        URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8)
                }
            val request =
                TokenRequest(
                    form,
                    exchange.requestHeaders.getFirst("Authorization"),
                    exchange.requestHeaders.getFirst("Content-Type"),
                )
            requests += request
            val (status, json) = respond(request)
            server.respond(exchange, status, json, "Content-Type" to "application/json")
        }
    }

    val url get() = "${server.base}/token"
    val calls get() = requests.size
    val last: TokenRequest get() = requests.last()

    override fun close() = server.close()
}

/** The environment [name] with the JSON object [json] as its content, read like an environment file. */
@OptIn(ExperimentalPathApi::class)
internal fun environment(
    json: String,
    name: String = "dev",
): Environment {
    val folder = Files.createTempDirectory("env")
    try {
        val file = folder.resolve(EnvironmentFiles.PUBLIC).apply { writeText("""{"$name": $json}""") }
        val environment = checkNotNull(Environments.load(EnvironmentFiles(file, null))[name])
        check(environment.problems.isEmpty()) { environment.problems }
        return environment
    } finally {
        folder.deleteRecursively()
    }
}

/** An environment with the OAuth2 auth [id], its further keys [auth] (JSON members without braces). */
internal fun oauthEnvironment(
    tokenUrl: String,
    auth: String = """"Grant Type": "Client Credentials", "Client ID": "client", "Client Secret": "secret"""",
    id: String = "a",
    name: String = "dev",
): Environment =
    environment(
        """
        {"tokenUrl": "$tokenUrl",
         "Security": {"Auth": {"$id": {"Type": "OAuth2", "Token URL": "{{tokenUrl}}", $auth}}}}
        """.trimIndent(),
        name,
    )

internal class MutableClock(
    var now: Instant = Instant.parse("2026-01-01T00:00:00Z"),
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = now
}
