package dev.sluide.python.settings

import dev.sluide.python.PythonSettings
import dev.sluide.python.TyInstaller
import dev.sluide.python.TyUpdates
import dev.sluide.settings.SettingsPage
import javafx.application.Platform
import javafx.beans.Observable
import javafx.scene.Node
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.Hyperlink
import javafx.scene.control.Label
import javafx.scene.control.RadioButton
import javafx.scene.control.TextField
import javafx.scene.control.ToggleGroup
import javafx.scene.control.Tooltip
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.stage.FileChooser
import javafx.stage.Window
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.io.File
import java.net.URI
import java.nio.file.Path

private const val DELETE_TITLE = "Delete ty"

/**
 * Languages → Python: ty, the language server of Python code intelligence (ADR 0144). Install, Check for Updates,
 * Update, Roll Back and Delete act at once; the choice between Thoreau's download and a custom executable changes on
 * OK or Apply.
 */
@Component
class PythonSettingsPage internal constructor(
    settings: PythonSettings,
    installer: TyInstaller,
    updates: TyUpdates,
    dialogs: PythonSettingsDialogs?,
) : SettingsPage {
    @Autowired
    constructor(settings: PythonSettings, installer: TyInstaller, updates: TyUpdates) :
        this(settings, installer, updates, null)

    /** Opens the release notes; `ide` sets it to the system browser at start-up. */
    var openWebPage: (URI) -> Unit = {}

    private val dialogs: PythonSettingsDialogs = dialogs ?: FxDialogs { node.scene?.window }

    internal val model = PythonSettingsPageModel(settings, installer, updates, this.dialogs) { openWebPage(it) }

    private val observed: List<Observable> =
        listOf(installer.state, installer.installed, installer.previous, model.updateCheck, model.checking)

    override val path = listOf("Languages", "Python")

    override val node: Node by lazy(::createNode)

    override fun load() = model.load()

    override fun apply() = model.apply()

    /** The page's controls, created with the node on the FX thread; for tests. */
    internal class Widgets {
        val status =
            Label().apply {
                isWrapText = true
                tooltip = Tooltip()
            }
        val installButton = Button("Install")
        val checkButton = Button("Check for Updates")
        val rollbackButton = Button()
        val pinButton = Button()
        val deleteButton = Button("Delete…")
        val result = Label().apply { isWrapText = true }
        val releaseNotes = Hyperlink("Release Notes")
        val updateButton = Button("Update")
        val downloadRadio = RadioButton("Thoreau's download")
        val customRadio = RadioButton("Custom path")
        val customField = TextField().apply { promptText = "Absolute path of a ty executable" }
        val browseButton = Button("Browse…")
        val customHint = Label().apply { styleClass += "preview-caption" }
    }

    internal lateinit var widgets: Widgets

    private fun createNode(): Node = Widgets().also { widgets = it }.layOut()

    private fun Widgets.layOut(): Node {
        installButton.setOnAction { model.installOrCancel() }
        checkButton.setOnAction { model.checkForUpdates() }
        rollbackButton.setOnAction { model.rollback() }
        pinButton.setOnAction { model.installPinned() }
        deleteButton.setOnAction { model.delete() }
        releaseNotes.setOnAction { model.openReleaseNotes() }
        updateButton.setOnAction { model.update() }
        browseButton.setOnAction { model.browse() }
        val group = ToggleGroup()
        downloadRadio.toggleGroup = group
        customRadio.toggleGroup = group
        downloadRadio.setOnAction { model.useCustom.set(false) }
        customRadio.setOnAction { model.useCustom.set(true) }
        customField.textProperty().bindBidirectional(model.customPath)
        model.useCustom.subscribe { custom ->
            customRadio.isSelected = custom
            downloadRadio.isSelected = !custom
            customField.isDisable = !custom
            browseButton.isDisable = !custom
        }
        model.useCustom.subscribe { _ -> customHint.text = model.customHint() }
        model.customPath.subscribe { _ -> customHint.text = model.customHint() }
        for (observable in observed) observable.subscribe { refresh() }
        refresh()

        val heading = Label("ty, the language server for Python").apply { styleClass += "label-heading" }
        val actions = HBox(8.0, installButton, pinButton, checkButton, rollbackButton, deleteButton)
        val update = HBox(8.0, result, releaseNotes, updateButton)
        val executableHeading = Label("ty executable").apply { styleClass += "label-heading" }
        val custom = HBox(8.0, customRadio, customField, browseButton)
        HBox.setHgrow(customField, Priority.ALWAYS)
        val note =
            caption(
                "Thoreau downloads ty from GitHub only on Install or Update, and checks for updates only when asked. " +
                    "ty runs no program of the project.",
            )
        return VBox(10.0, heading, status, actions, update, executableHeading, downloadRadio, custom, customHint, note)
            .apply { styleClass += "python-settings-page" }
    }

    private fun Widgets.refresh() {
        val controls = model.controls()
        status.text = controls.status
        // A long failure message is wrapped and clipped by the label; the tooltip has all of it.
        status.tooltip.text = controls.status
        installButton.text = controls.installText
        installButton.isDisable = controls.installDisabled
        checkButton.isDisable = controls.checkDisabled
        result.text = controls.result.orEmpty()
        shown(result, controls.result != null)
        shown(releaseNotes, controls.releaseNotesShown)
        shown(updateButton, controls.updateShown)
        updateButton.isDisable = controls.updateDisabled
        rollbackButton.text = controls.rollbackText.orEmpty()
        shown(rollbackButton, controls.rollbackText != null)
        rollbackButton.isDisable = controls.rollbackDisabled
        pinButton.text = controls.pinText.orEmpty()
        shown(pinButton, controls.pinText != null)
        pinButton.isDisable = controls.pinDisabled
        deleteButton.isDisable = controls.deleteDisabled
    }

    private fun shown(
        target: Node,
        visible: Boolean,
    ) {
        target.isVisible = visible
        target.isManaged = visible
    }

    private fun caption(text: String) =
        Label(text).apply {
            styleClass += "preview-caption"
            isWrapText = true
        }

    /** The real questions, owned by the window of the page. */
    private class FxDialogs(
        private val owner: () -> Window?,
    ) : PythonSettingsDialogs {
        /** A confirmation with Cancel as the default button and focused. */
        override fun confirmDelete(question: String): Boolean {
            val delete = ButtonType("Delete", ButtonBar.ButtonData.OK_DONE)
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

        override fun chooseExecutable(current: String): Path? {
            val chooser = FileChooser().apply { title = "ty Executable" }
            val folder = current.takeIf { it.isNotBlank() }?.let { File(it).parentFile }
            if (folder != null && folder.isDirectory) chooser.initialDirectory = folder
            return chooser.showOpenDialog(owner())?.toPath()
        }
    }
}
