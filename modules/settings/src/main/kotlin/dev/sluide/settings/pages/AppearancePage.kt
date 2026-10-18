package dev.sluide.settings.pages

import dev.sluide.settings.SettingsPage
import dev.sluide.settings.Theme
import dev.sluide.settings.ThemeSettings
import javafx.scene.Node
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.layout.GridPane
import javafx.scene.layout.VBox
import org.springframework.stereotype.Component

/** Display → Appearance: the light, dark or system theme. Nothing changes in the IDE until the dialog's OK or Apply. */
@Component
class AppearancePage(
    settings: ThemeSettings,
) : SettingsPage {
    internal val model = AppearancePageModel(settings)

    override val path = listOf("Display", "Appearance")

    override val node: Node by lazy(::createNode)

    override fun load() = model.load()

    override fun apply() = model.apply()

    /** The theme choice, for tests. */
    internal lateinit var theme: ComboBox<Theme>

    private fun createNode(): Node {
        theme =
            ComboBox<Theme>().apply {
                items.setAll(Theme.entries)
                valueProperty().bindBidirectional(model.draft)
            }
        val grid = GridPane().apply { hgap = 8.0 }
        grid.addRow(0, Label("Theme"), theme)
        val note =
            Label("System follows the light or dark mode of the operating system. The terminal stays dark.").apply {
                styleClass += "preview-caption"
            }
        return VBox(8.0, grid, note).apply { styleClass += "appearance-page" }
    }
}
