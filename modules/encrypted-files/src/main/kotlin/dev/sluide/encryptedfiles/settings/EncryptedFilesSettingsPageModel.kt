package dev.sluide.encryptedfiles.settings

import javafx.beans.property.IntegerProperty
import javafx.beans.property.SimpleIntegerProperty

/** The Encrypted Files page: a draft of the idle lock time, applied to [settings] by [apply]. FX thread. */
internal class EncryptedFilesSettingsPageModel(
    private val settings: EncryptedFilesSettings,
) {
    /** The draft of [EncryptedFilesSettings.idleLockMinutes]. */
    val idleLockMinutes: IntegerProperty = SimpleIntegerProperty()

    init {
        load()
    }

    /** Shows the stored value, dropping the draft. */
    fun load() = idleLockMinutes.set(settings.idleLockMinutes.get())

    /** Makes the draft the setting; it applies at once to the open tabs. */
    fun apply() = settings.idleLockMinutes.set(idleLockMinutes.get())

    companion object {
        const val EXPLANATION =
            "An unlocked encrypted file saves its unsaved changes and locks after this many minutes without a key " +
                "press, mouse press or scroll in its tab. 0 turns it off. If the save fails, or the file changed " +
                "elsewhere with another password and the tab has unsaved changes, the tab stays unlocked and tries " +
                "again a period later."
    }
}
