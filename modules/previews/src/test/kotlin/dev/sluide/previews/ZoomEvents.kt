package dev.sluide.previews

import javafx.scene.input.ScrollEvent
import javafx.scene.input.ZoomEvent

/** A wheel event at (100, 100) of [deltaY], with Shortcut (Ctrl, or Cmd on macOS) held if [shortcut]. */
internal fun scrollEvent(
    deltaY: Double,
    shortcut: Boolean,
) = ScrollEvent(
    ScrollEvent.SCROLL,
    100.0,
    100.0,
    100.0,
    100.0,
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

/** A pinch at (100, 100) by [factor]. */
internal fun pinchEvent(factor: Double) =
    ZoomEvent(
        ZoomEvent.ZOOM,
        100.0,
        100.0,
        100.0,
        100.0,
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
