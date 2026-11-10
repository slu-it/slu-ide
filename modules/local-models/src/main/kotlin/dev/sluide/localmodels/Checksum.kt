package dev.sluide.localmodels

import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat

/** A digest algorithm a [Checksum] names: its name in SRI strings, its JDK (and message) name and its size in bytes. */
enum class DigestAlgorithm(
    val sriName: String,
    val javaName: String,
    val bytes: Int,
) {
    SHA256("sha256", "SHA-256", 32),
    SHA384("sha384", "SHA-384", 48),
    SHA512("sha512", "SHA-512", 64),
}

private val SHA256_HEX = Regex("[0-9a-fA-F]{64}")

/**
 * What a downloaded file must hash to: a SHA-256 in hex (pins, GitHub's `digest`, model catalogues) or a Subresource
 * Integrity value such as npm's `dist.integrity` (`sha512-<base64>`). Nothing is checked on construction: [wellFormed]
 * says whether the value can be a digest of its algorithm at all, so that an install can refuse it readably.
 */
sealed interface Checksum {
    val algorithm: DigestAlgorithm

    /** Whether the value has the form and size of a digest of [algorithm]. */
    val wellFormed: Boolean

    /** Whether [digest], computed with [algorithm], is this checksum. */
    fun matches(digest: ByteArray): Boolean

    /** A SHA-256 as 64 hex digits, compared in any case. */
    data class Sha256(
        val hex: String,
    ) : Checksum {
        override val algorithm: DigestAlgorithm get() = DigestAlgorithm.SHA256

        override val wellFormed: Boolean get() = SHA256_HEX.matches(hex)

        override fun matches(digest: ByteArray) = HexFormat.of().formatHex(digest).equals(hex, ignoreCase = true)
    }

    /** One Subresource Integrity hash, `<algorithm>-<base64>`; [toString] gives that form. */
    data class Sri(
        override val algorithm: DigestAlgorithm,
        val base64: String,
    ) : Checksum {
        override val wellFormed: Boolean get() = decoded()?.size == algorithm.bytes

        override fun matches(digest: ByteArray) = decoded()?.let { MessageDigest.isEqual(it, digest) } == true

        private fun decoded(): ByteArray? =
            try {
                Base64.getDecoder().decode(base64)
            } catch (_: IllegalArgumentException) {
                null
            }

        override fun toString() = "${algorithm.sriName}-$base64"
    }

    companion object {
        /**
         * [text] as one SRI hash (`sha512-<base64>`, surrounding blanks ignored), or null for anything else: an
         * unknown algorithm, several hashes, options (`?…`). Whether the base64 is a digest says [wellFormed].
         */
        fun sri(text: String): Sri? {
            val (name, value) = text.trim().split('-', limit = 2).takeIf { it.size == 2 } ?: return null
            val algorithm = DigestAlgorithm.entries.firstOrNull { it.sriName == name } ?: return null
            if (value.isEmpty() || value.any { it.isWhitespace() || it == '?' }) return null
            return Sri(algorithm, value)
        }
    }
}
