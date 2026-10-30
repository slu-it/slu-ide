package dev.sluide.codecs.codec

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.CRC32
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** A hash or checksum; [digest] and [hmac] are the JCA names, null where there is none (CRC32). */
enum class HashAlgorithm(
    val title: String,
    val digest: String?,
    val hmac: String?,
) {
    MD5("MD5", "MD5", "HmacMD5"),
    SHA1("SHA-1", "SHA-1", "HmacSHA1"),
    SHA256("SHA-256", "SHA-256", "HmacSHA256"),
    SHA384("SHA-384", "SHA-384", "HmacSHA384"),
    SHA512("SHA-512", "SHA-512", "HmacSHA512"),
    SHA3_256("SHA3-256", "SHA3-256", "HmacSHA3-256"),
    CRC32("CRC32", null, null),
}

/** How a hash value is written. */
enum class HashOutput(
    val title: String,
) {
    HEX("Hex"),
    BASE64("Base64"),
}

data class HashValue(
    val algorithm: HashAlgorithm,
    val value: String,
)

internal object Hashes {
    /** Plain hashes of [bytes]; with a non-empty [key] the HMACs of the algorithms that have one. */
    fun compute(
        bytes: ByteArray,
        key: ByteArray?,
        output: HashOutput,
    ): List<HashValue> {
        val keyed = key != null && key.isNotEmpty()
        return HashAlgorithm.entries.mapNotNull { algorithm ->
            val raw =
                when {
                    keyed -> algorithm.hmac?.let { hmac(it, key, bytes) }
                    algorithm.digest != null -> MessageDigest.getInstance(algorithm.digest).digest(bytes)
                    else -> crc32(bytes)
                }
            raw?.let { HashValue(algorithm, write(it, output)) }
        }
    }

    private fun hmac(
        name: String,
        key: ByteArray,
        bytes: ByteArray,
    ): ByteArray {
        val mac = Mac.getInstance(name)
        mac.init(SecretKeySpec(key, name))
        return mac.doFinal(bytes)
    }

    private fun crc32(bytes: ByteArray): ByteArray {
        val crc = CRC32().apply { update(bytes) }
        return ByteBuffer.allocate(Int.SIZE_BYTES).putInt(crc.value.toInt()).array()
    }

    private fun write(
        raw: ByteArray,
        output: HashOutput,
    ): String =
        when (output) {
            HashOutput.HEX -> raw.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            HashOutput.BASE64 -> Base64.getEncoder().encodeToString(raw)
        }
}
