package dev.sluide.speech.settings

import dev.sluide.localmodels.InstallState
import dev.sluide.settings.SettingsPage
import dev.sluide.speech.install.KokoroVariant
import dev.sluide.speech.install.ModelInstaller
import javafx.application.Platform
import javafx.scene.Node
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.Label
import javafx.scene.control.ProgressBar
import javafx.scene.control.RadioButton
import javafx.scene.control.TextField
import javafx.scene.control.ToggleGroup
import javafx.scene.control.Tooltip
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import org.springframework.stereotype.Component

private const val DELETE_TITLE = "Delete"

/** Speech: the path of espeak-ng and the Kokoro voice models. The path and the variant change on OK or Apply. */
@Component
class SpeechSettingsPage internal constructor(
    settings: SpeechSettings,
    private val installer: ModelInstaller,
    dialogs: SpeechSettingsDialogs?,
) : SettingsPage {
    @org.springframework.beans.factory.annotation.Autowired
    constructor(settings: SpeechSettings, installer: ModelInstaller) : this(settings, installer, null)

    private val dialogs: SpeechSettingsDialogs = dialogs ?: FxDialogs { node.scene?.window }

    internal val model = SpeechSettingsPageModel(settings, installer, this.dialogs)

    override val path = listOf("Speech")

    override val node: Node by lazy(::createNode)

    override fun load() = model.load()

    override fun apply() = model.apply()

    /** The espeak-ng field and the buttons per variant, for tests. */
    internal lateinit var espeak: TextField
    internal val downloadButtons = HashMap<KokoroVariant, Button>()
    internal val deleteButtons = HashMap<KokoroVariant, Button>()
    internal val radios = HashMap<KokoroVariant, RadioButton>()

    private fun createNode(): Node {
        espeak =
            TextField().apply {
                promptText = "Found on the login shell's PATH"
                prefColumnCount = 32
                textProperty().bindBidirectional(model.espeak)
            }
        val grid = GridPane().apply { hgap = 8.0 }
        grid.addRow(0, Label("espeak-ng"), espeak)
        GridPane.setHgrow(espeak, Priority.ALWAYS)
        val note = caption("Path to espeak-ng; leave empty to find it on the login shell's PATH.")
        val models = VBox(6.0, Label("Voice model").apply { styleClass += "label-heading" })
        val group = ToggleGroup()
        for (v in KokoroVariant.entries) models.children += row(v, group)
        return VBox(10.0, grid, note, models).apply { styleClass += "speech-settings-page" }
    }

    private fun row(
        v: KokoroVariant,
        group: ToggleGroup,
    ): Node {
        val radio =
            RadioButton(SpeechSettingsPageModel.rowLabel(v)).apply {
                toggleGroup = group
                isSelected = model.variant.get() == v
                setOnAction { model.variant.set(v) }
                prefWidth = 280.0
            }
        model.variant.subscribe { selected -> radio.isSelected = selected == v }
        radios[v] = radio
        val status =
            Label().apply {
                isWrapText = true
                prefWidth = 240.0
                maxHeight = 60.0
            }
        val bar = ProgressBar(0.0)
        val download = Button("Download")
        val delete = Button("Delete").apply { setOnAction { model.delete(v) } }
        download.setOnAction {
            if (installer.state(v).get() is InstallState.Downloading) model.cancel(v) else model.download(v)
        }
        downloadButtons[v] = download
        deleteButtons[v] = delete
        installer.state(v).subscribe { state ->
            status.text = SpeechSettingsPageModel.statusText(state)
            // A long failure message is wrapped and clipped by the label; the tooltip has all of it.
            status.tooltip = if (state is InstallState.Failed) Tooltip(state.message) else null
            val downloading = state is InstallState.Downloading
            bar.isVisible = downloading
            bar.isManaged = downloading
            if (state is InstallState.Downloading) bar.progress = state.progress
            download.text = if (downloading) "Cancel" else "Download"
            download.isDisable = state is InstallState.Installed
            delete.isDisable = state !is InstallState.Installed
        }
        return HBox(8.0, radio, status, bar, download, delete).apply {
            styleClass += "speech-model-row"
        }
    }

    private fun caption(text: String) =
        Label(text).apply {
            styleClass += "preview-caption"
            isWrapText = true
        }

    /** The real questions: a confirmation with Cancel as the default button, owned by the window of the page. */
    private class FxDialogs(
        private val owner: () -> javafx.stage.Window?,
    ) : SpeechSettingsDialogs {
        override fun confirmDelete(question: String): Boolean {
            val delete = ButtonType(DELETE_TITLE, javafx.scene.control.ButtonBar.ButtonData.OK_DONE)
            val alert =
                Alert(Alert.AlertType.CONFIRMATION, null, delete, ButtonType.CANCEL).apply {
                    owner()?.let(::initOwner)
                    title = DELETE_TITLE
                    headerText = question
                    val cancel = dialogPane.lookupButton(ButtonType.CANCEL) as Button
                    (dialogPane.lookupButton(delete) as Button).isDefaultButton = false
                    cancel.isDefaultButton = true
                    // Later, since the dialog focuses a button of its own once shown.
                    setOnShown { Platform.runLater(cancel::requestFocus) }
                }
            return alert.showAndWait().orElse(ButtonType.CANCEL) == delete
        }
    }
}
