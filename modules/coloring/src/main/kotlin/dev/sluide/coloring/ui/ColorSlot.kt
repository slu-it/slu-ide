package dev.sluide.coloring.ui

import dev.sluide.coloring.color.CssColor
import javafx.beans.property.ObjectProperty
import javafx.beans.property.SimpleObjectProperty

/** A named color a tab of the Coloring tool works on; the left column edits it while it is [ColoringState.editing]. */
internal class ColorSlot(
    val tab: String,
    val label: String,
    initial: CssColor,
) {
    val color: ObjectProperty<CssColor> = SimpleObjectProperty(initial)
    val title: String get() = "$tab › $label"
}
