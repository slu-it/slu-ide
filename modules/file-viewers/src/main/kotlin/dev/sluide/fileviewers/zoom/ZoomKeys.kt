package dev.sluide.fileviewers.zoom

import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination

/** What a zoom key (with Shortcut) does. Shared by the previews and the PDF view. */
enum class ZoomKeyAction { ZOOM_IN, ZOOM_OUT, ACTUAL_SIZE, FIT }

/** The action of Shortcut+[code]; `null` for other keys, and for Fit (Shortcut+9) if the target does not [fits]. */
fun zoomKeyAction(
    code: KeyCode,
    fits: Boolean,
): ZoomKeyAction? =
    when (code) {
        KeyCode.PLUS, KeyCode.EQUALS, KeyCode.ADD -> ZoomKeyAction.ZOOM_IN
        KeyCode.MINUS, KeyCode.SUBTRACT -> ZoomKeyAction.ZOOM_OUT
        KeyCode.DIGIT0, KeyCode.NUMPAD0 -> ZoomKeyAction.ACTUAL_SIZE
        KeyCode.DIGIT9, KeyCode.NUMPAD9 -> if (fits) ZoomKeyAction.FIT else null
        else -> null
    }

/** Shortcut+Plus, shown in the tooltip of zoom in. */
val ZOOM_IN_KEY: KeyCodeCombination = KeyCodeCombination(KeyCode.PLUS, KeyCombination.SHORTCUT_DOWN)

/** Shortcut+Minus, shown in the tooltip of zoom out. */
val ZOOM_OUT_KEY: KeyCodeCombination = KeyCodeCombination(KeyCode.MINUS, KeyCombination.SHORTCUT_DOWN)

/** Shortcut+0, shown in the tooltip of the 100% button. */
val ACTUAL_SIZE_KEY: KeyCodeCombination = KeyCodeCombination(KeyCode.DIGIT0, KeyCombination.SHORTCUT_DOWN)

/** Shortcut+9, shown in the tooltip of Fit. */
val FIT_KEY: KeyCodeCombination = KeyCodeCombination(KeyCode.DIGIT9, KeyCombination.SHORTCUT_DOWN)
