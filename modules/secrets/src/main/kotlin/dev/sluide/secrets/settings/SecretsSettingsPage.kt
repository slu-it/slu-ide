package dev.sluide.secrets.settings

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.secrets.SavedSecretSource
import dev.sluide.secrets.Secrets
import dev.sluide.settings.SettingsPage
import javafx.application.Platform
import javafx.scene.Node
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.Label
import javafx.scene.control.Tooltip
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.stage.Window
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * Secrets: the secret store in use, the saved passphrases of every [SavedSecretSource] with Remove, and Forget All.
 * Removals wait for the dialog's OK or Apply; Forget All Saved Secrets acts at once, after its question, and is
 * not undone by Cancel.
 */
@Component
class SecretsSettingsPage internal constructor(
    secrets: Secrets,
    sources: () -> List<SavedSecretSource>,
) : SettingsPage {
    @Autowired
    constructor(secrets: Secrets, sources: ObjectProvider<SavedSecretSource>) :
        this(secrets, { sources.orderedStream().toList() })

    internal val model = SecretsSettingsPageModel(secrets, sources)

    override val path = listOf("Secrets")

    override val node: Node by lazy(::createNode)

    override fun load() {
        model.load()
        if (contentCreated) {
            statusLabel.text = ""
            render()
        }
    }

    override fun apply() {
        try {
            model.apply()
        } finally {
            if (contentCreated) render()
        }
    }

    /** Asks before Forget All; replaced by tests. */
    internal var confirmForgetAll: () -> Boolean = ::askForgetAll

    /** The parts of the page, for tests. */
    internal lateinit var storeLabel: Label
    internal lateinit var statusLabel: Label
    internal lateinit var forgetButton: Button
    internal val content = VBox(8.0).apply { styleClass += "secrets-settings-page" }
    internal val removeButtons = mutableListOf<Button>()
    internal val entryLabels = mutableListOf<Label>()

    private var forgetting = false
    private val forgetExecutor by lazy { NamedThreads.single("sluide-secrets-forget") }
    private val contentCreated get() = ::storeLabel.isInitialized

    private fun createNode(): Node {
        storeLabel = Label()
        statusLabel = hint("")
        forgetButton = Button("Forget All Saved Secrets…").apply { setOnAction { forgetAll() } }
        model.load()
        render()
        return content
    }

    private fun render() {
        removeButtons.clear()
        entryLabels.clear()
        val store = HBox(8.0, storeLabel)
        storeLabel.text = model.storeLine()
        if (model.denied()) {
            store.children +=
                Button("Try Again").apply {
                    setOnAction {
                        model.tryAgain()
                        render()
                    }
                }
        }
        val items = mutableListOf<Node>(store)
        if (model.sections.isEmpty()) {
            items += Label("Nothing saved here yet.")
            items += hint("Database passwords are managed on their connection cards.")
        }
        model.sections.forEachIndexed { index, section ->
            items += Label(section.source.title).apply { style = "-fx-font-weight: bold;" }
            section.entries.forEach { entry -> items += row(index, entry.id, entry.label, entry.detail) }
        }
        forgetButton.isDisable = forgetting || !model.canForgetAll()
        items += forgetButton
        items += statusLabel
        content.children.setAll(items)
    }

    private fun row(
        section: Int,
        id: String,
        label: String,
        detail: String,
    ): Node {
        val text = Label(label).apply { tooltip = Tooltip(detail) }
        val button = Button()

        fun show() {
            val marked = model.isPending(section, id)
            text.style = if (marked) "-fx-strikethrough: true; -fx-opacity: 0.5;" else ""
            button.text = if (marked) "Undo" else "Remove"
        }
        button.setOnAction {
            model.toggle(section, id)
            show()
        }
        show()
        entryLabels += text
        removeButtons += button
        val spacer = Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }
        return HBox(8.0, text, spacer, button)
    }

    private fun forgetAll() {
        if (!confirmForgetAll()) return
        forgetting = true
        statusLabel.text = ""
        forgetButton.isDisable = true
        forgetExecutor.execute {
            var message = "Saved secrets could not be forgotten."
            try {
                message = model.forgetAll()
            } finally {
                // Also after an Error, which NamedThreads then logs.
                Platform.runLater {
                    forgetting = false
                    render()
                    statusLabel.text = message
                }
            }
        }
    }

    private fun askForgetAll(): Boolean =
        forgetAllAlert(content.scene?.window).showAndWait().orElse(ButtonType.CANCEL) != ButtonType.CANCEL

    private fun hint(text: String) = Label(text).apply { styleClass += "preview-caption" }
}

/** The question before Forget All: Cancel is the default button and has the focus, so Enter keeps everything. */
internal fun forgetAllAlert(owner: Window?): Alert {
    val alert =
        Alert(
            Alert.AlertType.CONFIRMATION,
            "Every saved database password and passphrase becomes unreadable and is asked for again. " +
                "This cannot be undone.",
            ButtonType("Forget All", ButtonType.OK.buttonData),
            ButtonType.CANCEL,
        )
    alert.title = "Forget All Saved Secrets"
    alert.headerText = null
    owner?.let { alert.initOwner(it) }
    val cancel = alert.dialogPane.lookupButton(ButtonType.CANCEL) as Button
    alert.dialogPane.buttonTypes
        .filter { it != ButtonType.CANCEL }
        .forEach { (alert.dialogPane.lookupButton(it) as Button).isDefaultButton = false }
    cancel.isDefaultButton = true
    // Later, since the dialog focuses a button of its own once shown.
    alert.setOnShown { Platform.runLater(cancel::requestFocus) }
    return alert
}
