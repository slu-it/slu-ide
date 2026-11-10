package dev.sluide.games.common.ui

import dev.sluide.games.common.ZoomRange
import javafx.beans.property.ReadOnlyDoubleProperty
import javafx.beans.property.ReadOnlyDoubleWrapper
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.MenuButton
import javafx.scene.control.OverrunStyle
import javafx.scene.control.RadioMenuItem
import javafx.scene.control.ToggleGroup
import javafx.scene.control.Tooltip
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyEvent
import javafx.scene.input.ScrollEvent
import javafx.scene.input.ZoomEvent
import javafx.scene.layout.HBox
import javafx.scene.text.Text
import org.kordamp.ikonli.Ikon
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignM
import kotlin.math.ceil
import kotlin.math.pow

/** What a zoom key does in a game. */
internal enum class GameZoomKey { ZOOM_IN, ZOOM_OUT, FIT }

/**
 * The action of [code] with [shortcut] held: Plus, `=` and numpad + zoom in, Minus and numpad − zoom out, 0 and numpad
 * 0 fit. Null for other keys (Shortcut+9 is the Git Log key on macOS) and without Shortcut.
 */
internal fun gameZoomKey(
    code: KeyCode,
    shortcut: Boolean,
): GameZoomKey? =
    if (!shortcut) {
        null
    } else {
        when (code) {
            KeyCode.PLUS, KeyCode.EQUALS, KeyCode.ADD -> GameZoomKey.ZOOM_IN
            KeyCode.MINUS, KeyCode.SUBTRACT -> GameZoomKey.ZOOM_OUT
            KeyCode.DIGIT0, KeyCode.NUMPAD0 -> GameZoomKey.FIT
            else -> null
        }
    }

/** The factor a wheel turn by [deltaY] zooms by: only with [shortcut] held, else null (the view scrolls). */
internal fun wheelZoomFactor(
    deltaY: Double,
    shortcut: Boolean,
): Double? = if (shortcut && deltaY != 0.0) 1.1.pow(deltaY / 40) else null

/**
 * The zoom of a game (ADR 0155): a toolbar group [node] with zoom out, the [levelMenu] of presets, zoom in and Fit, and
 * the keys, Shortcut+wheel and pinch anywhere in the game ([install]). [zoom] is a factor on the game's Fit size,
 * within [range]; [onZoom] is called after every change of it (not for [initial]).
 *
 * FX thread only.
 */
