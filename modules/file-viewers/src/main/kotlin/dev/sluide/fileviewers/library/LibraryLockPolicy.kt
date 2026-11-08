package dev.sluide.fileviewers.library

/** What a code editor shows of its file being library code (ADR 0150). */
internal enum class LibraryLockState {
    /** Not library code: no lock, no banner. */
    NOT_LIBRARY,

    /** Library code, the setting on: locked, with the banner offering Edit Anyway. */
    LOCKED,

    /** Library code the user unlocked with Edit Anyway, or that has unsaved changes: editable, with the warning. */
    UNLOCKED_BY_USER,

    /** Library code while the setting is off: editable, no banner. */
    SETTING_OFF,
}

/**
 * The rules of one editor's library lock, without the editor: what [evaluate] returns for its file's location, the
 * setting and the unsaved changes. Edit Anyway is remembered until the editor is closed, whatever happens to the
 * setting (which, while off, shows no banner and takes no lock; turning it on again skips such a tab); so are unsaved
 * changes found when the lock would be taken, so that Save keeps working for them. A file that stops being library
 * code forgets both.
 */
internal class LibraryLockPolicy {
    private var unlocked = false

    /** The state for a file that [isLibrary] code, with the setting [settingOn] and the editor [dirty]. */
    fun evaluate(
        isLibrary: Boolean,
        settingOn: Boolean,
        dirty: Boolean,
    ): LibraryLockState {
        if (!isLibrary) {
            unlocked = false
            return LibraryLockState.NOT_LIBRARY
        }
        if (!unlocked && settingOn && dirty) unlocked = true
        return when {
            !settingOn -> LibraryLockState.SETTING_OFF
            unlocked -> LibraryLockState.UNLOCKED_BY_USER
            else -> LibraryLockState.LOCKED
        }
    }

    /** The user chose Edit Anyway: unlocked until the editor is closed, or the file stops being library code. */
    fun editAnyway() {
        unlocked = true
    }
}
