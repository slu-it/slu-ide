package dev.sluide.encryptedfiles.settings

import dev.sluide.settings.SettingsPage
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.control.Spinner
import javafx.scene.control.SpinnerValueFactory
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import javafx.util.StringConverter
import org.springframework.stereotype.Component

/** Editor → Encrypted Files: the idle lock time (ADR 0162); applies on OK or Apply. */
@Component
class EncryptedFilesSettingsPage(
    settings: EncryptedFilesSettings,
) : SettingsPage {
    internal val model by lazy { EncryptedFilesSettingsPageModel(settings) } // reads the stored value on first use

    override val path = listOf("Editor", "Encrypted Files")

    override val node: Node by lazy(::createNode)

    /** The spinner, created with the node on the FX thread; for tests. */
    internal lateinit var spinner: Spinner<Int>

    override fun load() = model.load()

    override fun apply() = model.apply()

    private fun createNode(): Node {
        spinner =
            Spinner<Int>(0, EncryptedFilesSettings.MAX_MINUTES, model.idleLockMinutes.get()).apply {
                isEditable = true
                val factory = valueFactory as SpinnerValueFactory.IntegerSpinnerValueFactory
                // Text that is no number keeps the last value; a number is clamped to the range.
                factory.converter =
                    object : StringConverter<Int>() {
                        override fun toString(value: Int?) = value?.toString().orEmpty()

                        override fun fromString(text: String?) =
                            text?.trim()?.toIntOrNull()?.coerceIn(0, EncryptedFilesSettings.MAX_MINUTES)
                                ?: factory.value
                    }
                // The text is committed on Enter (which then does not press the dialog's OK) and when the field loses
                // the focus, and goes back to the committed value; while typing nothing is written back into it.
                addEventFilter(KeyEvent.KEY_PRESSED) { event ->
                    if (event.code == KeyCode.ENTER) {
                        settle()
                        event.consume()
                    }
                }
                focusedProperty().subscribe { focused -> if (!focused) settle() }
                // The spinner is the draft's view: its value is the draft, and a loaded draft is its value.
                factory.valueProperty().subscribe { value -> if (value != null) model.idleLockMinutes.set(value) }
                model.idleLockMinutes.subscribe { value -> factory.value = value.toInt() }
            }
        val row = HBox(8.0, Label("Lock after N minutes idle (0 = never)"), spinner)
        val explanation =
            Label(EncryptedFilesSettingsPageModel.EXPLANATION).apply {
                styleClass += "preview-caption"
                isWrapText = true
            }
        return VBox(10.0, row, explanation).apply { styleClass += "editor-settings-page" }
    }

    private fun Spinner<Int>.settle() {
        commitValue()
        editor.text = valueFactory.converter.toString(valueFactory.value)
    }
}
