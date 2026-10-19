package dev.sluide.ide

import javafx.scene.image.Image
import javafx.stage.Stage
import javafx.stage.Window
import javafx.util.Subscription

/**
 * Gives every stage (main windows, the project picker, dialogs) the Thoreau logo as its icon, including stages opened
 * later; a stage with icons of its own keeps them. The images are rendered from `ide/packaging/thoreau.svg`. On macOS
 * the Dock shows the app's `.icns` instead; these matter on Linux and for `java -jar`.
 */
internal class WindowIcons {
    private val images =
        listOf(16, 32, 64, 128, 256).map { size ->
            Image(
                checkNotNull(
                    WindowIcons::class.java.getResource("/dev/sluide/ide/icon/thoreau-$size.png"),
                ).toExternalForm(),
            )
        }
    private var subscription: Subscription? = null

    /** Sets the icons of all open stages and of every stage opened later. Must be called on the FX thread. */
    fun install() {
        if (subscription != null) return
        Window.getWindows().forEach(::decorate)
        subscription = Window.getWindows().subscribe { Window.getWindows().forEach(::decorate) }
    }

    /** Stops decorating stages, for tests, which share the window list. Must be called on the FX thread. */
    fun uninstall() {
        subscription?.unsubscribe()
        subscription = null
    }

    private fun decorate(window: Window) {
        if (window is Stage && window.icons.isEmpty()) window.icons += images
    }
}
