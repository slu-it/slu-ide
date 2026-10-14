package dev.sluide.httpclient.auth

import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.HexFormat

/**
 * HTTP Digest authentication (RFC 7616) for one answer to a `401` challenge: algorithms MD5, MD5-sess, SHA-256 and
 * SHA-256-sess, quality of protection `auth` or none (RFC 2069 style, MD5 and SHA-256 only: `-sess` needs a client
 * nonce, which may only be sent with a qop). [cnonce] makes the client nonce.
 */
internal class DigestAuth(
    private val cnonce: () -> String = ::randomCnonce,
) {
    data class Credentials(
        val username: String,
        val password: String,
    )

    /** A `Digest` challenge; [algorithm] as sent (MD5 when absent), [qop] the offered qualities of protection. */
    data class Challenge(
        val realm: String,
        val nonce: String,
        val opaque: String?,
        val algorithm: String?,
        val qop: List<String>,
    ) {
        private val name = (algorithm ?: "MD5").uppercase()
        internal val hashName = HASHES[name.removeSuffix("-SESS")]
        internal val session = name.endsWith("-SESS")

        /** Why this challenge cannot be answered, or null if it can. */
        val problem: String?
            get() =
                when {
                    hashName == null -> "algorithm $algorithm is not supported"
                    qop.isNotEmpty() && "auth" !in qop -> "only qop ${qop.joinToString(", ")} is offered, not auth"
                    session && qop.isEmpty() -> "$algorithm needs a qop, but the server offers none"
                    else -> null
                }
    }

    /**
     * The `Authorization` value answering [challenge] for a request with [method] to [uri], or null if the challenge
     * has a [Challenge.problem].
     */
    fun authorization(
        challenge: Challenge,
        credentials: Credentials,
        method: String,
        uri: URI,
    ): String? {
        if (challenge.problem != null) return null
        val hashName = challenge.hashName ?: return null
        val qop = if (challenge.qop.isEmpty()) null else "auth"

        fun hash(text: String) =
            HexFormat.of().formatHex(MessageDigest.getInstance(hashName).digest(text.toByteArray()))
        val digestUri = (uri.rawPath?.ifEmpty { null } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
        val clientNonce = cnonce()
        val secret = hash("${credentials.username}:${challenge.realm}:${credentials.password}")
        val ha1 = if (challenge.session) hash("$secret:${challenge.nonce}:$clientNonce") else secret
        val ha2 = hash("$method:$digestUri")
        val response =
            if (qop == null) {
                hash("$ha1:${challenge.nonce}:$ha2")
            } else {
                hash("$ha1:${challenge.nonce}:$NONCE_COUNT:$clientNonce:$qop:$ha2")
            }
        return buildList {
            add("username=${quoted(credentials.username)}")
            add("realm=${quoted(challenge.realm)}")
            add("uri=${quoted(digestUri)}")
            challenge.algorithm?.let { add("algorithm=$it") }
            add("nonce=${quoted(challenge.nonce)}")
            if (qop != null) {
                add("nc=$NONCE_COUNT")
                add("cnonce=${quoted(clientNonce)}")
                add("qop=$qop")
            }
            add("response=${quoted(response)}")
            challenge.opaque?.let { add("opaque=${quoted(it)}") }
        }.joinToString(", ", prefix = "Digest ")
    }

    companion object {
        private const val NONCE_COUNT = "00000001"
        private val HASHES = mapOf("MD5" to "MD5", "SHA-256" to "SHA-256")
        private val DIGEST_AUTHORIZATION = Regex("""Digest\s+(\S+)(?:\s+(.*))?""", RegexOption.IGNORE_CASE)
        private val DIGEST_SCHEME = Regex("""(?:^|,)\s*Digest\s+""", RegexOption.IGNORE_CASE)

        /**
         * The credentials of a written `Digest user password` (or `Digest user:password`) header, else null; also null
         * for a complete Digest answer (`Digest username="…", …`), which is sent as written.
         */
        fun credentials(authorization: String): Credentials? {
            val match = DIGEST_AUTHORIZATION.matchEntire(authorization.trim()) ?: return null
            val (first, password) = match.destructured
            return when {
                '=' in first -> null
                password.isNotEmpty() -> Credentials(first, password)
                ':' in first -> Credentials(first.substringBefore(':'), first.substringAfter(':'))
                else -> null
            }
        }

        /** The `Digest` challenges of the `WWW-Authenticate` [headers] (those with realm and nonce). */
        fun challenges(headers: List<String>): List<Challenge> =
            headers
                .flatMap { header -> DIGEST_SCHEME.findAll(header).map { parameters(header, it.range.last + 1) } }
                .mapNotNull { params ->
                    val realm = params["realm"] ?: return@mapNotNull null
                    val nonce = params["nonce"] ?: return@mapNotNull null
                    val qop =
                        params["qop"]
                            .orEmpty()
                            .split(
                                ',',
                            ).map { it.trim().lowercase() }
                            .filter(String::isNotEmpty)
                    Challenge(realm, nonce, params["opaque"], params["algorithm"], qop)
                }

        /** The best challenge without [Challenge.problem]: SHA-256 before MD5; null if there is none. */
        fun challenge(headers: List<String>): Challenge? =
            challenges(headers)
                .filter { it.problem == null }
                .maxByOrNull { if (it.hashName == "SHA-256") 1 else 0 }

        /** The `name=value` parameters from [start] up to the next scheme (a token without `=`). */
        internal fun parameters(
            text: String,
            start: Int,
        ): Map<String, String> {
            val params = mutableMapOf<String, String>()
            var index = start
            while (index < text.length) {
                while (index < text.length && (text[index].isWhitespace() || text[index] == ',')) index++
                val nameEnd =
                    text.indexOfAny(charArrayOf('=', ',', ' ', '\t'), index).let {
                        if (it <
                            0
                        ) {
                            text.length
                        } else {
                            it
                        }
                    }
                if (nameEnd >= text.length || text[nameEnd] != '=') break
                val name = text.substring(index, nameEnd).lowercase()
                index = nameEnd + 1
                val value = StringBuilder()
                if (index < text.length && text[index] == '"') {
                    index++
                    while (index < text.length && text[index] != '"') {
                        if (text[index] == '\\' && index + 1 < text.length) index++
                        value.append(text[index++])
                    }
                    index++
                } else {
                    while (index < text.length && text[index] != ',') value.append(text[index++])
                }
                params[name] = value.toString().trim()
            }
            return params
        }

        private fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        private fun randomCnonce(): String = HexFormat.of().formatHex(ByteArray(16).also(SecureRandom()::nextBytes))
    }
}
