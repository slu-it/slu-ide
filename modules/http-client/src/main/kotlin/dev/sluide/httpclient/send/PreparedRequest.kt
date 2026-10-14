package dev.sluide.httpclient.send

import dev.sluide.httpclient.auth.DigestAuth
import dev.sluide.httpclient.parse.HttpVersion
import java.net.URI

/**
 * A request ready to send: variables resolved, files read, body as bytes. [notes] tell what was changed or left out;
 * [digest] holds the credentials of a written `Authorization: Digest user password`, used to answer a challenge.
 */
internal class PreparedRequest(
    val uri: URI,
    val method: String,
    val version: HttpVersion,
    val headers: List<Pair<String, String>>,
    val body: ByteArray,
    val notes: List<String>,
    val digest: DigestAuth.Credentials? = null,
) {
    fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
}

/** The request cannot be built, e.g. because of an invalid URL or a missing body file. */
internal class RequestBuildException(
    message: String,
) : RuntimeException(message)
