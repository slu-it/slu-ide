package dev.sluide.speech.settings

import dev.sluide.settings.SettingsStore
import dev.sluide.speech.install.KokoroVariant
import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.beans.property.ReadOnlyStringProperty
import javafx.beans.property.ReadOnlyStringWrapper
import org.springframework.stereotype.Component

private const val ESPEAK_KEY = "speech.espeak"
private const val VARIANT_KEY = "speech.kokoro.variant"

/**
 * The Read Aloud settings: the path of espeak-ng (empty: found on the login shell's PATH) and the Kokoro model variant
 * in use. [apply] stores them in the [SettingsStore]; the properties belong to the FX thread, the `current` functions
 * can be read from any thread.
 */
@Component
class SpeechSettings(
    private val store: SettingsStore,
) {
    private val stored = store.load()
    private val espeakWrapper = ReadOnlyStringWrapper(readEspeak(stored))
    private val variantWrapper = ReadOnlyObjectWrapper(readVariant(stored))

    @Volatile private var espeak = espeakWrapper.get()

    @Volatile private var variantInUse = variantWrapper.get()

    /** The path of espeak-ng; empty means automatic. */
    val espeakPath: ReadOnlyStringProperty = espeakWrapper.readOnlyProperty

    /** The model variant in use. */
    val variant: ReadOnlyObjectProperty<KokoroVariant> = variantWrapper.readOnlyProperty

    fun currentEspeakPath(): String = espeak

    fun currentVariant(): KokoroVariant = variantInUse

    /**
     * Stores both (the path trimmed) and then sets them; FX thread.
     *
     * @throws java.io.IOException if they cannot be stored; they are then not changed either.
     */
    @Throws(java.io.IOException::class)
    fun apply(
        espeakPath: String,
        variant: KokoroVariant,
    ) {
        val path = espeakPath.trim()
        if (path == espeak && variant == variantInUse) return
        store.update(mapOf(ESPEAK_KEY to path, VARIANT_KEY to variant.name))
        espeak = path
        variantInUse = variant
        espeakWrapper.set(path)
        variantWrapper.set(variant)
    }

    private fun readEspeak(values: Map<String, String>) = values[ESPEAK_KEY]?.trim() ?: ""

    private fun readVariant(values: Map<String, String>) =
        KokoroVariant.entries.firstOrNull { it.name == values[VARIANT_KEY] } ?: KokoroVariant.FP32
}
