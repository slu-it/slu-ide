package dev.sluide.settings

import java.nio.file.Path

/** Font settings for tests, with fixed defaults instead of the system fonts (which need the JavaFX toolkit). */
internal object TestFonts {
    val defaults =
        mapOf(
            FontArea.EXPLORER to FontSpec("System", 13.0),
            FontArea.EDITOR to FontSpec("Monospaced", 13.0),
            FontArea.TERMINAL to FontSpec("Monospaced", 14.0),
        )

    /** Font settings stored in [file]. */
    fun settings(file: Path) = FontSettings(SettingsStore(file), defaults)
}
