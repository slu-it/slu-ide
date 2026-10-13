package dev.sluide.settings

import javafx.application.ColorScheme
import javafx.application.Platform
import javafx.beans.binding.Bindings
import javafx.beans.binding.ObjectBinding
import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.beans.value.ObservableValue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

private const val KEY = "theme"

/**
 * The [Theme] of the IDE. Windows follow [colorScheme]; the settings dialog changes the theme with [apply], which also
 * stores it in the [SettingsStore]. A missing or unknown stored theme means [Theme.SYSTEM].
 */
@Component
class ThemeSettings(
    private val store: SettingsStore,
    systemColorScheme: ObservableValue<ColorScheme>,
) {
    /** Follows the operating system's colour scheme; needs the JavaFX toolkit. */
    @Autowired
    constructor(store: SettingsStore) : this(store, Platform.getPreferences().colorSchemeProperty())

    private val wrapper = ReadOnlyObjectWrapper(read(store.load()) ?: Theme.SYSTEM)

    /** The chosen theme. */
    val theme: ReadOnlyObjectProperty<Theme> = wrapper.readOnlyProperty

    /** The colour scheme the theme stands for; for [Theme.SYSTEM] the one of the operating system. */
    val colorScheme: ObjectBinding<ColorScheme> =
        Bindings.createObjectBinding(
            {
                when (wrapper.get()) {
                    Theme.LIGHT -> ColorScheme.LIGHT
                    Theme.DARK -> ColorScheme.DARK
                    Theme.SYSTEM -> systemColorScheme.value ?: ColorScheme.LIGHT
                }
            },
            wrapper,
            systemColorScheme,
        )

    /** Sets the theme and stores it. Must be called on the FX thread. */
    fun apply(theme: Theme) {
        if (theme == wrapper.get()) return
        store.update(mapOf(KEY to theme.name))
        wrapper.set(theme)
    }

    internal companion object {
        fun read(stored: Map<String, String>): Theme? =
            stored[KEY]?.let { name -> Theme.entries.find { it.name == name } }
    }
}
