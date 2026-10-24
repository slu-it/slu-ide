package dev.sluide.fileviewers

import javafx.beans.property.ObjectProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.scene.Node
import javafx.scene.control.SplitPane
import javafx.scene.control.ToggleButton
import javafx.scene.control.ToggleGroup
import javafx.scene.layout.Region

/**
 * What a view with a source side shows: the source alone, the source beside the other side, or the other side alone.
 */
enum class SplitMode { SOURCE, SPLIT, OTHER }

/**
 * The [source] beside [other] in a horizontal SplitPane ([node]), switched by toggle buttons "Source", "Split" and
 * [otherLabel] ([modeButtons], each with [buttonStyleClass], not focus traversable). Clicking the selected button keeps
 * it selected; the divider position (start 0.5) is kept while one side is hidden. FX thread.
 */
class SourceSplit(
    private val source: Node,
    private val other: Node,
    otherLabel: String,
    buttonStyleClass: String,
    initial: SplitMode = SplitMode.SPLIT,
) {
    val node = SplitPane()

    val mode: ObjectProperty<SplitMode> = SimpleObjectProperty(initial)

    private val toggles = ToggleGroup()

    val modeButtons: List<ToggleButton> =
        SplitMode.entries.map { mode ->
            val label =
                when (mode) {
                    SplitMode.SOURCE -> "Source"
                    SplitMode.SPLIT -> "Split"
                    SplitMode.OTHER -> otherLabel
                }
            ToggleButton(label).apply {
                toggleGroup = toggles
                userData = mode
                styleClass += buttonStyleClass
                isFocusTraversable = false
            }
        }

    /** Where the divider was when both sides last showed. */
    private var dividerPosition = DIVIDER_POSITION

    init {
        // Either side may get narrower than its content wants; the divider decides.
        (source as? Region)?.minWidth = 0.0
        (other as? Region)?.minWidth = 0.0
        toggles.selectedToggleProperty().subscribe { old, new ->
            // A mode is always selected: clicking the selected button keeps it.
            if (new == null) toggles.selectToggle(old) else mode.set(new.userData as SplitMode)
        }
        mode.subscribe { mode ->
            toggles.selectToggle(modeButtons.first { it.userData == mode })
            show(mode)
        }
    }

    private fun show(mode: SplitMode) {
        if (node.items.size == 2) dividerPosition = node.dividerPositions[0]
        when (mode) {
            SplitMode.SOURCE -> {
                node.items.setAll(source)
            }

            SplitMode.SPLIT -> {
                node.items.setAll(source, other)
                node.setDividerPositions(dividerPosition)
            }

            SplitMode.OTHER -> {
                node.items.setAll(other)
            }
        }
    }

    private companion object {
        const val DIVIDER_POSITION = 0.5
    }
}
