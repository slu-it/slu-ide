package dev.sluide.translation.opusmt

import dev.sluide.localmodels.IdleSession
import dev.sluide.translation.engine.PairModel
import kotlin.time.Duration

private val log = System.getLogger(PairSessions::class.java.name)

/**
 * The loaded pair models of the app (ADR 0126), shared by all windows: each loads on first use, unloads [idle] after
 * its last use, and at most [maxLoaded] are loaded at once (a pivot route needs two); before another one loads, the
 * least recently used ones are unloaded.
 *
 * Design: every [PairModel] that was used gets one [IdleSession] for the app's lifetime, which serializes its calls.
 * Its value is a cheap holder ([Slot]) that loads the model on demand and can drop it again, so evicting a model
 * unloads it inside that session's own `use` and never closes the session: a window that is about to translate with
 * the evicted model just waits for the eviction and loads it again, instead of hitting a closed session. The idle
 * unload closes the holder (and with it the model) the same way. Only [close] closes the sessions; later calls throw
 * [IllegalStateException]. A model that fails to close is logged and dropped, also during an eviction or [close].
 *
 * Eviction runs before the caller's own `use`, holding no session's lock, so two threads that evict each other's
 * models cannot deadlock. The price: two windows that load different models at the same moment may briefly hold one
 * more than [maxLoaded]; the next load evicts it again.
 *
 * Every idle session has a scheduled daemon thread of its own (one per model used in this run of the app).
 */
internal class PairSessions<T : AutoCloseable>(
    private val idle: Duration,
    private val maxLoaded: Int = 2,
    private val load: (PairModel) -> T,
) : AutoCloseable {
    private val lock = Any()

    /** Guarded by [lock]. */
    private val sessions = HashMap<PairModel, IdleSession<Slot>>()

    /** The loaded models, least recently used first; guarded by [lock]. */
    private val loaded = LinkedHashSet<PairModel>()

    private var closed = false

    /**
     * Runs [block] with [model] loaded; the calls on one model are serialized. Blocking; platform threads only
     * (native calls).
     *
     * @throws IllegalStateException after [close].
     * @throws InterruptedException when interrupted while waiting for the model.
     */
    fun <R> use(
        model: PairModel,
        block: (T) -> R,
    ): R {
        val session = session(model)
        makeRoom(model)
        return session.use { slot -> block(slot.get()) }
    }

    /** The loaded models, least recently used first. */
    fun loaded(): List<PairModel> = synchronized(lock) { loaded.toList() }

    /** Closes every model (after the calls that run) and refuses later use. */
    override fun close() {
        val all =
            synchronized(lock) {
                closed = true
                sessions.values.toList()
            }
        for (session in all) {
            try {
                session.close()
            } catch (e: Exception) {
                log.log(System.Logger.Level.WARNING, "Cannot close a translation model", e)
            }
        }
    }

    private fun session(model: PairModel): IdleSession<Slot> =
        synchronized(lock) {
            check(!closed) { "The translation models are closed" }
            sessions.getOrPut(model) {
                IdleSession("opus-mt-idle-${model.pair}-${model.quality.name.lowercase()}", idle) { Slot(model) }
            }
        }

    /** Unloads the least recently used models until [model] fits. */
    private fun makeRoom(model: PairModel) {
        val victims =
            synchronized(lock) {
                val others = loaded.filter { it != model }
                others.take((others.size + 1 - maxLoaded).coerceAtLeast(0)).mapNotNull { sessions[it] }
            }
        for (victim in victims) victim.use { it.unload() }
    }

    /** The holder of one model inside its [IdleSession]; used under that session's lock only. */
    private inner class Slot(
        private val model: PairModel,
    ) : AutoCloseable {
        private var value: T? = null

        fun get(): T {
            val current = value ?: load(model).also { value = it }
            synchronized(lock) {
                loaded.remove(model)
                loaded.add(model)
            }
            return current
        }

        /** Drops the model; a failure to close it is logged, never thrown into an eviction's unrelated translation. */
        fun unload() {
            val old = value ?: return
            value = null
            synchronized(lock) { loaded.remove(model) }
            try {
                old.close()
            } catch (e: Exception) {
                log.log(System.Logger.Level.WARNING, "Cannot close the translation model ${model.pair}", e)
            }
        }

        override fun close() = unload()
    }
}
