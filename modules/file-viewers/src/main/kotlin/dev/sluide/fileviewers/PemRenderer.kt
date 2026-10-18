package dev.sluide.fileviewers

import dev.sluide.fileviewers.preview.HtmlPage
import org.jsoup.nodes.Entities
import java.io.ByteArrayInputStream
import java.security.Key
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.DSAKey
import java.security.interfaces.ECKey
import java.security.interfaces.EdECKey
import java.security.interfaces.RSAKey
import java.security.interfaces.XECKey
import java.security.spec.NamedParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import javax.crypto.EncryptedPrivateKeyInfo
import javax.security.auth.x500.X500Principal

/**
 * Renders the blocks of a PEM file (`-----BEGIN label-----` … `-----END label-----`) as a page of details for the
 * preview: certificates with subject, issuer, serial number, validity (a badge: valid, expiring within
 * [EXPIRY_WARNING], expired), public key, alternative names, key usage, CA flag and SHA-256 fingerprint, each of a
 * chain on its own; keys only with their type and whether they are encrypted, never their material; certificate
 * requests with their subject and key. A block that cannot be read shows its label and why. All text is escaped.
 */
internal object PemRenderer {
    /** A certificate within this time before it expires gets a warning badge. */
    val EXPIRY_WARNING: Duration = Duration.ofDays(30)

    /** What a PEM block holds: its [title], [fields], a [badge] for certificates, or the [error] reading it. */
    data class Details(
        val title: String,
        val fields: List<Field> = emptyList(),
        val badge: Badge? = null,
        val error: String? = null,
    )

    /** One detail; [mono] for hex strings, shown in a fixed-width font. */
    data class Field(
        val name: String,
        val value: String,
        val mono: Boolean = false,
    )

    /** A certificate's validity at a time: [kind] is `valid`, `expiring` or `expired` (also for not yet valid). */
    data class Badge(
        val text: String,
        val kind: String,
    )

    /** A block of a PEM file: its [label] (`CERTIFICATE`), its [headers] (`Proc-Type`) and its base64 [content]. */
    class Block(
        val label: String,
        val headers: Map<String, String>,
        val content: String,
    )

    private val stylesheet = HtmlPage.resource("preview.css")
    private val pemStylesheet = HtmlPage.resource("pem.css")
    private val script = HtmlPage.resource("preview.js")

    /** The page for [text], with certificates' validity at [now]. */
    fun render(
        text: String,
        dark: Boolean,
        now: Instant = Instant.now(),
    ): String {
        val scan = scan(text)
        val details = describe(scan.blocks, now)
        val unfinished = scan.unfinished
        return HtmlPage.render(dark, listOf(stylesheet, pemStylesheet), listOf(script), listOf("pem")) {
            if (details.isEmpty()) append("<p class=\"pem-note\">No PEM blocks found.</p>\n")
            for (block in details) {
                append("<section class=\"pem-block\">\n<h2>").append(escape(block.title))
                block.badge?.let {
                    append(" <span class=\"pem-badge ").append(it.kind).append("\">").append(escape(it.text))
                    append("</span>")
                }
                append("</h2>\n")
                val error = block.error?.let(::escape)
                if (error != null) append("<p class=\"pem-error\">Cannot be read: ").append(error).append("</p>\n")
                if (block.fields.isNotEmpty()) {
                    append("<table class=\"pem-fields\">\n")
                    for (field in block.fields) {
                        append("<tr><th>").append(escape(field.name)).append("</th>")
                        append(if (field.mono) "<td class=\"mono\">" else "<td>")
                        append(escape(field.value)).append("</td></tr>\n")
                    }
                    append("</table>\n")
                }
                append("</section>\n")
            }
            if (unfinished > 0) {
                val blocks = if (unfinished == 1) "1 block has" else "$unfinished blocks have"
                append("<p class=\"pem-note\">").append(blocks).append(" no matching END line.</p>\n")
            }
        }
    }

