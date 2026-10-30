package dev.sluide.codecs.detect

/** The binary file formats recognised by their first bytes. */
enum class FileKind(
    val title: String,
    val mime: String,
) {
    PNG("PNG image", "image/png"),
    JPEG("JPEG image", "image/jpeg"),
    GIF("GIF image", "image/gif"),
    WEBP("WebP image", "image/webp"),
    PDF("PDF document", "application/pdf"),
    ZIP("ZIP archive", "application/zip"),
    GZIP("gzip data", "application/gzip"),
    ZLIB("zlib data", "application/zlib"),
    CLASS("Java class file", "application/java-vm"),
    ;

    companion object {
        private val zlibSecondBytes = setOf(0x01, 0x5e, 0x9c, 0xda)
        private const val WEBP_HEADER = 12

        fun of(bytes: ByteArray): FileKind? =
            when {
                startsWith(bytes, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) -> PNG
                startsWith(bytes, 0xff, 0xd8, 0xff) -> JPEG
                isGif(bytes) -> GIF
                isWebp(bytes) -> WEBP
                startsWith(bytes, '%'.code, 'P'.code, 'D'.code, 'F'.code, '-'.code) -> PDF
                startsWith(bytes, 'P'.code, 'K'.code, 0x03, 0x04) -> ZIP
                startsWith(bytes, 0x1f, 0x8b) -> GZIP
                bytes.size >= 2 && bytes[0] == 0x78.toByte() && (bytes[1].toInt() and 0xff) in zlibSecondBytes -> ZLIB
                startsWith(bytes, 0xca, 0xfe, 0xba, 0xbe) -> CLASS
                else -> null
            }

        private fun startsWith(
            bytes: ByteArray,
            vararg prefix: Int,
        ): Boolean = bytes.size >= prefix.size && prefix.indices.all { (bytes[it].toInt() and 0xff) == prefix[it] }

        private fun isGif(bytes: ByteArray): Boolean =
            startsWith(bytes, 'G'.code, 'I'.code, 'F'.code, '8'.code) &&
                bytes.size > 4 &&
                (bytes[4] == '7'.code.toByte() || bytes[4] == '9'.code.toByte())

        private fun isWebp(bytes: ByteArray): Boolean =
            bytes.size >= WEBP_HEADER &&
                startsWith(bytes, 'R'.code, 'I'.code, 'F'.code, 'F'.code) &&
                bytes[8] == 'W'.code.toByte() &&
                bytes[9] == 'E'.code.toByte() &&
                bytes[10] == 'B'.code.toByte() &&
                bytes[11] == 'P'.code.toByte()
    }
}
