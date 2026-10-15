package dev.sluide.httpclient.send

import dev.sluide.httpclient.auth.DigestAuth
import dev.sluide.httpclient.parse.HttpVersion
import java.net.URI
import javax.net.ssl.SSLContext

/**
 * A request ready to send: variables resolved, files read, body as bytes. [notes] tell what was changed or left out;
 * [digest] holds the credentials of a written `Authorization: Digest user password`, used to answer a challenge;
 * [sslContext] is the one of the environment's `SSLConfiguration` (null: the JDK's default). [secrets] are values that
 * must not be shown, e.g. OAuth2 tokens put in by `$auth.token`.
 */
internal class PreparedRequest(
    val uri: URI,
    val method: String,
    val version: HttpVersion,
    val headers: List<Pair<String, String>>,
    val body: ByteArray,
    val notes: List<String>,
    val digest: DigestAuth.Credentials? = null,
    val sslContext: SSLContext? = null,
    val secrets: List<String> = emptyList(),
) {
    fun withSslContext(context: SSLContext?) =
        PreparedRequest(uri, method, version, headers, body, notes, digest, context, secrets)

    fun withSecrets(values: List<String>) =
        PreparedRequest(uri, method, version, headers, body, notes, digest, sslContext, values)

    fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
}

/** The request cannot be built, e.g. because of an invalid URL or a missing body file. */
internal class RequestBuildException(
    message: String,
) : RuntimeException(message)
