package dev.sluide.translation

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.projectdata.ProjectDataStore
import dev.sluide.translation.engine.Quality
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import tools.jackson.databind.node.ObjectNode
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

/**
 * The last languages and quality of the Translate tab, user-wide (ADR 0126):
 * `{"source": "detect", "target": "de", "quality": "medium"}` in the top-level field `translation` of `shared.json`
 * (ADR 0101); `target` is absent until one was chosen. The text is never stored. Read once on first use, then kept in
 * memory: [source], [target] and [quality] are the last saved values. [save] changes them at once and writes off the
 * FX thread; saves within [writeDelay] of each other end in one write of the latest values. When the app quits
 * ([destroy]) a pending write is done at once, and so is every later save.
 *
 * A missing or invalid value reads as its default (detect, no target, medium). The codes are not checked against the
 * engine's languages here: the Translate model falls back for a code it does not offer. Other fields of `translation`
 * stay as they are; a `translation` that is no JSON object is not ours to keep and is replaced.
 */
@Component
class TranslationPreferences internal constructor(
    private val store: ProjectDataStore,
    private val writeDelay: Duration,
    private val writer: Executor,
) : DisposableBean {
    @Autowired
    constructor(
        store: ProjectDataStore,
    ) : this(store, Duration.ofMillis(300), NamedThreads.single("sluide-translation-prefs"))

    private data class Values(
        val source: String?,
        val target: String?,
        val quality: Quality,
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

    /** The code of the last source language picked, or null for Detect (also when none was saved). */
    val source: String? get() = values().source

    /** The code of the last target language, or null when none was chosen yet. */
    val target: String? get() = values().target

    /** Medium unless saved otherwise. */
    val quality: Quality get() = values().quality

    /** Remembers [source] (null for Detect), [target] (null for none) and [quality]; writes shortly after. */
    fun save(
        source: String?,
        target: String?,
        quality: Quality,
    ) {
        val values = Values(source?.takeIf { it.isNotBlank() }, target?.takeIf { it.isNotBlank() }, quality)
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
        val translation = store.readShared()?.get(FIELD) as? ObjectNode ?: return DEFAULT
        val source = code(translation, SOURCE)?.takeIf { it != DETECT }
        val target = code(translation, TARGET)?.takeIf { it != DETECT }
        val quality =
            translation
                .get(QUALITY)
                ?.takeIf { it.isString }
                ?.asString()
                ?.let { name -> Quality.entries.firstOrNull { it.name.lowercase() == name } } ?: DEFAULT.quality
        return Values(source, target, quality)
    }

    private fun code(
        translation: ObjectNode,
        field: String,
    ): String? =
        translation
            .get(field)
            ?.takeIf { it.isString }
            ?.asString()
            ?.takeIf { it.isNotBlank() }

    private fun write(values: Values) {
        val written =
            store.updateShared { root ->
                val translation = root.get(FIELD) as? ObjectNode ?: root.objectNode()
                translation.put(SOURCE, values.source ?: DETECT)
                if (values.target == null) translation.remove(TARGET) else translation.put(TARGET, values.target)
                translation.put(QUALITY, values.quality.name.lowercase())
                root.set(FIELD, translation)
            }
        if (!written && !warned) {
            warned = true
            System
                .getLogger(TranslationPreferences::class.java.name)
                .log(
                    System.Logger.Level.WARNING,
                    "Languages and quality not saved: the shared data file cannot be written",
                )
        }
    }

    private companion object {
        const val FIELD = "translation"
        const val SOURCE = "source"
        const val TARGET = "target"
        const val QUALITY = "quality"
        const val DETECT = "detect"

        val DEFAULT = Values(null, null, Quality.MEDIUM)
    }
}
