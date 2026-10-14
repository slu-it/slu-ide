package dev.sluide.httpclient.send

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsExchange
import com.sun.net.httpserver.HttpsParameters
import com.sun.net.httpserver.HttpsServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.Executors
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlin.io.path.inputStream
import kotlin.io.path.toPath

/**
 * An HTTPS server on 127.0.0.1 with the `localhost` certificate of the test CA (`src/test/resources/ssl`) that requires
 * a client certificate from the test CA and answers every request with the subject of that certificate.
 */
internal class TlsServer : AutoCloseable {
    private val server = HttpsServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

    init {
        val password = "changeit".toCharArray()
        val keyStore = KeyStore.getInstance("PKCS12")
        fixture("server.p12").inputStream().use { keyStore.load(it, password) }
        val keyManagers =
            KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, password) }
        val trustManagers =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trustedCa()) }
        val context =
            SSLContext.getInstance("TLS").apply { init(keyManagers.keyManagers, trustManagers.trustManagers, null) }
        server.httpsConfigurator =
            object : HttpsConfigurator(context) {
                override fun configure(params: HttpsParameters) {
                    params.setSSLParameters(context.defaultSSLParameters.apply { needClientAuth = true })
                }
            }
        server.executor = Executors.newCachedThreadPool { runnable -> Thread(runnable).apply { isDaemon = true } }
        server.createContext("/") { exchange ->
            exchange.use {
                val subject = (exchange as HttpsExchange).sslSession.peerPrincipal.name
                val body = subject.toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.write(body)
            }
        }
        server.start()
    }

    val port get() = server.address.port

    override fun close() = server.stop(0)

    companion object {
        fun fixture(name: String): Path = checkNotNull(TlsServer::class.java.getResource("/ssl/$name")).toURI().toPath()

        /** A trust store with the test CA only. */
        fun trustedCa(): KeyStore {
            val factory = CertificateFactory.getInstance("X.509")
            val ca = fixture("ca.pem").inputStream().use(factory::generateCertificate)
            return KeyStore.getInstance("PKCS12").apply {
                load(null, null)
                setCertificateEntry("ca", ca)
            }
        }
    }
}
