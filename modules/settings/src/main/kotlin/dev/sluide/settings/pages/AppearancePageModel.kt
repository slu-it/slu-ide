package dev.sluide.settings.pages

import dev.sluide.settings.Theme
import dev.sluide.settings.ThemeSettings
import javafx.beans.property.ObjectProperty
import javafx.beans.property.SimpleObjectProperty

/** The edit on the Appearance page: a draft theme, applied to [settings] only by [apply]. */
internal class AppearancePageModel(
    private val settings: ThemeSettings,
) {
    val draft: ObjectProperty<Theme> = SimpleObjectProperty(settings.theme.get())

    /** Drops the edit: the draft shows the current theme again. */
    fun load() = draft.set(settings.theme.get())

    fun apply() = settings.apply(draft.get())
}
