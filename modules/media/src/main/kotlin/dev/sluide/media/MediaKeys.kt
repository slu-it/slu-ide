package dev.sluide.media

import javafx.scene.input.KeyCode

/** What a key does in the media view. */
internal enum class MediaKeyAction { TOGGLE_PLAY, BACK, AHEAD, VOLUME_UP, VOLUME_DOWN, TOGGLE_MUTE }

/** The keys of the media view (ADR 0123): Space, Left, Right, Up, Down and M, only without modifiers. */
internal object MediaKeys {
    fun actionFor(
        code: KeyCode,
        hasModifier: Boolean,
    ): MediaKeyAction? {
        if (hasModifier) return null
        return when (code) {
            KeyCode.SPACE -> MediaKeyAction.TOGGLE_PLAY
            KeyCode.LEFT -> MediaKeyAction.BACK
            KeyCode.RIGHT -> MediaKeyAction.AHEAD
            KeyCode.UP -> MediaKeyAction.VOLUME_UP
            KeyCode.DOWN -> MediaKeyAction.VOLUME_DOWN
            KeyCode.M -> MediaKeyAction.TOGGLE_MUTE
            else -> null
        }
    }
}