    /** The blocks of [text] in their order; text outside them is ignored. */
    fun blocks(text: String): List<Block> = scan(text).blocks

    /** The [blocks] of a text and how many of its BEGIN lines outside them have no matching END line. */
    private class Scan(
        val blocks: List<Block>,
        val unfinished: Int,
    )

    /**
     * Finds the blocks line by line: a `-----BEGIN label-----` line up to the next `-----END label-----` line after
     * it, each marker on a line of its own. Linear in the text's length (plus a binary search per BEGIN line), also
     * for many BEGIN lines without an END, which a back-referencing regex would search to the end each time.
     */
    private fun scan(text: String): Scan {
        val lines = text.lines().map { it.trim() }
        // For each label, the lines (ascending) with its END marker.
        val ends = HashMap<String, MutableList<Int>>()
        for ((index, line) in lines.withIndex()) {
            val label = END.matchEntire(line)?.groupValues?.get(1) ?: continue
            ends.getOrPut(label) { mutableListOf() } += index
        }
        val blocks = mutableListOf<Block>()
        var unfinished = 0
        var index = 0
        while (index < lines.size) {
            val label = BEGIN.matchEntire(lines[index])?.groupValues?.get(1)
            val end = label?.let { ends[it]?.firstAfter(index) }
            when {
                label == null -> {
                    index++
                }

                end == null -> {
                    unfinished++
                    index++
                }

                else -> {
                    blocks += block(label, lines.subList(index + 1, end))
                    index = end + 1
                }
            }
        }
        return Scan(blocks, unfinished)
    }

    /** The first of these ascending line numbers after [line], if any. */
    private fun List<Int>.firstAfter(line: Int): Int? {
        val found = binarySearch(line)
        val next = if (found >= 0) found + 1 else -found - 1
        return getOrNull(next)
    }

    private fun block(
        label: String,
        lines: List<String>,
    ): Block {
        val body = lines.dropWhile { it.isEmpty() }
        val headers = body.takeWhile { HEADER.matches(it) }
        return Block(
            label,
            headers.associate { it.substringBefore(':') to it.substringAfter(':').trim() },
            body.drop(headers.size).joinToString(""),
        )
    }

    /** The details of each block of [text], certificates' validity at [now]. */
    fun describe(
        text: String,
        now: Instant,
    ): List<Details> = describe(blocks(text), now)

