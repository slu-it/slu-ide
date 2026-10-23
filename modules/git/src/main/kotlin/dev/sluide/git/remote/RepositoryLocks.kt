package dev.sluide.git.remote

import dev.sluide.git.GitCancellation
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Serialises the changes to a cached repository: one lock per repository folder within the JVM (shared by all caches,
 * so that two on one folder never take its file lock twice, which Java refuses), and a file lock on `<folder>.lock`
 * beside it for other running apps. Folders are told apart by their path in lower case, so that two
 * spellings of one folder on a case-insensitive file system share the lock.
 */
internal object RepositoryLocks {
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    /**
     * Runs [action] holding the locks of [directory] (its parent folders are created); `null` without running it if
     * [cancellation] is cancelled or the thread interrupted while waiting (the interrupt is kept). Throws IOException
     * if the lock file cannot be created.
     */
    fun <T> withLock(
        directory: Path,
        cancellation: GitCancellation,
        action: () -> T,
    ): T? {
        val lock = locks.computeIfAbsent(directory.toString().lowercase()) { ReentrantLock() }
        try {
            while (!lock.tryLock(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                if (cancellation.cancelled) return null
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        }
        try {
            Files.createDirectories(directory.parent)
            val lockFile = directory.resolveSibling("${directory.fileName}.lock")
            FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                val fileLock = fileLock(channel, cancellation) ?: return null
                return fileLock.use { action() }
            }
        } finally {
            lock.unlock()
        }
    }

    private fun fileLock(
        channel: FileChannel,
        cancellation: GitCancellation,
    ): FileLock? {
        while (true) {
            channel.tryLock()?.let { return it }
            if (cancellation.cancelled) return null
            try {
                Thread.sleep(POLL_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
    }

    private const val POLL_MILLIS = 100L
}
