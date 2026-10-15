package dev.sluide.httpclient.auth

import dev.sluide.httpclient.env.AuthConfig.ClientCredentials
import dev.sluide.httpclient.send.HttpClients
import dev.sluide.httpclient.send.ReasonPhrases
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.ConnectException
import java.net.URLEncoder
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.Base64
import javax.net.ssl.SSLContext

/**
 * Requests tokens from the token endpoint of an [OAuth2Config] (RFC 6749): the Client Credentials and Password grants
 * and refreshing with a refresh token. Calls block until the response is read, at most for [timeout]; an interrupt
 * aborts them with [InterruptedException]. Failures throw [OAuth2Exception]. The `sslContext` of a call is the one of
 * the environment, so client certificates and `verifyHostCertificate` apply to the token endpoint as well.
 */
internal class OAuth2Client(
    private val clients: HttpClients,
    private val timeout: Duration = DEFAULT_TIMEOUT,
) {
    /** A token response; `expires_in` became [expiresIn]; [notes] tell what of the response was ignored. */
    class TokenResponse(
        val accessToken: String,
        val tokenType: String?,
        val expiresIn: Duration?,
        val refreshToken: String?,
        val idToken: String?,
        val notes: List<String> = emptyList(),
    )

    fun grant(
        config: OAuth2Config,
        sslContext: SSLContext? = null,
    ): TokenResponse {
        val parameters = mutableListOf("grant_type" to config.grant.value)
        if (config.grant == OAuth2Config.Grant.PASSWORD) {
            parameters += "username" to config.username.orEmpty()
            parameters += "password" to config.password.orEmpty()
        }
        return request(config, parameters, sslContext)
    }

    fun refresh(
        config: OAuth2Config,
        refreshToken: String,
        sslContext: SSLContext? = null,
    ): TokenResponse =
        request(config, mutableListOf("grant_type" to "refresh_token", "refresh_token" to refreshToken), sslContext)

    private fun request(
        config: OAuth2Config,
        parameters: MutableList<Pair<String, String>>,
        sslContext: SSLContext?,
    ): TokenResponse {
        config.scope?.let { parameters += "scope" to it }
        val builder =
            HttpRequest
                .newBuilder(config.tokenUrl)
                .timeout(timeout)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .header("User-Agent", "Thoreau")
        when (config.clientCredentials) {
            ClientCredentials.BASIC -> {
                // RFC 6749 2.3.1: id and secret are form-encoded before they are joined and Base64-encoded.
                val credentials = "${encode(config.clientId)}:${encode(config.clientSecret.orEmpty())}"
                builder.header(
                    "Authorization",
                    "Basic " + Base64.getEncoder().encodeToString(credentials.toByteArray()),
                )
            }

            ClientCredentials.IN_BODY -> {
                parameters += "client_id" to config.clientId
                parameters += "client_secret" to config.clientSecret.orEmpty()
            }

            ClientCredentials.NONE -> {
                parameters += "client_id" to config.clientId
            }
        }
        parameters += config.parameters
        val form = parameters.joinToString("&") { (name, value) -> "${encode(name)}=${encode(value)}" }
        val request = builder.POST(HttpRequest.BodyPublishers.ofString(form)).build()
        val key = HttpClients.ClientKey(followRedirects = false, cookieJar = false, timeout, sslContext)
        val response =
            try {
                clients.client(key).send(request, HttpResponse.BodyHandlers.ofByteArray())
            } catch (e: IOException) {
                throw failure(config, describe(e, config), e)
            }
        return try {
            parse(config, response.statusCode(), response.body())
        } catch (e: OAuth2Exception) {
            throw e
        } catch (e: RuntimeException) {
            throw failure(config, "the response cannot be read ($e)", e)
        }
    }

    private fun parse(
        config: OAuth2Config,
        status: Int,
        body: ByteArray,
    ): TokenResponse {
        val json =
            try {
                mapper.readTree(body)
            } catch (_: JacksonException) {
                null
            }?.takeIf { it.isObject }
        val error = json?.text("error")
        val statusText = listOfNotNull(status.toString(), ReasonPhrases.of(status)).joinToString(" ")
        if (error != null) {
            val description = json.text("error_description")?.let { " — $it" }.orEmpty()
            throw failure(config, "$status $error$description")
        }
        // Other bodies stay out of the message: they may echo secrets of the request.
        if (status !in 200..299) throw failure(config, statusText)
        if (json == null) throw failure(config, "$statusText, but the response is no JSON object")
        val accessToken = json.text("access_token") ?: throw failure(config, "the response has no access_token")
        val notes = mutableListOf<String>()
        return TokenResponse(
            accessToken = accessToken,
            tokenType = json.text("token_type"),
            expiresIn = json.get("expires_in")?.let { expiresIn(config, it, notes) },
            refreshToken = json.text("refresh_token"),
            idToken = json.text("id_token"),
            notes = notes,
        )
    }

    private fun JsonNode.text(name: String): String? =
        get(name)?.takeIf { !it.isNull }?.let { if (it.isString) it.stringValue() else it.toString() }

    /** Whole seconds from a number or a numeric string (fractions are cut off); anything else is ignored. */
    private fun expiresIn(
        config: OAuth2Config,
        node: JsonNode,
        notes: MutableList<String>,
    ): Duration? {
        if (node.isNull) return null
        val number =
            when {
                node.isNumber -> node.decimalValue()
                node.isString -> node.stringValue().trim().toBigDecimalOrNull()
                else -> null
            }
        val seconds = number?.setScale(0, RoundingMode.DOWN)?.takeIf { it.signum() >= 0 && it <= MAX_EXPIRES_IN }
        if (seconds == null) {
            notes += "The token response for '${config.id}' has an unusable expires_in ($node); it is ignored"
            return null
        }
        return Duration.ofSeconds(seconds.toLong())
    }

    private fun describe(
        e: IOException,
        config: OAuth2Config,
    ): String {
        val target = "${config.tokenUrl.host}:${config.tokenUrl.port.takeIf { it >= 0 } ?: "default port"}"
        return when (e) {
            is HttpConnectTimeoutException -> "connecting to $target timed out"
            is HttpTimeoutException -> "no response within ${timeout.toSeconds()} s"
            is ConnectException -> "cannot connect to $target"
            else -> generateSequence<Throwable>(e) { it.cause }.firstNotNullOfOrNull { it.message } ?: e.toString()
        }
    }

    private fun failure(
        config: OAuth2Config,
        reason: String,
        cause: Throwable? = null,
    ) = OAuth2Exception("Token request for '${config.id}' failed: $reason", cause)

    companion object {
        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(30)
        private val MAX_EXPIRES_IN = BigDecimal.valueOf(Long.MAX_VALUE)
        private val mapper = JsonMapper.builder().build()

        private fun encode(text: String) = URLEncoder.encode(text, Charsets.UTF_8)
    }
}