    private fun describe(
        blocks: List<Block>,
        now: Instant,
    ): List<Details> {
        val certificates = blocks.count { it.label in CERTIFICATES }
        var certificate = 0
        return blocks.map { block ->
            try {
                when (block.label) {
                    in CERTIFICATES -> {
                        certificate++
                        val ofChain = if (certificates > 1) " $certificate of $certificates" else ""
                        certificate(decode(block), "Certificate$ofChain", now)
                    }

                    "PRIVATE KEY" -> {
                        privateKey(decode(block))
                    }

                    "ENCRYPTED PRIVATE KEY" -> {
                        encryptedPrivateKey(decode(block))
                    }

                    "RSA PRIVATE KEY", "EC PRIVATE KEY", "DSA PRIVATE KEY" -> {
                        traditionalPrivateKey(block)
                    }

                    "OPENSSH PRIVATE KEY" -> {
                        openSshPrivateKey(decode(block))
                    }

                    "PUBLIC KEY" -> {
                        publicKey(decode(block))
                    }

                    "RSA PUBLIC KEY" -> {
                        Details("Public key", listOf(Field("Type", "RSA public key (PKCS #1)")))
                    }

                    "CERTIFICATE REQUEST", "NEW CERTIFICATE REQUEST" -> {
                        certificateRequest(decode(block))
                    }

                    else -> {
                        Details(block.label, listOf(Field("Content", "Not shown in detail")))
                    }
                }
            } catch (e: Exception) {
                Details(block.label, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun decode(block: Block): ByteArray = Base64.getDecoder().decode(block.content)

    private fun certificate(
        der: ByteArray,
        title: String,
        now: Instant,
    ): Details {
        val certificate =
            CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        val subject = certificate.subjectX500Principal.name
        val issuer = certificate.issuerX500Principal.name
        val fields =
            mutableListOf(
                Field("Subject", subject),
                Field("Issuer", if (isSelfSigned(certificate)) "$issuer (self-signed)" else issuer),
                Field("Serial number", hex(certificate.serialNumber.toByteArray().withoutSignByte()), mono = true),
                Field("Valid from", DATE.format(certificate.notBefore.toInstant())),
                Field("Valid until", DATE.format(certificate.notAfter.toInstant())),
                Field("Public key", describeKey(certificate.publicKey)),
                Field("Signature algorithm", certificate.sigAlgName),
            )
        certificate.subjectAlternativeNames?.let { names ->
            fields += Field("Alternative names", names.joinToString(", ", transform = ::alternativeName))
        }
        certificate.keyUsage?.let { bits ->
            fields +=
                Field("Key usage", KEY_USAGES.filterIndexed { i, _ -> bits.getOrElse(i) { false } }.joinToString(", "))
        }
        certificate.extendedKeyUsage?.let { usages ->
            fields += Field("Extended key usage", usages.joinToString(", ") { EXTENDED_KEY_USAGES[it] ?: it })
        }
        val pathLength = certificate.basicConstraints
        fields +=
            Field(
                "Certificate authority",
                when (pathLength) {
                    -1 -> "No"
                    Int.MAX_VALUE -> "Yes"
                    else -> "Yes, path length $pathLength"
                },
            )
        fields += Field("SHA-256 fingerprint", hex(MessageDigest.getInstance("SHA-256").digest(der)), mono = true)
        val name = commonName(subject) ?: subject
        return Details("$title: $name", fields, badge(certificate, now))
    }

    /**
     * A subject alternative name as `X509Certificate` gives it: its type, then a string, or for other names, X.400
     * addresses and EDI party names their DER bytes, which are not shown.
     */
    internal fun alternativeName(name: List<*>): String {
        val type = SAN_TYPES[name.getOrNull(0)] ?: "Unknown type"
        return when (val value = name.getOrNull(1)) {
            is String -> "$type: $value"
            else -> "$type (not shown)"
        }
    }

    private fun badge(
        certificate: X509Certificate,
        now: Instant,
    ): Badge {
        val from = certificate.notBefore.toInstant()
        val until = certificate.notAfter.toInstant()
        val left = Duration.between(now, until)
        return when {
            now.isAfter(until) -> {
                Badge("Expired", "expired")
            }

            now.isBefore(from) -> {
                Badge("Not yet valid", "expired")
            }

            left <= EXPIRY_WARNING -> {
                val days = left.toDays()
                Badge(
                    when (days) {
                        0L -> "Expires today"
                        1L -> "Expires in 1 day"
                        else -> "Expires in $days days"
                    },
                    "expiring",
                )
            }

            else -> {
                Badge("Valid", "valid")
            }
        }
    }

    private fun isSelfSigned(certificate: X509Certificate): Boolean =
        certificate.subjectX500Principal == certificate.issuerX500Principal &&
            runCatching { certificate.verify(certificate.publicKey) }.isSuccess

    private fun privateKey(der: ByteArray): Details {
        val key =
            KEY_ALGORITHMS.firstNotNullOfOrNull { algorithm ->
                runCatching { KeyFactory.getInstance(algorithm).generatePrivate(PKCS8EncodedKeySpec(der)) }.getOrNull()
            }
        return Details(
            "Private key",
            listOf(
                Field("Type", "Private key (PKCS #8)"),
                Field("Algorithm", key?.let(::describeKey) ?: "Unknown"),
                Field("Encrypted", "No"),
            ),
        )
    }

    private fun encryptedPrivateKey(der: ByteArray): Details =
        Details(
            "Private key",
            listOf(
                Field("Type", "Private key (PKCS #8)"),
                Field("Encrypted", "Yes (${EncryptedPrivateKeyInfo(der).algName})"),
            ),
        )

    /** OpenSSL's own format, encrypted as its `Proc-Type` header says. */
    private fun traditionalPrivateKey(block: Block): Details {
        val encrypted = block.headers["Proc-Type"]?.contains("ENCRYPTED") == true
        val cipher = block.headers["DEK-Info"]?.substringBefore(',')
        return Details(
            "Private key",
            listOf(
                Field("Type", "${block.label.substringBefore(' ')} private key (OpenSSL)"),
                Field(
                    "Encrypted",
                    if (!encrypted) {
                        "No"
                    } else if (cipher != null) {
                        "Yes ($cipher)"
                    } else {
                        "Yes"
                    },
                ),
            ),
        )
    }

    /** `openssh-key-v1`: cipher, key derivation and its options, the number of keys, then the first public key. */
    private fun openSshPrivateKey(bytes: ByteArray): Details {
        require(bytes.decodeToString(0, OPENSSH_MAGIC.length) == OPENSSH_MAGIC) { "Not an OpenSSH private key" }
        var position = OPENSSH_MAGIC.length

        fun string(): ByteArray {
            require(position + 4 <= bytes.size) { "Truncated OpenSSH private key" }
            val length = (0 until 4).fold(0) { value, i -> (value shl 8) or (bytes[position + i].toInt() and 0xff) }
            require(length >= 0 && length <= bytes.size - position - 4) { "Truncated OpenSSH private key" }
            return bytes.copyOfRange(position + 4, position + 4 + length).also { position += 4 + length }
        }
        val cipher = string().decodeToString()
        repeat(2) { string() }
        position += 4
        val publicKey = string()
        val type = sshString(publicKey)
        return Details(
            "Private key",
            listOf(
                Field("Type", "OpenSSH private key ($type)"),
                Field("Encrypted", if (cipher == "none") "No" else "Yes ($cipher)"),
            ),
        )
    }

    private fun publicKey(der: ByteArray): Details {
        val key =
            KEY_ALGORITHMS.firstNotNullOfOrNull { algorithm ->
                runCatching { KeyFactory.getInstance(algorithm).generatePublic(X509EncodedKeySpec(der)) }.getOrNull()
            }
        return Details("Public key", listOf(Field("Algorithm", key?.let(::describeKey) ?: "Unknown")))
    }

    /** PKCS #10: a sequence of the request's info (version, subject, public key, …), algorithm and signature. */
    private fun certificateRequest(der: ByteArray): Details {
        val request = Der(der).next()
        val info = Der(der, request.contentStart).next()
        val fields = Der(der, info.contentStart)
        fields.next()
        val subject = fields.next()
        val publicKey = fields.next()
        val name = X500Principal(der.copyOfRange(subject.start, subject.end)).name
        val key =
            KEY_ALGORITHMS.firstNotNullOfOrNull { algorithm ->
                runCatching {
                    KeyFactory
                        .getInstance(algorithm)
                        .generatePublic(X509EncodedKeySpec(der.copyOfRange(publicKey.start, publicKey.end)))
                }.getOrNull()
            }
        return Details(
            "Certificate request: ${commonName(name) ?: name}",
            listOf(Field("Subject", name), Field("Public key", key?.let(::describeKey) ?: "Unknown")),
        )
    }

    /** The algorithm and size of [key], nothing of its material. */
    private fun describeKey(key: Key): String =
        when (key) {
            is RSAKey -> "RSA, ${key.modulus.bitLength()} bits"
            is ECKey -> "EC, ${key.params.order.bitLength()} bits"
            is EdECKey -> key.params.name
            is XECKey -> (key.params as? NamedParameterSpec)?.name ?: key.algorithm
            is DSAKey -> "DSA, ${key.params.p.bitLength()} bits"
            else -> key.algorithm
        }

    /** The first (most specific) `CN` of an RFC 2253 [name], unescaped. */
    private fun commonName(name: String): String? =
        COMMON_NAME
            .findAll(name)
            .firstOrNull()
            ?.groupValues
            ?.get(1)
            ?.replace(Regex("""\\(.)"""), "$1")

    private fun hex(bytes: ByteArray): String = bytes.joinToString(":") { "%02X".format(it) }

    /** Without the leading zero byte that keeps a positive number positive in two's complement (`BigInteger`). */
    private fun ByteArray.withoutSignByte(): ByteArray {
        val hasSignByte = size > 1 && this[0] == 0.toByte()
        return if (hasSignByte) copyOfRange(1, size) else this
    }

    private fun escape(text: String) = Entities.escape(text)

    /** The markers of the blocks the HTTP client reads too (`SslContexts`); that module is not a dependency. */
    private val BEGIN = Regex("""-----BEGIN ([A-Z0-9 ]+)-----""")
    private val END = Regex("""-----END ([A-Z0-9 ]+)-----""")
    private val HEADER = Regex("""[A-Za-z][\w-]*:.*""")
    private val COMMON_NAME = Regex("""(?:^|,)CN=((?:\\.|[^,\\])*)""")
    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC)
    private const val OPENSSH_MAGIC = "openssh-key-v1\u0000"

    private val CERTIFICATES = setOf("CERTIFICATE", "TRUSTED CERTIFICATE", "X509 CERTIFICATE")
    private val KEY_ALGORITHMS = listOf("RSA", "EC", "EdDSA", "XDH", "DSA", "RSASSA-PSS", "ML-DSA", "ML-KEM")
    private val SAN_TYPES =
        mapOf(
            0 to "Other name",
            1 to "Email",
            2 to "DNS",
            3 to "X.400 address",
            4 to "Directory",
            5 to "EDI party name",
            6 to "URI",
            7 to "IP",
            8 to "Registered ID",
        )
    private val KEY_USAGES =
        listOf(
            "Digital signature",
            "Non-repudiation",
            "Key encipherment",
            "Data encipherment",
            "Key agreement",
            "Certificate signing",
            "CRL signing",
            "Encipher only",
            "Decipher only",
        )
    private val EXTENDED_KEY_USAGES =
        mapOf(
            "1.3.6.1.5.5.7.3.1" to "TLS server",
            "1.3.6.1.5.5.7.3.2" to "TLS client",
            "1.3.6.1.5.5.7.3.3" to "Code signing",
            "1.3.6.1.5.5.7.3.4" to "Email",
            "1.3.6.1.5.5.7.3.8" to "Time stamping",
            "1.3.6.1.5.5.7.3.9" to "OCSP signing",
        )
}

/** Reads DER elements of [bytes] one after the other from [position]: just enough for certificate requests. */
private class Der(
    private val bytes: ByteArray,
    private var position: Int = 0,
) {
    /** An element: its encoding from [start] to [end], the content from [contentStart]. */
    class Element(
        val start: Int,
        val contentStart: Int,
        val end: Int,
    )

    fun next(): Element {
        val start = position
        require(position + 2 <= bytes.size) { "Truncated DER" }
        position++
        var length = bytes[position++].toInt() and 0xff
        if (length and 0x80 != 0) {
            val count = length and 0x7f
            require(count in 1..3 && position + count <= bytes.size) { "Unsupported DER length" }
            length = 0
            repeat(count) { length = (length shl 8) or (bytes[position++].toInt() and 0xff) }
        }
        val contentStart = position
        position += length
        require(position <= bytes.size) { "Truncated DER" }
        return Element(start, contentStart, position)
    }
}

/** The first SSH string of [bytes] (a 4-byte length, then the text), e.g. a key's type. */
private fun sshString(bytes: ByteArray): String {
    require(bytes.size >= 4) { "Truncated OpenSSH key" }
    val length = (0 until 4).fold(0) { value, i -> (value shl 8) or (bytes[i].toInt() and 0xff) }
    require(length in 0..bytes.size - 4) { "Truncated OpenSSH key" }
    return bytes.decodeToString(4, 4 + length)
}
