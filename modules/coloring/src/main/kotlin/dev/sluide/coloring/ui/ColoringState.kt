package dev.sluide.coloring.ui

import dev.sluide.coloring.color.CssColor
import javafx.beans.property.BooleanProperty
import javafx.beans.property.ObjectProperty
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleObjectProperty

/** The colors the Coloring tool of one window works on. Used on the JavaFX thread. */
internal class ColoringState {
    /** The current color. */
    val current: ObjectProperty<CssColor> = SimpleObjectProperty(CssColor.fromRgb8(59, 130, 246))

    /** The contrast and mix partner of [current]. */
    val second: ObjectProperty<CssColor> = SimpleObjectProperty(CssColor.srgb(1.0, 1.0, 1.0))

    /** `true`: [current] is the text color and [second] the background (Contrast). */
    val currentIsText: BooleanProperty = SimpleBooleanProperty(true)
}
