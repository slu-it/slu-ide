package dev.sluide.coloring.ui

import dev.sluide.coloring.ui.picker.AlphaSlider
import dev.sluide.coloring.ui.picker.PickerModel
import dev.sluide.coloring.ui.picker.SquarePicker
import dev.sluide.coloring.ui.picker.WheelPicker
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.control.ToggleButton
import javafx.scene.control.ToggleGroup
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.util.Subscription

/**
 * The color picker for the current color: a saturation/value square with a hue strip, or a hue/saturation wheel with a
 * value slider (switched by the toggle buttons at the top), and an alpha slider below. The picker keeps its own hue,
 * saturation, value and alpha ([PickerModel]); it writes HSV colors to the state and follows colors set from outside.
 */
internal class PickerSection(
    private val state: ColoringState,
) {
    private val model = PickerModel(state.current.get()) { state.current.set(it) }
    internal val square = SquarePicker(model)
    internal val wheel = WheelPicker(model)

    // Each mode has an alpha slider as wide as its own row.
    internal val squareAlpha = AlphaSlider(model, SquarePicker.WIDTH + SquarePicker.GAP + SquarePicker.STRIP_WIDTH)
    internal val wheelAlpha = AlphaSlider(model, WheelPicker.SIZE + WheelPicker.GAP + WheelPicker.SLIDER_WIDTH)
    private val content = VBox(8.0).apply { alignment = Pos.CENTER_LEFT }
    private val toggles = ToggleGroup()

    private val modeViews = listOf(listOf(square.node, squareAlpha.node), listOf(wheel.node, wheelAlpha.node))

    /** The buttons "Square" and "Wheel". */
    internal val modeButtons: List<ToggleButton> =
        listOf("Square", "Wheel").map { label -> ToggleButton(label).apply { toggleGroup = toggles } }

    private val stateSubscription: Subscription

    val node: Node =
        VBox(
            8.0,
            HBox(
                8.0,
                Label("Picker").apply { styleClass += "coloring-section-title" },
                Region().also { HBox.setHgrow(it, Priority.ALWAYS) },
                *modeButtons.toTypedArray(),
            ).apply { alignment = Pos.CENTER_LEFT },
            content,
        ).apply { styleClass += "coloring-picker" }

    init {
        toggles.selectedToggleProperty().subscribe { old, new ->
            // A mode is always selected: clicking the selected button keeps it.
            if (new == null) toggles.selectToggle(old) else content.children.setAll(modeViews[modeButtons.indexOf(new)])
        }
        toggles.selectToggle(modeButtons.first())
        model.onChange = {
            square.update()
            wheel.update()
            squareAlpha.update()
            wheelAlpha.update()
        }
        stateSubscription = state.current.subscribe { color -> model.external(color) }
    }

    /** Releases the listeners on the window's state. */
    fun dispose() {
        stateSubscription.unsubscribe()
        wheel.dispose()
    }
}
