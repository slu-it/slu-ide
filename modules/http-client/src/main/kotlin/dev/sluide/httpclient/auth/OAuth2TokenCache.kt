package dev.sluide.httpclient.auth

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.httpclient.env.Environment
import dev.sluide.httpclient.send.SslContexts
import dev.sluide.httpclient.vars.DynamicVariables
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import javax.net.ssl.SSLContext

private val log = System.getLogger(OAuth2TokenCache::class.java.name)

/**
 * The OAuth2 tokens of one window, in memory only. A token belongs to an environment name and auth id, and is only
 * used while the resolved [OAuth2Config] stays the same. A token counts as valid until [EARLY_EXPIRY] before it
 * expires (forever without `expires_in`), then it is refreshed with its refresh token, or acquired anew if refreshing
 * fails or there is none.
 *
 * [token] and [acquire] block during token requests; call them off the FX thread. Concurrent calls for the same
 * environment and id wait for one shared token request. [clear], [clearAll] and [tokens] never block. Token requests
 * use the environment's `SSLConfiguration` through [sslContexts].
 *
 * Every token request is a task of [tasks] (ADR 0061): "Acquiring token '<id>'" (for [acquire] with reading the auth
 * configuration) or "Refreshing token '<id>'". It fails with the [OAuth2Exception]'s message, which names no secret.
 */
