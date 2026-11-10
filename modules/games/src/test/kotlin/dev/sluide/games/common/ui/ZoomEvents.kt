package dev.sluide.games.common.ui

import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.ScrollEvent
import javafx.scene.input.ZoomEvent

/** A key press of [code], with Shortcut (Ctrl, or Cmd on macOS) held if [shortcut]. */
internal fun keyPress(
    code: KeyCode,
    shortcut: Boolean = true,
) = KeyEvent(
    KeyEvent.KEY_PRESSED,
    "",
    "",
    code,
    false,
    shortcut && !SHORTCUT_IS_META,
    false,
    shortcut && SHORTCUT_IS_META,
)

/** A wheel event at (10, 10) of [deltaY], with Shortcut held if [shortcut]. */
internal fun scrollEvent(
    deltaY: Double,
    shortcut: Boolean,
) = ScrollEvent(
    ScrollEvent.SCROLL,
    10.0,
    10.0,
    10.0,
    10.0,
    false,
    shortcut && !SHORTCUT_IS_META,
    false,
    shortcut && SHORTCUT_IS_META,
    false,
    false,
    0.0,
    deltaY,
    0.0,
    deltaY,
    ScrollEvent.HorizontalTextScrollUnits.NONE,
    0.0,
    ScrollEvent.VerticalTextScrollUnits.NONE,
    0.0,
    0,
    null,
)

/** A pinch at (10, 10) by [factor]. */
internal fun pinchEvent(factor: Double) =
    ZoomEvent(
        ZoomEvent.ZOOM,
        10.0,
        10.0,
        10.0,
        10.0,
        false,
        false,
        false,
        false,
        true,
        false,
        factor,
        factor,
        null,
    )

private val SHORTCUT_IS_META = System.getProperty("os.name").startsWith("Mac")
