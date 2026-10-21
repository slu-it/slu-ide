package dev.sluide.terminal.settings

import dev.sluide.settings.SettingsPage
import javafx.beans.property.IntegerProperty
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.control.Slider
import javafx.scene.layout.GridPane
import javafx.scene.layout.VBox
import org.springframework.stereotype.Component

/**
 * Terminal → Scrolling: the scroll speeds of trackpads and mouse wheels. Nothing changes in the IDE until the dialog's
 * OK or Apply.
 */
@Component
class TerminalScrollPage(
    settings: TerminalScrollSettings,
) : SettingsPage {
    internal val model = TerminalScrollPageModel(settings)

    override val path = listOf("Terminal", "Scrolling")

    override val node: Node by lazy(::createNode)

    override fun load() = model.load()

    override fun apply() = model.apply()

    /** The trackpad speed, for tests. */
    internal lateinit var trackpad: Slider

    /** The mouse wheel speed, for tests. */
    internal lateinit var wheel: Slider

    /** The labels showing the speeds, e.g. "35 %", for tests. */
    internal lateinit var trackpadValue: Label
    internal lateinit var wheelValue: Label

    private fun createNode(): Node {
        trackpad = slider(model.trackpad)
        wheel = slider(model.wheel)
        trackpadValue = valueLabel(model.trackpad)
        wheelValue = valueLabel(model.wheel)
        val grid = GridPane().apply { hgap = 8.0 }
        grid.addRow(0, Label("Trackpad"), trackpad, trackpadValue)
        grid.addRow(1, Label("Mouse wheel"), wheel, wheelValue)
        val note =
            Label(
                "100 % is the default speed. Lower the trackpad speed if small swipes scroll too far.\n" +
                    "Only macOS tells trackpads (and the Magic Mouse) apart; elsewhere touchpads use the wheel speed.",
            ).apply {
                styleClass += "preview-caption"
            }
        return VBox(8.0, grid, note).apply { styleClass += "terminal-scroll-page" }
    }

    private fun slider(draft: IntegerProperty) =
        Slider(
            TerminalScrollSettings.SPEEDS.first.toDouble(),
            TerminalScrollSettings.SPEEDS.last.toDouble(),
            draft.get().toDouble(),
        ).apply {
            blockIncrement = TerminalScrollSettings.STEP.toDouble()
            majorTickUnit = 50.0
            // Ticks 5 apart, counted from the minimum: clicks and keys snap to whole multiples of 5.
            minorTickCount = 9
            isSnapToTicks = true
            prefWidth = 240.0
            // Dragging the thumb sets any value; keep them whole multiples of 5 too.
            valueProperty().subscribe { value ->
                val snapped = TerminalScrollPageModel.snap(value.toDouble())
                if (value.toDouble() != snapped.toDouble()) this.value = snapped.toDouble()
            }
            valueProperty().bindBidirectional(draft)
        }

    private fun valueLabel(draft: IntegerProperty) =
        Label().apply {
            textProperty().bind(draft.asString("%d %%"))
            minWidth = 48.0
        }
}
