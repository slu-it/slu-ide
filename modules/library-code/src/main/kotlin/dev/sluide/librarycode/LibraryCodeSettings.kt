package dev.sluide.librarycode

import dev.sluide.settings.SettingsStore
import javafx.beans.InvalidationListener
import javafx.beans.property.BooleanProperty
import javafx.beans.property.SimpleBooleanProperty
import org.springframework.stereotype.Component
import java.io.IOException

private val log = System.getLogger(LibraryCodeSettings::class.java.name)

/** The settings key of [LibraryCodeSettings.readOnly]. */
internal const val READ_ONLY_KEY = "editor.libraryCodeReadOnly"

/** The settings of library code (ADR 0150), shared by all windows (a singleton bean), stored with [SettingsStore]. */
@Component
class LibraryCodeSettings(
    private val store: SettingsStore,
) {
    /**
     * "Open library code read-only", stored as `editor.libraryCodeReadOnly` (default true; a value that is no boolean
     * counts as the default). Read from the settings on first use of the property or of [currentReadOnly], so creating
     * the bean does no I/O. FX thread; setting it stores it, and a failure to store it is logged.
     */
    val readOnly: BooleanProperty by lazy {
        val stored = store.load()[READ_ONLY_KEY]?.trim()?.toBooleanStrictOrNull() ?: true
        current = stored
        SimpleBooleanProperty(this, "readOnly", stored).apply {
            // Before any other listener: whoever reacts to a change and asks currentReadOnly() gets the new value.
            addListener(InvalidationListener { current = get() })
            addListener { _, _, value -> save(value) }
        }
    }

    @Volatile
    private var current = true

    /** The current value, readable from any thread. */
    fun currentReadOnly(): Boolean = readOnly.let { current } // reads the settings on first use

    private fun save(value: Boolean) {
        try {
            store.update(mapOf(READ_ONLY_KEY to value.toString()))
        } catch (e: IOException) {
            log.log(System.Logger.Level.WARNING, "Cannot store whether library code opens read-only", e)
        }
    }
}
