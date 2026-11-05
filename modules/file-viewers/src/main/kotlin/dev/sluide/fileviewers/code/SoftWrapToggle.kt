package dev.sluide.fileviewers.code

import dev.sluide.fileviewers.FileViewHost
import dev.sluide.fileviewers.SoftWrap
import javafx.beans.property.BooleanProperty
import javafx.beans.property.SimpleBooleanProperty
import javafx.scene.control.CheckMenuItem
import javafx.scene.control.ToggleButton
import javafx.scene.control.Tooltip
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyEvent
import org.fxmisc.richtext.CodeArea
import org.fxmisc.wellbehaved.event.EventPattern.keyPressed
import org.fxmisc.wellbehaved.event.InputMap
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignW

/**
 * Soft wrap of a code view's [area] (ADR 0130): [wrapping] decides whether its long lines wrap at the visible edge.
 * Off and local to the view until [attach]ed to a host whose window has the [SoftWrap] service; from then on it follows
 * the user-wide switch both ways, until [dispose]. The [button], the [menuItem] and the [keys] (Shortcut+Alt+Z) toggle
 * it. FX thread.
 */
internal class SoftWrapToggle(
    private val area: CodeArea,
) {
    /** Whether the view wraps; bound both ways to the window's [SoftWrap.enabled] once attached. */
    val wrapping: BooleanProperty = SimpleBooleanProperty(false)

    /** The shared switch followed, once attached. */
    private var shared: BooleanProperty? = null
    private var disposed = false

    /** Icon-only and not focusable: a click leaves the focus where it was. */
    val button =
        ToggleButton(null, FontIcon(MaterialDesignW.WRAP)).apply {
            styleClass += "soft-wrap-toggle"
            // It carries its look into the toolbars of other views and modules.
            stylesheets +=
                checkNotNull(SoftWrapToggle::class.java.getResource("/dev/sluide/fileviewers/soft-wrap.css"))
                    .toExternalForm()
            tooltip = Tooltip("Soft Wrap (${KEY.displayText})")
            isFocusTraversable = false
            selectedProperty().bindBidirectional(wrapping)
        }

    /** The item of the editor's context menu; it shows the key, which [keys] binds. */
    val menuItem =
        CheckMenuItem("Soft Wrap").apply {
            accelerator = KEY
            selectedProperty().bindBidirectional(wrapping)
        }

    /** Shortcut+Alt+Z toggles; add with `Nodes.addInputMap`. */
    val keys: InputMap<KeyEvent> = InputMap.consume(keyPressed(KEY)) { wrapping.set(!wrapping.get()) }

    init {
        area.wrapTextProperty().bind(wrapping)
    }

    /** Follows the [SoftWrap] of [host]'s window, unless it follows one already, has none or was disposed. */
    fun attach(host: FileViewHost?) {
        if (shared != null || disposed) return
        val softWrap = host?.windowService(SoftWrap::class.java) ?: return
        shared = softWrap.enabled.also(wrapping::bindBidirectional)
    }

    /** Stops following the shared switch, which outlives the view. */
    fun dispose() {
        disposed = true
        shared?.let(wrapping::unbindBidirectional)
        shared = null
    }

    companion object {
        /** Shortcut+Alt+Z. */
        val KEY = KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN, KeyCombination.ALT_DOWN)
    }
}
