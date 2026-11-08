package dev.sluide.fileviewers.settings

import dev.sluide.librarycode.LibraryCodeSettings
import javafx.beans.property.BooleanProperty
import javafx.beans.property.SimpleBooleanProperty

/** The Editor page: a draft of "Open library code read-only", applied to [settings] by [apply]. FX thread. */
internal class EditorSettingsPageModel(
    private val settings: LibraryCodeSettings,
) {
    /** The draft of [LibraryCodeSettings.readOnly]. */
    val libraryReadOnly: BooleanProperty = SimpleBooleanProperty()

    init {
        load()
    }

    /** Shows the stored value, dropping the draft. */
    fun load() = libraryReadOnly.set(settings.readOnly.get())

    /** Makes the draft the setting; it applies at once to the open tabs. */
    fun apply() = settings.readOnly.set(libraryReadOnly.get())

    companion object {
        /** The explanation below the check box. */
        const val EXPLANATION =
            "Library code is code you install rather than write: files in virtual environments (folders with " +
                "pyvenv.cfg), in site-packages and dist-packages, in __pypackages__, the Python standard library " +
                "and ty's typeshed stubs. Changes there are lost on the next install. Edit Anyway in a tab " +
                "unlocks it."
    }
}
