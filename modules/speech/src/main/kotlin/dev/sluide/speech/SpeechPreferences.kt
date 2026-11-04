package dev.sluide.speech

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
 * The last voice and speed of Read Aloud, user-wide (ADR 0124): `{"voice": "af_heart", "speed": 1.25}` in the
 * top-level field `speech` of `shared.json` (ADR 0101). Read once on first use, then kept in memory: [voiceId] and
 * [speed] are the last saved values. [save] changes them at once and writes off the FX thread; saves within
 * [writeDelay] of each other end in one write of the latest values. When the app quits ([destroy]) a pending write is
 * done at once, and so is every later save.
 *
 * A missing or invalid value reads as its default (no voice, 1.0); a speed outside the media player's speeds (0.5
 * to 2.0) is invalid. Other fields of `speech` stay as they are; a `speech` that is
 * no JSON object is not ours to keep and is replaced.
 */
@Component
class SpeechPreferences internal constructor(
    private val store: ProjectDataStore,
    private val writeDelay: Duration,
    private val writer: Executor,
) : DisposableBean {
    @Autowired
    constructor(
        store: ProjectDataStore,
    ) : this(store, Duration.ofMillis(300), NamedThreads.single("sluide-speech-prefs"))

    private data class Values(
        val voiceId: String?,
        val speed: Double,
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

    /** The id of the last voice used, or null when none was saved; the caller checks that it still exists. */
    val voiceId: String? get() = values().voiceId

    /** 0.5 to 2.0; 1.0 unless saved otherwise. */
    val speed: Double get() = values().speed

    /** Remembers [voiceId] and [speed] (kept within the speeds; NaN is the default); writes shortly after. */
    fun save(
        voiceId: String,
        speed: Double,
    ) {
        val values = Values(voiceId, if (speed.isNaN()) DEFAULT.speed else speed.coerceIn(MIN_SPEED, MAX_SPEED))
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
        val speech = store.readShared()?.get(FIELD) as? ObjectNode ?: return DEFAULT
        val voiceId =
            speech
                .get(VOICE)
                ?.takeIf { it.isString }
                ?.asString()
                ?.takeIf { it.isNotBlank() }
        val speed =
            speech
                .get(SPEED)
                ?.takeIf { it.isNumber }
                ?.asDouble()
                ?.takeIf { it in MIN_SPEED..MAX_SPEED } ?: DEFAULT.speed
        return Values(voiceId, speed)
    }

    private fun write(values: Values) {
        val written =
            store.updateShared { root ->
                val speech = root.get(FIELD) as? ObjectNode ?: root.objectNode()
                speech.put(VOICE, values.voiceId)
                speech.put(SPEED, values.speed)
                root.set(FIELD, speech)
            }
        if (!written && !warned) {
            warned = true
            System
                .getLogger(SpeechPreferences::class.java.name)
                .log(System.Logger.Level.WARNING, "Voice and speed not saved: the shared data file cannot be written")
        }
    }

    private companion object {
        const val FIELD = "speech"
        const val VOICE = "voice"
        const val SPEED = "speed"

        // The bounds of the media player's speeds (MediaControlActions.SPEEDS, internal to media).
        const val MIN_SPEED = 0.5
        const val MAX_SPEED = 2.0
        val DEFAULT = Values(null, 1.0)
    }
}
