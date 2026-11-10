package dev.sluide.servertools

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
import java.io.File
import java.net.URI
import java.nio.file.Path

/**
 * The texts of a server tool's page beyond the tool's name: the [language] whose code intelligence it serves (in the
 * delete question), the page's [heading], and the [note] at its end, which says where the tool is downloaded from and
 * what it runs.
 */
data class ServerToolPageTexts(
    val language: String,
    val heading: String,
    val note: String,
)

/**
 * A server tool's settings page at [path] (Languages → Python for ty, ADR 0159): Install, Check for Updates, Update,
 * Roll Back and Delete act at once; the choice between Thoreau's download and a custom executable changes on OK or
 * Apply. A language module's page bean extends it; [dialogs] null means the real questions.
 */
open class ServerToolSettingsPage(
    final override val path: List<String>,
    texts: ServerToolPageTexts,
    setting: ServerPathSetting,
    installer: ServerToolInstaller,
    updates: ToolUpdates,
    dialogs: ServerToolDialogs? = null,
) : SettingsPage {
    /** Opens the release notes; `ide` sets it to the system browser at start-up. */
    var openWebPage: (URI) -> Unit = {}

    private val name = installer.tool.name
    private val heading = texts.heading
    private val note = texts.note

    private val dialogs: ServerToolDialogs = dialogs ?: FxDialogs(name) { node.scene?.window }

    internal val model =
        ServerToolPageModel(texts.language, setting, installer, updates, this.dialogs) { openWebPage(it) }

    private val observed: List<Observable> =
        listOf(installer.state, installer.installed, installer.previous, model.updateCheck, model.checking)

    final override val node: Node by lazy(::createNode)

    final override fun load() = model.load()

    final override fun apply() = model.apply()

    /** The page's controls, created with the node on the FX thread; for tests. */
    internal class Widgets(
        name: String,
    ) {
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
        val customField = TextField().apply { promptText = "Absolute path of a $name executable" }
        val browseButton = Button("Browse…")
        val customHint = Label().apply { styleClass += "preview-caption" }
    }

    internal lateinit var widgets: Widgets

    private fun createNode(): Node = Widgets(name).also { widgets = it }.layOut()

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

        val headingLabel = Label(heading).apply { styleClass += "label-heading" }
        val actions = HBox(8.0, installButton, pinButton, checkButton, rollbackButton, deleteButton)
        val update = HBox(8.0, result, releaseNotes, updateButton)
        val executableHeading = Label("$name executable").apply { styleClass += "label-heading" }
        val custom = HBox(8.0, customRadio, customField, browseButton)
        HBox.setHgrow(customField, Priority.ALWAYS)
        return VBox(
            10.0,
            headingLabel,
            status,
            actions,
            update,
            executableHeading,
            downloadRadio,
            custom,
            customHint,
            caption(note),
        ).apply { styleClass += "server-tool-settings-page" }
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
        private val name: String,
        private val owner: () -> Window?,
    ) : ServerToolDialogs {
        /** A confirmation with Cancel as the default button and focused. */
        override fun confirmDelete(question: String): Boolean {
            val delete = ButtonType("Delete", ButtonBar.ButtonData.OK_DONE)
            val alert =
                Alert(Alert.AlertType.CONFIRMATION, null, delete, ButtonType.CANCEL).apply {
                    owner()?.let(::initOwner)
                    title = "Delete $name"
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
            val chooser = FileChooser().apply { title = "$name Executable" }
            val folder = current.takeIf { it.isNotBlank() }?.let { File(it).parentFile }
            if (folder != null && folder.isDirectory) chooser.initialDirectory = folder
            return chooser.showOpenDialog(owner())?.toPath()
        }
    }
}
