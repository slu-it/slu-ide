package dev.sluide.translation.settings

import dev.sluide.localmodels.InstallState
import dev.sluide.localmodels.ModelInstaller
import dev.sluide.settings.SettingsPage
import dev.sluide.translation.engine.PairModel
import dev.sluide.translation.opusmt.OpusMtEngine
import javafx.application.Platform
import javafx.scene.Node
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.Label
import javafx.scene.control.Tooltip
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

private const val DELETE_TITLE = "Delete"

/**
 * Translation: the downloaded translation models with their size and Delete, the total size and the attributions.
 * Nothing to apply; the list follows the installer's states (a download that ends, a delete that completes).
 */
@Component
class TranslationSettingsPage internal constructor(
    private val engine: OpusMtEngine,
    private val installer: ModelInstaller<PairModel>,
    dialogs: TranslationSettingsDialogs?,
) : SettingsPage {
    @Autowired
    constructor(engine: OpusMtEngine, installer: ModelInstaller<PairModel>) : this(engine, installer, null)

    private val dialogs: TranslationSettingsDialogs = dialogs ?: FxDialogs { node.scene?.window }

    internal val model = TranslationSettingsPageModel(engine, installer, this.dialogs)

    override val path = listOf("Translation")

    override val node: Node by lazy(::createNode)

    override fun load() {
        node
        refresh()
    }

    override fun apply() = load()

    // Created with the node, on the FX thread.
    private lateinit var rows: VBox
    private lateinit var total: Label
    private var ready = false

    /** The delete buttons by model, for tests. */
    internal val deleteButtons = HashMap<PairModel, Button>()

    /** Whether the list shows the empty caption, for tests. */
    internal val emptyShown: Boolean
        get() = rows.children.any { it is Label && it.text == TranslationSettingsPageModel.EMPTY }

    /** The text of the total and whether it is shown, for tests. */
    internal val totalShown: Pair<String, Boolean>
        get() = total.text to (total.isVisible && total.isManaged)

    private fun createNode(): Node {
        rows = VBox(6.0)
        total = Label().apply { styleClass += "label-heading" }
        for (m in engine.models) {
            installer.state(m).subscribe { state -> if (ready && state !is InstallState.Downloading) refresh() }
        }
        ready = true
        refresh()
        val heading = Label("Downloaded models").apply { styleClass += "label-heading" }
        return VBox(
            10.0,
            heading,
            rows,
            total,
            caption(TranslationSettingsPageModel.ATTRIBUTION_MODELS),
            caption(TranslationSettingsPageModel.ATTRIBUTION_DETECTION),
        ).apply { styleClass += "translation-settings-page" }
    }

    private fun refresh() {
        rows.children.clear()
        deleteButtons.clear()
        val installed = model.installedModels()
        val list = model.rows(installed)
        if (list.isEmpty()) rows.children += caption(TranslationSettingsPageModel.EMPTY)
        for (row in list) {
            val label = Label(row.label).apply { prefWidth = 280.0 }
            val delete = Button("Delete").apply { setOnAction { model.delete(row.model) } }
            deleteButtons[row.model] = delete
            val failure = row.message
            val status =
                Label(failure).apply {
                    isWrapText = true
                    prefWidth = 240.0
                    maxHeight = 60.0
                    tooltip = failure?.let(::Tooltip)
                    isVisible = failure != null
                    isManaged = failure != null
                }
            rows.children += HBox(8.0, label, status, delete).apply { styleClass += "translation-model-row" }
        }
        total.text = TranslationSettingsPageModel.totalLabel(model.totalBytes(installed))
        total.isVisible = installed.isNotEmpty()
        total.isManaged = installed.isNotEmpty()
    }

    private fun caption(text: String) =
        Label(text).apply {
            styleClass += "preview-caption"
            isWrapText = true
        }

    /** The real questions: a confirmation with Cancel as the default button, owned by the window of the page. */
    private class FxDialogs(
        private val owner: () -> javafx.stage.Window?,
    ) : TranslationSettingsDialogs {
        override fun confirmDelete(question: String): Boolean {
            val delete = ButtonType(DELETE_TITLE, ButtonBar.ButtonData.OK_DONE)
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
