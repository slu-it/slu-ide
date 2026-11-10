package dev.sluide.encryptedfiles.settings

import dev.sluide.settings.SettingsStore
import javafx.beans.InvalidationListener
import javafx.beans.property.IntegerProperty
import javafx.beans.property.SimpleIntegerProperty
import org.springframework.stereotype.Component
import java.io.IOException

private val log = System.getLogger(EncryptedFilesSettings::class.java.name)

/** The settings key of [EncryptedFilesSettings.idleLockMinutes]. */
internal const val IDLE_LOCK_KEY = "encryptedFiles.idleLockMinutes"

/** The settings of encrypted files (ADR 0162), shared by all windows (a singleton bean), stored with [SettingsStore]. */
@Component
class EncryptedFilesSettings(
    private val store: SettingsStore,
) {
    /**
     * Minutes without a key press, mouse press or scroll in an unlocked tab until it saves and locks, stored as
     * `encryptedFiles.idleLockMinutes`: 0 = never, [MAX_MINUTES] at most, default [DEFAULT_MINUTES]; a stored value
     * that is no whole number in that range counts as the default. Read on first use. FX thread; setting it stores it,
     * and a failure to store it is logged.
     */
    val idleLockMinutes: IntegerProperty by lazy {
        val stored = parse(store.load()[IDLE_LOCK_KEY])
        current = stored
        SimpleIntegerProperty(this, "idleLockMinutes", stored).apply {
            // Before any other listener: whoever reacts to a change and asks currentIdleLockMinutes() gets the new value.
            addListener(InvalidationListener { current = get() })
            addListener { _, _, value -> save(value.toInt()) }
        }
    }

    @Volatile
    private var current = DEFAULT_MINUTES

    /** The current value, readable from any thread. */
    fun currentIdleLockMinutes(): Int = idleLockMinutes.let { current }

    private fun save(value: Int) {
        try {
            store.update(mapOf(IDLE_LOCK_KEY to value.toString()))
        } catch (e: IOException) {
            log.log(System.Logger.Level.WARNING, "Cannot store the idle lock time of encrypted files", e)
        }
    }

    companion object {
        const val DEFAULT_MINUTES = 10
        const val MAX_MINUTES = 240

        internal fun parse(text: String?): Int =
            text?.trim()?.toIntOrNull()?.takeIf { it in 0..MAX_MINUTES } ?: DEFAULT_MINUTES
    }
}
