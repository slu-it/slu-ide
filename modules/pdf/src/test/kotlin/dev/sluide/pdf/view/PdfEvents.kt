package dev.sluide.pdf.view

import javafx.scene.input.ContextMenuEvent
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.input.ScrollEvent
import javafx.scene.input.ZoomEvent

private val SHORTCUT_IS_META = System.getProperty("os.name").startsWith("Mac")

/** A key press of [code], with Shortcut (Ctrl, or Cmd on macOS), Shift and Alt held as asked. */
internal fun keyPress(
    code: KeyCode,
    shortcut: Boolean = false,
    shift: Boolean = false,
    text: String = "",
    alt: Boolean = false,
) = KeyEvent(
    KeyEvent.KEY_PRESSED,
    "",
    text,
    code,
    shift,
    shortcut && !SHORTCUT_IS_META,
    alt,
    shortcut && SHORTCUT_IS_META,
)

/** A wheel event at the scene point [sceneX], [sceneY] of [deltaY], with Shortcut held if [shortcut]. */
internal fun wheel(
    sceneX: Double,
    sceneY: Double,
    deltaY: Double,
    shortcut: Boolean = false,
) = ScrollEvent(
    ScrollEvent.SCROLL,
    sceneX,
    sceneY,
    sceneX,
    sceneY,
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

/** A pinch at the scene point [sceneX], [sceneY] by [factor]. */
internal fun pinch(
    sceneX: Double,
    sceneY: Double,
    factor: Double,
) = ZoomEvent(
    ZoomEvent.ZOOM,
    sceneX,
    sceneY,
    sceneX,
    sceneY,
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

/** A press of the primary mouse button, as a click starts. */
internal fun mousePress() =
    MouseEvent(
        MouseEvent.MOUSE_PRESSED,
        5.0,
        5.0,
        5.0,
        5.0,
        MouseButton.PRIMARY,
        1,
        false,
        false,
        false,
        false,
        true,
        false,
        false,
        false,
        false,
        false,
        null,
    )

/** A secondary click's context menu request at the scene point [sceneX], [sceneY]. */
internal fun contextMenuRequest(
    sceneX: Double,
    sceneY: Double,
) = ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, sceneX, sceneY, sceneX, sceneY, false, null)
