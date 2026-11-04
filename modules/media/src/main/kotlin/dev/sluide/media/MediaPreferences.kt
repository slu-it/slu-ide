package dev.sluide.media

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.projectdata.ProjectDataStore
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import tools.jackson.databind.node.ObjectNode
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

/**
 * Volume and mute of the media views, user-wide (ADR 0123): `{"volume": 0.8, "muted": false}` in the top-level field
 * `media` of `shared.json` (ADR 0101). Read once on first use, then kept in memory: [volume] and [muted] are the last
 * saved values. [save] changes them at once and writes off the FX thread; saves within [writeDelay] of each other end
 * in one write of the latest values. When the app quits ([destroy]) a pending write is done at once, and so is every
 * later save.
 *
 * A missing, invalid or out-of-range value reads as its default (volume 1.0, not muted). Other fields of `media` stay as
 * they are; a `media` that is no JSON object is not ours to keep and is replaced.
 */
@Component
class MediaPreferences internal constructor(
    private val store: ProjectDataStore,
    private val writeDelay: Duration,
    private val writer: Executor,
) : DisposableBean {
    @Autowired
    constructor(
        store: ProjectDataStore,
    ) : this(store, Duration.ofMillis(300), NamedThreads.single("sluide-media-prefs"))

    private data class Values(
        val volume: Double,
        val muted: Boolean,
    )

    @Volatile
    private var current: Values? = null
    private val pending = AtomicReference<Values?>()

    /** Held while taking and writing the pending values, so that [destroy] waits for a write in progress. */
    private val writeLock = Any()

    @Volatile
    private var closed = false

    @Volatile
    private var warned = false

    /** 0.0 to 1.0; 1.0 unless saved otherwise. */
    val volume: Double get() = values().volume

    /** `false` unless saved otherwise. */
    val muted: Boolean get() = values().muted

    /** Remembers [volume] (kept within 0 and 1; NaN is the default) and [muted]; writes shortly after. */
    fun save(
        volume: Double,
        muted: Boolean,
    ) {
        val values = Values(if (volume.isNaN()) DEFAULT.volume else volume.coerceIn(0.0, 1.0), muted)
        current = values
        if (closed) {
            pending.set(values)
            flush()
        } else if (pending.getAndSet(values) == null) {
            writer.execute {
                // Saves arriving meanwhile replace the pending values; this task writes the latest of them.
                try {
                    Thread.sleep(writeDelay)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                flush()
            }
        }
    }

    /** The app quits: the writer is a daemon thread, so pending values are written now; later saves write at once. */
    override fun destroy() {
        closed = true
        flush()
    }

    private fun flush() {
        synchronized(writeLock) { pending.getAndSet(null)?.let(::write) }
    }

    private fun values(): Values = current ?: read().also { current = it }

    private fun read(): Values {
        val media = store.readShared()?.get(FIELD) as? ObjectNode ?: return DEFAULT
        val volume =
            media
                .get(VOLUME)
                ?.takeIf { it.isNumber }
                ?.asDouble()
                ?.takeIf { it in 0.0..1.0 } ?: DEFAULT.volume
        val muted = media.get(MUTED)?.takeIf { it.isBoolean }?.asBoolean() ?: DEFAULT.muted
        return Values(volume, muted)
    }

    private fun write(values: Values) {
        val written =
            store.updateShared { root ->
                val media = root.get(FIELD) as? ObjectNode ?: root.objectNode()
                media.put(VOLUME, values.volume)
                media.put(MUTED, values.muted)
                root.set(FIELD, media)
            }
        if (!written && !warned) {
            warned = true
            System
                .getLogger(MediaPreferences::class.java.name)
                .log(System.Logger.Level.WARNING, "Volume and mute not saved: the shared data file cannot be written")
        }
    }

    private companion object {
        const val FIELD = "media"
        const val VOLUME = "volume"
        const val MUTED = "muted"
        val DEFAULT = Values(1.0, false)
    }
}
