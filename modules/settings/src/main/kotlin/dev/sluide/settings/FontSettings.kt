package dev.sluide.settings

import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.scene.text.Font
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * The fonts of the [FontArea]s. Views follow [font]; the settings dialog changes them with [apply], which also stores
 * them in the [SettingsStore]. Stored values that cannot be used fall back to [defaults].
 */
@Component
class FontSettings(
    private val store: SettingsStore,
    val defaults: Map<FontArea, FontSpec>,
) {
    /** Uses the system font for the explorer and a monospaced font for editor and terminal; needs the JavaFX toolkit. */
    @Autowired
    constructor(store: SettingsStore) : this(store, systemDefaults())

    init {
        require(defaults.keys == FontArea.entries.toSet()) { "Defaults needed for all areas: $defaults" }
    }

    private val fonts: Map<FontArea, ReadOnlyObjectWrapper<FontSpec>> =
        store.load().let { stored ->
            FontArea.entries.associateWith { area ->
                ReadOnlyObjectWrapper(read(stored, area) ?: defaults.getValue(area))
            }
        }

    /** The current font of [area]. */
    fun font(area: FontArea): ReadOnlyObjectProperty<FontSpec> = fonts.getValue(area).readOnlyProperty

    /** Sets the fonts of the given areas and stores them. Must be called on the FX thread. */
    fun apply(changes: Map<FontArea, FontSpec>) {
        if (changes.isEmpty()) return
        store.update(changes.flatMap { (area, spec) -> write(area, spec).toList() }.toMap())
        changes.forEach { (area, spec) -> fonts.getValue(area).set(spec) }
    }

    companion object {
        fun systemDefaults(): Map<FontArea, FontSpec> {
            val size = Font.getDefault().size
            return mapOf(
                FontArea.EXPLORER to FontSpec(Font.getDefault().family, size),
                FontArea.EDITOR to FontSpec("Monospaced", size),
                FontArea.TERMINAL to FontSpec("Monospaced", 13.0),
            )
        }

        private fun keys(area: FontArea) = listOf("family", "size", "style").map { "font.${area.key}.$it" }

        internal fun write(
            area: FontArea,
            spec: FontSpec,
        ): Map<String, String> = keys(area).zip(listOf(spec.family, spec.size.toString(), spec.style.name)).toMap()

        internal fun read(
            stored: Map<String, String>,
            area: FontArea,
        ): FontSpec? {
            val (familyKey, sizeKey, styleKey) = keys(area)
            val family = stored[familyKey]?.takeIf { it.isNotBlank() } ?: return null
            val size = stored[sizeKey]?.toDoubleOrNull()?.takeIf { it in 1.0..500.0 } ?: return null
            val style = stored[styleKey]?.let { name -> FontStyle.entries.find { it.name == name } } ?: return null
            return FontSpec(family, size, style)
        }
    }
}
