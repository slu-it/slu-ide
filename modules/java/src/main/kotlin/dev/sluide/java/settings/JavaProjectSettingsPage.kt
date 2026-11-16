package dev.sluide.java.settings

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.java.JavaHomes
import dev.sluide.java.JavaProjectSettings
import dev.sluide.java.JdtlsSpec
import dev.sluide.settings.ProjectSettingsPage
import javafx.application.Platform
import javafx.scene.Node
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.layout.VBox
import javafx.stage.DirectoryChooser
import javafx.stage.Window
import javafx.util.StringConverter
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.util.concurrent.Executor

/**
 * One login-shell call for all windows of an app run: [JavaHomes] asks once per instance. `JdtlsSpec` owns another
 * instance, so the login shell runs a second time per app run until the two share one (accepted for now).
 */
private val sharedHomes by lazy { JavaHomes() }

private fun chooseFolder(owner: Window?): Path? =
    DirectoryChooser()
        .apply { title = "Choose a JDK folder" }
        .showDialog(owner)
        ?.toPath()

/** The destructive question of Clean Java Workspace: Clean and Cancel, with Cancel the default and focused. */
private fun confirmClean(
    owner: Window?,
    question: String,
): Boolean = cleanConfirmation(owner, question).showAndWait().orElse(ButtonType.CANCEL) == CLEAN_BUTTON

internal val CLEAN_BUTTON = ButtonType("Clean", ButtonBar.ButtonData.OK_DONE)

/** The dialog of [confirmClean], not yet shown; for tests too. */
internal fun cleanConfirmation(
    owner: Window?,
    question: String,
): Alert =
    Alert(Alert.AlertType.CONFIRMATION, null, CLEAN_BUTTON, ButtonType.CANCEL).apply {
        owner?.let(::initOwner)
        title = "Clean Java Workspace"
        headerText = question
        val cancel = dialogPane.lookupButton(ButtonType.CANCEL) as Button
        (dialogPane.lookupButton(CLEAN_BUTTON) as Button).isDefaultButton = false
        cancel.isDefaultButton = true
        // Later, since the dialog focuses a button of its own once shown.
        setOnShown { Platform.runLater(cancel::requestFocus) }
    }

/**
 * Project Settings → Java: the JDK of the project (ADR 0179). The rules are in [JavaProjectSettingsPageModel]; this
 * is its combo box, the error under it and the note below. [chooser] asks for the folder of Other… (the window is the
 * owner).
 */
@Component
@Scope("window")
class JavaProjectSettingsPage internal constructor(
    settings: JavaProjectSettings,
    homes: JavaHomes,
    userHome: Path,
    macOs: Boolean,
    io: Executor,
    dispatch: (Runnable) -> Unit,
    private val chooser: (Window?) -> Path?,
    cleaner: WorkspaceCleaner,
    private val confirmer: (Window?, String) -> Boolean,
) : ProjectSettingsPage {
    @Autowired
    constructor(settings: JavaProjectSettings, spec: JdtlsSpec) : this(
        settings,
        sharedHomes,
        Path.of(System.getProperty("user.home")),
        System.getProperty("os.name").lowercase().contains("mac"),
        Executor { NamedThreads.virtual("java-project-settings", it) },
        Platform::runLater,
        ::chooseFolder,
        WorkspaceCleaner(spec::workspaceRuns, spec::cleanRunning, spec::cleanStopped),
        ::confirmClean,
    )

    override val path = listOf("Java")

    internal val model =
        JavaProjectSettingsPageModel(
            settings,
            homes,
            userHome,
            macOs,
            Path.of(System.getProperty("java.home")),
            io = io,
            dispatch = dispatch,
            cleaner = cleaner,
            confirmClean = { question -> confirmer(node.scene?.window, question) },
        )

    /** The JDK field, created with the node on the FX thread; for tests. */
    internal val combo: ComboBox<JdkChoice> by lazy { jdkCombo() }

    override val node: Node by lazy { content() }

    override fun load(folder: Path) = model.load(folder)

    override fun apply(folder: Path) = model.apply(folder)

    private fun jdkCombo(): ComboBox<JdkChoice> =
        ComboBox(model.choices).apply {
            id = "java-project-jdk"
            promptText = "Looking for JDKs…"
            maxWidth = Double.MAX_VALUE
            converter =
                object : StringConverter<JdkChoice>() {
                    override fun toString(value: JdkChoice?) = value?.text ?: ""

                    override fun fromString(text: String?): JdkChoice? = null
                }
            disableProperty().bind(model.loading)
            // Paints the loading text itself: the prompt text of a non-editable combo box is unreliable after a reset.
            buttonCell =
                object : ListCell<JdkChoice>() {
                    init {
                        model.loading.subscribe { _ -> updateItem(item, isEmpty) }
                    }

                    override fun updateItem(
                        item: JdkChoice?,
                        empty: Boolean,
                    ) {
                        super.updateItem(item, empty)
                        text =
                            when {
                                item != null -> item.text
                                model.loading.get() -> "Looking for JDKs…"
                                else -> null
                            }
                    }
                }
            model.selected.subscribe { choice -> if (value != choice) value = choice }
            valueProperty().subscribe { choice ->
                if (model.selected.get() == choice) return@subscribe
                model.selected.set(choice)
                if (choice?.other == true) afterPopup { model.chooseOther { chooser(scene?.window) } }
            }
        }

    /**
     * Runs [action] after the current event, with the popup hidden. A list cell selects on mouse press, so a modal
     * chooser opened inside that event would open while the popup still shows and the button is held.
     */
    private fun ComboBox<*>.afterPopup(action: () -> Unit) {
        if (isShowing) hide()
        Platform.runLater(action)
    }

    private fun content(): Node {
        val error =
            Label().apply {
                id = "java-project-jdk-error"
                isWrapText = true
                style = "-fx-font-weight: bold;" // no colour: follows both themes
                textProperty().bind(model.error)
                visibleProperty().bind(model.error.isNotEmpty)
                managedProperty().bind(visibleProperty())
            }
        val note =
            Label(
                "Runs this project's Gradle imports and is the JDK its builds compile against. Applies to the " +
                    "running Java language server at once.",
            ).apply {
                styleClass += "preview-caption"
                isWrapText = true
            }
        return VBox(
            10.0,
            Label("JDK").apply { styleClass += "label-heading" },
            combo,
            error,
            note,
            Label("Java workspace").apply { styleClass += "label-heading" },
            cleanButton,
            cleanNote(),
            cleanResult(),
        )
    }

    /** The Clean Java Workspace button, created with the node on the FX thread; for tests. */
    internal val cleanButton: Button by lazy {
        Button("Clean Java Workspace…").apply {
            id = "java-project-clean"
            setOnAction { model.clean() }
        }
    }

    private fun cleanNote() =
        Label(
            "Deletes jdtls's index and imported project data of this project; jdtls restarts and imports the " +
                "open builds again.",
        ).apply {
            styleClass += "preview-caption"
            isWrapText = true
        }

    private fun cleanResult() =
        Label().apply {
            id = "java-project-clean-result"
            isWrapText = true
            textProperty().bind(model.cleanMessage)
            visibleProperty().bind(model.cleanMessage.isNotEmpty)
            managedProperty().bind(visibleProperty())
        }
}
