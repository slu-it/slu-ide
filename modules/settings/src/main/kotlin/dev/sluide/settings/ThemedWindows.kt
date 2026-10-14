package dev.sluide.settings

import javafx.scene.Scene
import javafx.stage.Window
import javafx.util.Subscription
import org.springframework.stereotype.Component
import java.util.WeakHashMap

/**
 * Gives every window (main window, dialogs, menus, popups) the colour scheme of the [ThemeSettings] and the
 * `theme.css` that styles Modena for it. Other stylesheets add their own `@media (prefers-color-scheme: dark)` rules.
 */
@Component
class ThemedWindows(
    private val settings: ThemeSettings,
) {
    private val stylesheet = checkNotNull(ThemedWindows::class.java.getResource("theme.css")).toExternalForm()
    private val watched = WeakHashMap<Window, Unit>()
    private var installed = false
    private var subscription = Subscription.EMPTY

    /** Themes all open windows and every window opened later. Must be called on the FX thread. */
    fun install() {
        if (installed) return
        installed = true
        Window.getWindows().forEach(::watch)
        subscription =
            Subscription.combine(
                Window.getWindows().subscribe { Window.getWindows().forEach(::watch) },
                settings.colorScheme.subscribe { _ -> Window.getWindows().forEach { it.scene?.let(::style) } },
            )
    }

    /** Stops theming windows, for tests, which share the window list. Must be called on the FX thread. */
    internal fun uninstall() {
        installed = false
        subscription.unsubscribe()
        subscription = Subscription.EMPTY
    }

    private fun watch(window: Window) {
        if (watched.put(window, Unit) != null) return
        window.sceneProperty().subscribe { scene -> if (installed) scene?.let(::style) }
    }

    private fun style(scene: Scene) {
        if (stylesheet !in scene.stylesheets) scene.stylesheets += stylesheet
        scene.preferences.colorScheme = settings.colorScheme.get()
    }
}
