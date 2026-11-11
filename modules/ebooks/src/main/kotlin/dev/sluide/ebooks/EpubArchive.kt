package dev.sluide.ebooks

import java.io.InputStream
import java.util.zip.ZipFile

/** Zip limits; the real ones are the defaults, tests pass small ones. */
internal class EpubLimits(
    val maxEntries: Int = 10_000,
    val maxTotalBytes: Long = 512L * 1024 * 1024,
    val maxEntryBytes: Long = 64L * 1024 * 1024,
)

internal class TooLargeException : RuntimeException("too large")

/**
 * An open EPUB zip. Entry names are only looked up as keys, nothing is ever written to disk. The limits count the
 * bytes actually inflated, not what the headers claim ([checkLimits] inflates every entry once and discards it).
 */
internal class EpubArchive(
    private val zip: ZipFile,
    private val limits: EpubLimits,
) : AutoCloseable {
    /** Throws [TooLargeException] when a limit is exceeded. */
    fun checkLimits() {
        if (zip.size() > limits.maxEntries) throw TooLargeException()
        var total = 0L
        val buffer = ByteArray(64 * 1024)
        for (entry in zip.entries()) {
            if (entry.isDirectory) continue
            zip.getInputStream(entry).use { input ->
                var entryBytes = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    entryBytes += n
                    total += n
                    if (entryBytes > limits.maxEntryBytes || total > limits.maxTotalBytes) throw TooLargeException()
                }
            }
        }
    }

    fun has(name: String): Boolean = zip.getEntry(name)?.isDirectory == false

    /** The bytes of an entry, or null when there is none. */
    fun bytes(name: String): ByteArray? {
        val entry = zip.getEntry(name)?.takeIf { !it.isDirectory } ?: return null
        return zip.getInputStream(entry).use { it.readLimited(limits.maxEntryBytes) }
    }

    override fun close() = zip.close()

    private fun InputStream.readLimited(max: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            total += n
            if (total > max) throw TooLargeException()
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
