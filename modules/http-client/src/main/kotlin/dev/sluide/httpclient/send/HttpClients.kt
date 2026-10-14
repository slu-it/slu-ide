package dev.sluide.httpclient.send

import java.net.CookieManager
import java.net.CookiePolicy
import java.net.ProxySelector
import java.net.http.HttpClient
import java.time.Duration
import javax.net.ssl.SSLContext

/**
 * The JDK [HttpClient]s of one window, one per [ClientKey], sharing one in-memory cookie jar. Proxies come from [proxy],
 * by default the JDK's default selector (`http.proxyHost`, `https.proxyHost`, `http.nonProxyHosts`). [close] stops all
 * clients and aborts their exchanges; no client is handed out after that.
 */
internal class HttpClients(
    private val proxy: ProxySelector? = null,
) : AutoCloseable {
    /** [sslContext] is compared by identity, so callers should reuse the context of one configuration. */
    data class ClientKey(
        val followRedirects: Boolean,
        val cookieJar: Boolean,
        val connectTimeout: Duration,
        val sslContext: SSLContext? = null,
    )

    private val cookies = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
    private val lock = Any()
    private val clients = HashMap<ClientKey, HttpClient>()
    private var closed = false

    /** The client for [key]; throws [IllegalStateException] after [close]. */
    fun client(key: ClientKey): HttpClient =
        synchronized(lock) {
            check(!closed) { "The HTTP clients are closed" }
            clients.getOrPut(key) { create(key) }
        }

    /** Stops the clients of [sslContext] after their running exchanges, e.g. because the context was replaced. */
    fun evict(sslContext: SSLContext) {
        val stopped =
            synchronized(lock) {
                val keys = clients.keys.filter { it.sslContext === sslContext }
                keys.mapNotNull { clients.remove(it) }
            }
        stopped.forEach(HttpClient::shutdown)
    }

    fun clearCookies() {
        cookies.cookieStore.removeAll()
    }

    override fun close() {
        val stopped =
            synchronized(lock) {
                closed = true
                clients.values.toList().also { clients.clear() }
            }
        stopped.forEach(HttpClient::shutdownNow)
    }

    private fun create(key: ClientKey): HttpClient {
        val builder =
            HttpClient
                .newBuilder()
                .followRedirects(if (key.followRedirects) HttpClient.Redirect.NORMAL else HttpClient.Redirect.NEVER)
                .connectTimeout(key.connectTimeout)
        proxy?.let(builder::proxy)
        if (key.cookieJar) builder.cookieHandler(cookies)
        key.sslContext?.let(builder::sslContext)
        return builder.build()
    }

    companion object {
        /**
         * The JDK client refuses a written `Host` header unless this property lists it; the JDK reads it only once,
         * when its HTTP classes load, so it is set here before the first client is built (the launcher sets it too).
         */
        const val ALLOW_RESTRICTED_HEADERS = "jdk.httpclient.allowRestrictedHeaders"

        init {
            val allowed = System.getProperty(ALLOW_RESTRICTED_HEADERS)
            if (allowed.isNullOrBlank()) {
                System.setProperty(ALLOW_RESTRICTED_HEADERS, "host")
            } else if (allowed.split(',').none { it.trim().equals("host", ignoreCase = true) }) {
                System.setProperty(ALLOW_RESTRICTED_HEADERS, "$allowed,host")
            }
        }
    }
}
