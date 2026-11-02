package dev.sluide.databases.connect

import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * The passwords typed for connections, in memory until the app restarts (ADR 0121); never written anywhere. Keyed by
 * the project folder (normalized, absolute; none for the shared connections) and the connection id. Thread-safe;
 * hands out and stores copies, [forget] zeroes the stored array.
 */
@Component
class PasswordCache {
    private val passwords = HashMap<Pair<Path?, String>, CharArray>()

    /** A copy of the password of connection [id], or `null` if none is cached. The caller may zero the copy. */
    fun get(
        folder: Path?,
        id: String,
    ): CharArray? = synchronized(passwords) { passwords[key(folder, id)]?.copyOf() }

    /** Caches a copy of [password], replacing (and zeroing) an earlier one. */
    fun put(
        folder: Path?,
        id: String,
        password: CharArray,
    ) {
        synchronized(passwords) { passwords.put(key(folder, id), password.copyOf()) }?.let(::drop)
    }

    /** Drops and zeroes the cached password, e.g. after a failed login. */
    fun forget(
        folder: Path?,
        id: String,
    ) {
        synchronized(passwords) { passwords.remove(key(folder, id)) }?.let(::drop)
    }

    /** Test hook: called with each dropped array after it was zeroed. */
    internal var onZeroed: (CharArray) -> Unit = {}

    private fun drop(array: CharArray) {
        array.fill('\u0000')
        onZeroed(array)
    }

    private fun key(
        folder: Path?,
        id: String,
    ) = folder?.toAbsolutePath()?.normalize() to id
}
