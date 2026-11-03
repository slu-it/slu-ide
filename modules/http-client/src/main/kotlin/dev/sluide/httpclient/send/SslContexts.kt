package dev.sluide.httpclient.send

import dev.sluide.httpclient.env.SslConfig
import java.io.IOException
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import javax.net.ssl.SSLContext
import kotlin.io.path.getLastModifiedTime

/**
 * The [SSLContext]s of one window, one per `SSLConfiguration` (and modification time of its files), so that
 * `HttpClients.ClientKey`s of equal configurations are equal. Contexts that are replaced (because a file changed) or
 * cleared go to [discarded], e.g. to stop their HTTP clients.
 *
 * [passphrase] is asked at most once per pair of certificate and key files, when a context is first created, and
 * remembered for the session; it is asked again only if it turned out wrong. [context] blocks while that prompt is
 * open, so never call it on the FX thread; a prompt shown on the FX thread must not wait for this class. Only calls
 * for the same files wait for each other, interruptibly; [clear] never waits for a prompt.
 *
 * [trustStore] holds the certificates to trust when verifying hosts; null means the JDK's (`cacerts`).
 */
internal class SslContexts(
    private val passphrase: (SslConfig) -> CharArray? = { null },
    private val trustStore: KeyStore? = null,
    private val discarded: (SSLContext) -> Unit = {},
) {
    private data class Key(
        val config: SslConfig,
        val modified: List<FileTime?>,
    )

    private val contexts = ConcurrentHashMap<Key, SSLContext>()
    private val passphrases = ConcurrentHashMap<List<Path>, CharArray>()

    /** One lock per pair of files, held while creating their context (and prompting for their passphrase). */
    private val fileLocks = ConcurrentHashMap<List<Path>, ReentrantLock>()

    /** Guards changes of [contexts], [passphrases] and [generation]; held only briefly, never around a prompt. */
    private val storeLock = Any()

    /** Counts [clear]s, so that a context created across one is not stored. */
    private var generation = 0L

    fun context(config: SslConfig): SSLContext {
        val files = listOfNotNull(config.clientCertificate, config.clientCertificateKey).map { it.path }
        val key = Key(config, files.map(::modified))
        contexts[key]?.let { return it }
        val fileLock = fileLocks.computeIfAbsent(files) { ReentrantLock() }
        fileLock.lockInterruptibly()
        try {
            contexts[key]?.let { return it }
            val generation = synchronized(storeLock) { this.generation }
            var asked: CharArray? = null
            val ask = { passphrases[files]?.copyOf() ?: passphrase(config)?.also { asked = it.copyOf() } }
            var rejected = false
            val context =
                try {
                    create(config, ask, trustStore)
                } catch (e: SslConfigException) {
                    rejected = e.passphraseRejected
                    throw e
                } finally {
                    synchronized(storeLock) {
                        val current = this.generation == generation
                        if (rejected) passphrases.remove(files)?.fill('\u0000')
                        val keep = asked?.takeIf { !rejected && current }
                        if (keep != null) passphrases[files] = keep else asked?.fill('\u0000')
                    }
                }
            val replaced =
                synchronized(storeLock) {
                    if (this.generation != generation) return context
                    val old = contexts.keys.filter { it.config == config }.mapNotNull { contexts.remove(it) }
                    contexts[key] = context
                    old
                }
            replaced.forEach(discarded)
            return context
        } finally {
            fileLock.unlock()
        }
    }

    /** Forgets all contexts and passphrases; does not wait for running calls of [context]. */
    fun clear() {
        val cleared =
            synchronized(storeLock) {
                generation++
                passphrases.values.forEach { it.fill('\u0000') }
                passphrases.clear()
                contexts.values.toList().also { contexts.clear() }
            }
        cleared.forEach(discarded)
    }

    private fun modified(path: Path): FileTime? =
        try {
            path.getLastModifiedTime()
        } catch (_: IOException) {
            null
        }

    companion object {
        /** See [SslContextFactory.create]. */
        fun create(
            config: SslConfig,
            passphrase: () -> CharArray?,
            trustStore: KeyStore? = null,
        ): SSLContext = SslContextFactory.create(config, passphrase, trustStore)
    }
}
