package dev.sluide.games.wordguess

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
 * The language of the last Word Guess round, user-wide: in the top-level field `wordGuess` of `shared.json`,
 * `{"language": "en"|"de"}`. Read once on first use, then kept in memory; changes apply at once and are written off the
 * FX thread, saves within [writeDelay] of each other ending in one write. [destroy] writes a pending change at once,
 * and so does every later change. A missing or invalid value reads as English; other fields of `wordGuess` stay as
 * they are, a `wordGuess` that is no JSON object is replaced.
 */
@Component
class WordGuessPreferences internal constructor(
    private val store: ProjectDataStore,
    private val writeDelay: Duration,
    private val writer: Executor,
) : DisposableBean {
    @Autowired
    constructor(
        store: ProjectDataStore,
    ) : this(store, Duration.ofMillis(300), NamedThreads.single("sluide-wordguess-prefs"))

    @Volatile
    private var current: Language? = null
    private val pending = AtomicReference<Language?>()

    /** Held while taking and writing the pending value, so that [destroy] waits for a write in progress. */
    private val writeLock = Any()

    @Volatile
    private var closed = false

    @Volatile
    private var warned = false

    /** The language of the last round; English unless saved otherwise. */
    val lastLanguage: Language get() = current ?: read().also { current = it }

    fun saveLanguage(language: Language) {
        current = language
        if (closed) {
            pending.set(language)
            flush()
        } else if (pending.getAndSet(language) == null) {
            writer.execute {
                // Saves arriving meanwhile replace the pending value; this task writes the latest one.
                try {
                    Thread.sleep(writeDelay)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                flush()
            }
        }
    }

    /** The app quits: the writer is a daemon thread, so a pending value is written now; later changes write at once. */
    override fun destroy() {
        closed = true
        flush()
    }

    private fun flush() {
        synchronized(writeLock) { pending.getAndSet(null)?.let(::write) }
    }

    private fun read(): Language {
        val node = (store.readShared()?.get(FIELD) as? ObjectNode)?.get(LANGUAGE)
        val code = node?.takeIf { it.isString }?.asString()
        return Language.ofCode(code) ?: Language.EN
    }

    private fun write(language: Language) {
        val written =
            store.updateShared { root ->
                val node = root.get(FIELD) as? ObjectNode ?: root.objectNode()
                node.put(LANGUAGE, language.code)
                root.set(FIELD, node)
            }
        if (!written && !warned) {
            warned = true
            System
                .getLogger(WordGuessPreferences::class.java.name)
                .log(
                    System.Logger.Level.WARNING,
                    "Word Guess settings not saved: the shared data file cannot be written",
                )
        }
    }

    private companion object {
        const val FIELD = "wordGuess"
        const val LANGUAGE = "language"
    }
}
