package dev.sluide.fileviewers.settings

import dev.sluide.librarycode.LibraryCodeSettings
import dev.sluide.settings.SettingsPage
import javafx.scene.Node
import javafx.scene.control.CheckBox
import javafx.scene.control.Label
import javafx.scene.layout.VBox
import org.springframework.stereotype.Component

/** Editor: whether library code opens read-only (ADR 0150); applies on OK or Apply. */
@Component
class EditorSettingsPage(
    settings: LibraryCodeSettings,
) : SettingsPage {
    internal val model by lazy { EditorSettingsPageModel(settings) } // reads the stored value on first use

    override val path = listOf("Editor")

    override val node: Node by lazy(::createNode)

    /** The check box, created with the node on the FX thread; for tests. */
    internal lateinit var libraryCheckBox: CheckBox

    override fun load() = model.load()

    override fun apply() = model.apply()

    private fun createNode(): Node {
        libraryCheckBox =
            CheckBox("Open library code read-only").apply {
                selectedProperty().bindBidirectional(model.libraryReadOnly)
            }
        val explanation =
            Label(EditorSettingsPageModel.EXPLANATION).apply {
                styleClass += "preview-caption"
                isWrapText = true
            }
        return VBox(10.0, libraryCheckBox, explanation).apply { styleClass += "editor-settings-page" }
    }
}