internal class OAuth2TokenCache(
    private val client: OAuth2Client,
    private val sslContexts: SslContexts,
    private val tasks: BackgroundTasks,
    private val clock: Clock = Clock.systemUTC(),
    private val dynamic: DynamicVariables = DynamicVariables(),
) {
    /** What the Auth menu shows of a token; [expiresAt] is null if the token response did not say. */
    data class TokenInfo(
        val id: String,
        val environment: String,
        val expiresAt: Instant?,
        val hasRefreshToken: Boolean,
    )

    private data class Key(
        val environment: String,
        val id: String,
    )

    private class Token(
        val config: OAuth2Config,
        val response: OAuth2Client.TokenResponse,
        val expiresAt: Instant?,
    )

    private class Slot {
        val lock = ReentrantLock()

        @Volatile
        var token: Token? = null
    }

    private val slots = ConcurrentHashMap<Key, Slot>()

    /**
     * The access token of auth [id] in [environment], or with [idToken] (or `Use ID Token`) its ID token. A valid
     * cached token is returned as it is; otherwise it is refreshed or, if `Acquire Automatically` is on, acquired.
     * [note] hears about token requests made (and their time). Throws [OAuth2Exception] with a readable message.
     */
    fun token(
        environment: Environment?,
        id: String,
        idToken: Boolean = false,
        note: (String) -> Unit = {},
    ): String {
        val config =
            try {
                OAuth2Config.of(environment, id, dynamic)
            } catch (e: OAuth2Exception) {
                throw logged(e)
            }
        val key = Key(checkNotNull(environment).name, id)
        val slot = slots.computeIfAbsent(key) { Slot() }
        val ssl = { environment.ssl?.let(sslContexts::context) }
        val token =
            slot.lock.withLockInterruptibly {
                val cached = slot.token?.takeIf { it.config == config }
                when {
                    cached != null && valid(cached) -> cached
                    cached?.response?.refreshToken != null -> refresh(slot, cached, ssl, note)
                    config.acquireAutomatically -> obtain(slot, config, ssl, note)
                    cached != null -> throw logged(OAuth2Exception("The token for '$id' has expired; acquire it again"))
                    else -> throw logged(OAuth2Exception(ACQUIRE_FIRST.format(id)))
                }
            }
        return try {
            pick(token, idToken)
        } catch (e: OAuth2Exception) {
            throw logged(e)
        }
    }

    /**
     * Logs [error], a failure of [token] without a token request, at WARNING (ADR 0060); the failures of token requests
     * are logged by their tasks. So every [OAuth2Exception] that leaves this cache is logged once.
     */
    private fun logged(error: OAuth2Exception): OAuth2Exception {
        log.log(System.Logger.Level.WARNING, "No token: ${error.message}")
        return error
    }

    /** Acquires a new token for auth [id] in [environment], replacing a cached one; blocks like [token]. */
    fun acquire(
        environment: Environment?,
        id: String,
        note: (String) -> Unit = {},
    ): TokenInfo =
        // One task with what goes wrong before the request, e.g. an auth configuration without a Token URL.
        tokenRequest(acquiring(id)) {
            val config = OAuth2Config.of(environment, id, dynamic)
            val key = Key(checkNotNull(environment).name, id)
            val slot = slots.computeIfAbsent(key) { Slot() }
            val ssl = { environment.ssl?.let(sslContexts::context) }
            val token = slot.lock.withLockInterruptibly { obtain(slot, config, ssl, note, task = false) }
            info(key, token)
        }

    /** Forgets the tokens of auth [id] in all environments. */
    fun clear(id: String) {
        slots.keys.removeIf { it.id == id }
    }

    fun clearAll() {
        slots.clear()
    }

    /** The cached tokens, sorted by environment and id. */
    fun tokens(): List<TokenInfo> =
        slots.entries
            .mapNotNull { (key, slot) -> slot.token?.let { info(key, it) } }
            .sortedWith(compareBy({ it.environment }, { it.id }))

    private fun valid(token: Token): Boolean =
        token.expiresAt == null || clock.instant() < token.expiresAt - EARLY_EXPIRY

    /** Refreshes [cached]; if that fails, acquires a new token if allowed, else rethrows. */
    private fun refresh(
        slot: Slot,
        cached: Token,
        ssl: () -> SSLContext?,
        note: (String) -> Unit,
    ): Token {
        val config = cached.config
        val refreshToken = checkNotNull(cached.response.refreshToken)
        val start = clock.instant()
        val started = System.nanoTime()
        val response =
            try {
                tokenRequest("Refreshing token '${config.id}'") { client.refresh(config, refreshToken, ssl()) }
            } catch (e: OAuth2Exception) {
                slot.token = null
                if (!config.acquireAutomatically) throw e
                note("Refreshing the token for '${config.id}' failed (${e.message}); acquiring a new one")
                return obtain(slot, config, ssl, note)
            }
        note("Refreshed the token for '${config.id}' in ${millis(started)} ms")
        response.notes.forEach(note)
        // A refresh response may leave out the refresh and ID tokens; the old ones stay valid then (RFC 6749 6).
        val merged =
            OAuth2Client.TokenResponse(
                accessToken = response.accessToken,
                tokenType = response.tokenType,
                expiresIn = response.expiresIn,
                refreshToken = response.refreshToken ?: refreshToken,
                idToken = response.idToken ?: cached.response.idToken,
            )
        return store(slot, config, merged, start)
    }

    /** Requests a new token, as a task of its own unless [task] is `false` (the caller's is one). */
    private fun obtain(
        slot: Slot,
        config: OAuth2Config,
        ssl: () -> SSLContext?,
        note: (String) -> Unit,
        task: Boolean = true,
    ): Token {
        val start = clock.instant()
        val started = System.nanoTime()
        val grant = { client.grant(config, ssl()) }
        val response = if (task) tokenRequest(acquiring(config.id), grant) else grant()
        note("Acquired the token for '${config.id}' (${config.grant.label}) in ${millis(started)} ms")
        response.notes.forEach(note)
        return store(slot, config, response, start)
    }

    /**
     * Runs the token request [request] as the task [name]: failed with the message of an [OAuth2Exception], cancelled
     * if interrupted (the request was cancelled), else failed with what it threw.
     */
    private fun <T> tokenRequest(
        name: String,
        request: () -> T,
    ): T {
        val task = tasks.start(name)
        val response =
            try {
                request()
            } catch (e: OAuth2Exception) {
                val message = e.message ?: "The token request failed"
                if (Thread.currentThread().isInterrupted) task.failed(InterruptedException()) else task.failed(message)
                throw e
            } catch (t: Throwable) {
                task.failed(t)
                throw t
            }
        task.close()
        return response
    }

    /** Expiry counts from [start], before the request was sent, to be on the safe side; it ends at [Instant.MAX]. */
    private fun store(
        slot: Slot,
        config: OAuth2Config,
        response: OAuth2Client.TokenResponse,
        start: Instant,
    ): Token {
        val expiresAt =
            response.expiresIn?.let {
                if (it > Duration.between(start, Instant.MAX)) Instant.MAX else start + it
            }
        return Token(config, response, expiresAt).also { slot.token = it }
    }

    private fun pick(
        token: Token,
        idToken: Boolean,
    ): String {
        if (!idToken && !token.config.useIdToken) return token.response.accessToken
        return token.response.idToken
            ?: throw OAuth2Exception("The token response for '${token.config.id}' has no id_token")
    }

    private fun info(
        key: Key,
        token: Token,
    ) = TokenInfo(key.id, key.environment, token.expiresAt, token.response.refreshToken != null)

    private fun acquiring(id: String) = "Acquiring token '$id'"

    private fun millis(started: Long) = Duration.ofNanos(System.nanoTime() - started).toMillis()

    private inline fun <T> ReentrantLock.withLockInterruptibly(action: () -> T): T {
        lockInterruptibly()
        try {
            return action()
        } finally {
            unlock()
        }
    }

    companion object {
        private const val ACQUIRE_FIRST = "Acquire the token for '%s' first (Acquire Automatically is off)"

        /** Tokens are renewed this long before they expire, so that they do not expire on the way. */
        val EARLY_EXPIRY: Duration = Duration.ofSeconds(10)
    }
}