internal class GameZoomControls(
    val range: ZoomRange,
    initial: Double,
    private val onZoom: (Double) -> Unit,
) {
    private val zoomWrapper = ReadOnlyDoubleWrapper(range.clamp(initial))

    /** The zoom, within [range]. */
    val zoom: ReadOnlyDoubleProperty = zoomWrapper.readOnlyProperty

    private val levelGroup = ToggleGroup()
    private val levelItems =
        range.presets.associateWith { preset ->
            RadioMenuItem(range.label(preset)).apply {
                toggleGroup = levelGroup
                setOnAction { zoomTo(preset) }
            }
        }

    val zoomOutButton = button(MaterialDesignM.MAGNIFY_MINUS_OUTLINE, "Zoom Out", ZOOM_OUT_KEY, ::zoomOut)
    val levelMenu =
        MenuButton().apply {
            styleClass += "game-zoom-level"
            isFocusTraversable = false
            tooltip = Tooltip("Zoom")
            alignment = Pos.CENTER
            // A value between presets ("188%") can be a pixel wider than the widest preset in proportional fonts.
            textOverrun = OverrunStyle.CLIP
            items.setAll(levelItems.values)
            skinProperty().subscribe { skin -> if (skin != null) fixLabelWidth(this) }
        }
    val zoomInButton = button(MaterialDesignM.MAGNIFY_PLUS_OUTLINE, "Zoom In", ZOOM_IN_KEY, ::zoomIn)
    val fitButton =
        Button("Fit").apply {
            isFocusTraversable = false
            tooltip = Tooltip("Fit (${FIT_KEY.displayText})")
            setOnAction { fit() }
        }

    val node: Node =
        HBox(zoomOutButton, levelMenu, zoomInButton, fitButton).apply {
            styleClass += "game-zoom"
            alignment = Pos.CENTER_LEFT
            stylesheets +=
                checkNotNull(GameZoomControls::class.java.getResource("/dev/sluide/games/common/game-zoom.css"))
                    .toExternalForm()
        }

    init {
        show()
    }

    fun zoomIn() = zoomTo(range.zoomIn(zoom.get()))

    fun zoomOut() = zoomTo(range.zoomOut(zoom.get()))

    fun fit() = zoomTo(1.0)

    /** Zooms to [zoom], clamped to the [range]. */
    fun zoomTo(zoom: Double) {
        val value = range.clamp(zoom)
        val changed = value != zoomWrapper.get()
        zoomWrapper.set(value)
        // Also when unchanged: choosing the checked item again must leave it checked.
        show()
        if (changed) onZoom(value)
    }

    /** Zooms by [factor] (wheel, pinch), clamped to the [range]; ignored when it is no positive number. */
    fun zoomBy(factor: Double) {
        if (factor.isFinite() && factor > 0) zoomTo(zoom.get() * factor)
    }

    /**
     * Zooms with the keys, Shortcut+wheel and pinching anywhere in [root], through event filters that consume what they
     * handle, so that neither the game nor a scroll pane in it sees them.
     */
    fun install(root: Node) {
        root.addEventFilter(KeyEvent.KEY_PRESSED) { event ->
            val action =
                when (gameZoomKey(event.code, event.isShortcutDown)) {
                    GameZoomKey.ZOOM_IN -> ::zoomIn
                    GameZoomKey.ZOOM_OUT -> ::zoomOut
                    GameZoomKey.FIT -> ::fit
                    null -> null
                }
            if (action != null) {
                action()
                event.consume()
            }
        }
        root.addEventFilter(ScrollEvent.SCROLL) { event ->
            val factor = wheelZoomFactor(event.deltaY, event.isShortcutDown)
            if (factor != null) {
                zoomBy(factor)
                event.consume()
            }
        }
        root.addEventFilter(ZoomEvent.ZOOM) { event ->
            zoomBy(event.zoomFactor)
            event.consume()
        }
    }

    private fun show() {
        val value = zoom.get()
        levelMenu.text = range.label(value)
        levelGroup.selectToggle(range.preset(value)?.let(levelItems::get))
        zoomOutButton.isDisable = value <= range.min
        zoomInButton.isDisable = value >= range.max
    }

    /**
     * One width for every text of the menu's label, so that the buttons beside it stay in place: that of the widest
     * preset, in any font.
     */
    private fun fixLabelWidth(menu: MenuButton) {
        val label = menu.lookup(".label") as? Label ?: return
        val labels = range.presets.map(range::label)

        fun fix() {
            val widest = labels.maxOf { text -> Text(text).also { it.font = label.font }.layoutBounds.width }
            val width = ceil(widest + label.insets.left + label.insets.right)
            label.minWidth = width
            label.prefWidth = width
            label.maxWidth = width
        }
        label.fontProperty().subscribe { _ -> fix() }
        label.insetsProperty().subscribe { _ -> fix() }
    }

    private fun button(
        icon: Ikon,
        tooltip: String,
        key: KeyCombination,
        action: () -> Unit,
    ) = Button(null, FontIcon(icon).apply { iconSize = ICON_SIZE }).apply {
        isFocusTraversable = false
        this.tooltip = Tooltip("$tooltip (${key.displayText})")
        setOnAction { action() }
    }

    private companion object {
        const val ICON_SIZE = 16
        val ZOOM_IN_KEY = KeyCodeCombination(KeyCode.PLUS, KeyCombination.SHORTCUT_DOWN)
        val ZOOM_OUT_KEY = KeyCodeCombination(KeyCode.MINUS, KeyCombination.SHORTCUT_DOWN)
        val FIT_KEY = KeyCodeCombination(KeyCode.DIGIT0, KeyCombination.SHORTCUT_DOWN)
    }
}
