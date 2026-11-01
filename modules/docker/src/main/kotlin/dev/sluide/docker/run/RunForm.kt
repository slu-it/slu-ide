package dev.sluide.docker.run

import javafx.beans.property.SimpleBooleanProperty
import javafx.geometry.Insets
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import java.nio.file.Path

private const val DEFAULT_NETWORK = "(default)"

/**
 * The form of the Run Container dialog: the fields of [initial], a command preview that follows them and shows the
 * message of invalid input in its place (then [valid] is false), and Copy. It only binds controls to a [RunOptions]
 * and asks the pure functions of DockerRunCommand.kt; [executable] is how the preview names the CLI, [projectFolder]
 * and [home] resolve `./` and `~/` volumes.
 */
internal class RunForm(
    initial: RunOptions,
    networks: List<String>,
    volumeNames: List<String>,
    private val executable: String,
    private val projectFolder: Path?,
    private val home: Path,
) {
    /** Whether the input can be run; false while the preview shows a message. */
    val valid = SimpleBooleanProperty(true)

    private val image = TextField(initial.image).apply { isEditable = false }
    val name = TextField(initial.name).apply { promptText = "optional" }
    val network =
        ComboBox<String>().apply {
            items.setAll(listOf(DEFAULT_NETWORK) + (networks + listOfNotNull(initial.network)).distinct())
            value = initial.network ?: DEFAULT_NETWORK
            maxWidth = Double.MAX_VALUE
        }
    val command = TextField(initial.command).apply { promptText = "optional: the arguments after the image" }
    val detached = CheckBox("Detached (run in the background)").apply { isSelected = initial.detached }
    val removeWhenStopped = CheckBox("Remove when stopped (--rm)").apply { isSelected = initial.removeWhenStopped }

    val preview =
        TextArea().apply {
            id = "run-preview"
            isEditable = false
            isWrapText = true
            prefRowCount = 3
        }
    val problem =
        Label().apply {
            id = "run-problem"
            isWrapText = true
            styleClass += "docker-run-error"
        }
    val copy =
        Button("Copy").apply {
            id = "run-copy"
            setOnAction {
                Clipboard.getSystemClipboard().setContent(
                    ClipboardContent().apply { putString(copyText(executable, options(), projectFolder, home)) },
                )
            }
        }

    private val rows = RunRows(volumeNames)
    val ports = RowsEditor("Add a port", ::update, rows::port)
    val environment = RowsEditor("Add a variable", ::update, rows::variable)
    val volumes = RowsEditor("Add a volume", ::update, rows::volume)

    private var built = false

    /** The scrollable form; add it to the dialog. */
    val node: ScrollPane

    init {
        listOf(name.textProperty(), command.textProperty(), network.valueProperty()).forEach {
            it.addListener { _, _, _ -> update() }
        }
        listOf(detached, removeWhenStopped).forEach { it.selectedProperty().addListener { _, _, _ -> update() } }
        val grid =
            GridPane().apply {
                hgap = 8.0
                vgap = 8.0
                var row = 0

                fun line(
                    label: String,
                    field: javafx.scene.Node,
                ) {
                    // Labels keep their width; a wide row (a volume) shrinks its fields instead.
                    val title =
                        Label(label).apply {
                            minWidth = Region.USE_PREF_SIZE
                            GridPane.setValignment(this, javafx.geometry.VPos.TOP)
                        }
                    add(title, 0, row)
                    add(field, 1, row++)
                    GridPane.setHgrow(field, Priority.ALWAYS)
                }
                line("Image", image)
                line("Name", name)
                line("Ports", ports.node)
                line("Environment", environment.node)
                line("Volumes", this@RunForm.volumes.node)
                line("Network", network)
                line("Command", command)
                line("", VBox(4.0, detached, removeWhenStopped))
            }
        val previewBox =
            VBox(
                4.0,
                Label("Command"),
                VBox(preview, problem),
                HBox(
                    8.0,
                    copy,
                    Label("Variable values are hidden here; Copy includes them.").apply {
                        styleClass += "docker-row-detail"
                    },
                ).apply { alignment = javafx.geometry.Pos.CENTER_LEFT },
            )
        node =
            ScrollPane(VBox(12.0, grid, previewBox).apply { padding = Insets(10.0) }).apply {
                isFitToWidth = true
                prefViewportWidth = 640.0
                prefViewportHeight = 520.0
                styleClass += "docker-run-form"
                stylesheets += checkNotNull(RunForm::class.java.getResource("run-form.css")).toExternalForm()
            }
        built = true
        ports.setAll(initial.ports)
        environment.setAll(initial.environment)
        this.volumes.setAll(initial.volumes)
    }

    /** What the fields hold now. */
    fun options() =
        RunOptions(
            image = image.text,
            name = name.text,
            ports = ports.values(),
            environment = environment.values(),
            volumes = volumes.values(),
            network = network.value?.takeIf { it != DEFAULT_NETWORK },
            command = command.text,
            detached = detached.isSelected,
            removeWhenStopped = removeWhenStopped.isSelected,
        )

    private fun update() {
        if (!built) return
        val options = options()
        val message = validate(options)
        valid.set(message == null)
        copy.isDisable = message != null
        problem.text = message.orEmpty()
        problem.isVisible = message != null
        problem.isManaged = message != null
        preview.isVisible = message == null
        preview.isManaged = message == null
        if (message == null) preview.text = previewText(executable, options, projectFolder, home)
    }
}
