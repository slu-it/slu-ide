package dev.sluide.docker.dockerfile

import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.control.Tooltip
import javafx.scene.layout.ColumnConstraints
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import java.nio.file.Path

/**
 * The options of the Build pane: Tags (with the file's history), Context (with Choose…), Target (the file's stages),
 * the Build args of the file's ARGs, the flags and the Platform. Changes go to the model's options at once (it stores
 * them); the model's options and the file's stages and ARGs come back into the controls. FX thread.
 */
internal class BuildForm {
    val tags =
        ComboBox<String>().apply {
            styleClass += "docker-build-tags"
            isEditable = true
            maxWidth = Double.MAX_VALUE
            editor.promptText = "myapp:latest, myapp:1.0"
            tooltip = Tooltip("Tags (-t), separated by spaces or commas; the list holds earlier ones")
        }

    val context =
        TextField().apply {
            styleClass += "docker-build-context"
            HBox.setHgrow(this, Priority.ALWAYS)
            tooltip = Tooltip("The build context folder; empty: the Dockerfile's folder")
        }

    val chooseContext = Button("Choose…").apply { minWidth = Region.USE_PREF_SIZE }

    val target =
        ComboBox<String>().apply {
            styleClass += "docker-build-target"
            maxWidth = Double.MAX_VALUE
        }

    private val argsBox = VBox().apply { styleClass += "docker-build-args" }

    private val argsPlaceholder =
        Label("The file has no ARG instructions.").apply { styleClass += "docker-build-args-placeholder" }

    /** The value fields of the build args by name, in file order. */
    val argFields = LinkedHashMap<String, TextField>()

    val noCache = flag("--no-cache", "Build without the layer cache")
    val pull = flag("--pull", "Pull newer versions of the base images")

    val platform =
        ComboBox<String>().apply {
            styleClass += "docker-build-platform"
            isEditable = true
            items.setAll("", "linux/amd64", "linux/arm64")
            editor.promptText = "default"
            tooltip = Tooltip("--platform (os/arch[/variant]); empty: the engine's")
        }

    val node: Node =
        GridPane().apply {
            styleClass += "docker-build-form"
            columnConstraints +=
                listOf(
                    ColumnConstraints().apply { minWidth = Region.USE_PREF_SIZE },
                    ColumnConstraints().apply { hgrow = Priority.ALWAYS },
                )
            addRow(0, label("Tags:"), tags)
            addRow(1, label("Context:"), HBox(context, chooseContext).apply { styleClass += "docker-build-row" })
            addRow(2, label("Target:"), target)
            addRow(3, label("Build args:").apply { alignment = Pos.TOP_LEFT }, argsBox)
            addRow(4, label("Flags:"), HBox(noCache, pull).apply { styleClass += "docker-build-row" })
            addRow(5, label("Platform:"), platform)
        }

    /** Asks for a context folder, starting in the given one (a chooser owned by the window); tests replace it. */
    var chooseFolder: (initial: Path) -> Path? = { null }

    /** While the controls are filled from the model, their listeners do not write back. */
    private var filling = false

    /** Connects the form to [model], whose file is in [folder]. */
    fun bind(
        model: BuildPaneModel,
        folder: () -> Path,
    ) {
        model.options.subscribe { options -> fill(model, options) }
        model.info.subscribe { _ -> fill(model, model.options.get()) }
        tags.editor.textProperty().subscribe { text -> write(model) { it.copy(tags = text) } }
        context.textProperty().subscribe { text -> write(model) { it.copy(context = text.trim()) } }
        platform.editor.textProperty().subscribe { text -> write(model) { it.copy(platform = text.trim()) } }
        target.valueProperty().subscribe { value ->
            if (value != null) write(model) { it.copy(target = if (value == LAST_STAGE) "" else value) }
        }
        noCache.selectedProperty().subscribe { value -> write(model) { it.copy(noCache = value) } }
        pull.selectedProperty().subscribe { value -> write(model) { it.copy(pull = value) } }
        chooseContext.setOnAction {
            val current = runCatching { folder().resolve(model.options.get().context).normalize() }.getOrNull()
            val chosen = chooseFolder(current?.takeIf { it.toFile().isDirectory } ?: folder()) ?: return@setOnAction
            context.text = contextEntry(folder(), chosen)
        }
        showFolder(folder())
    }

    /** The prompt of the empty context field: the name of the Dockerfile's folder, e.g. after a move. */
    fun showFolder(folder: Path) {
        context.promptText = folder.fileName?.toString() ?: folder.toString()
    }

    private fun write(
        model: BuildPaneModel,
        transform: (BuildOptions) -> BuildOptions,
    ) {
        if (!filling) model.update(transform)
    }

    private fun fill(
        model: BuildPaneModel,
        options: BuildOptions,
    ) {
        filling = true
        try {
            if (tags.items != options.history) tags.items.setAll(options.history)
            if (tags.editor.text != options.tags) tags.editor.text = options.tags
            if (context.text.trim() != options.context) context.text = options.context
            if (platform.editor.text.trim() != options.platform) platform.editor.text = options.platform
            noCache.isSelected = options.noCache
            pull.isSelected = options.pull
            fillTarget(options.target, model.info.get().stages)
            fillArgs(model, options, model.info.get().args)
        } finally {
            filling = false
        }
    }

    private fun fillTarget(
        chosen: String,
        stages: List<String>,
    ) {
        val missing = isMissingTarget(chosen, stages)
        val items = listOf(LAST_STAGE) + stages + listOfNotNull(chosen.takeIf { missing })
        if (target.items != items) {
            // Replaced items keep the selected index, and the combo would go on showing the stage now at that place.
            target.value = null
            target.items.setAll(items)
        }
        target.value = chosen.ifEmpty { LAST_STAGE }.let { value -> items.firstOrNull { it.equals(value, true) } }
        target.styleClass.remove(MISSING)
        if (missing) {
            target.styleClass += MISSING
            target.tooltip = Tooltip("Stage \"$chosen\" is no longer in the file; the build passes it anyway")
        } else {
            target.tooltip = Tooltip("The stage to build (--target)")
        }
    }

    private fun fillArgs(
        model: BuildPaneModel,
        options: BuildOptions,
        args: List<BuildArg>,
    ) {
        if (argFields.keys.toList() != args.map { it.name }) {
            argFields.clear()
            argsBox.children.setAll(args.map { arg -> argRow(model, arg) })
            if (args.isEmpty()) argsBox.children.setAll(argsPlaceholder)
        }
        for (arg in args) {
            val field = argFields.getValue(arg.name)
            field.promptText = arg.default ?: ""
            val value = options.buildArgs[arg.name].orEmpty()
            if (field.text != value) field.text = value
        }
    }

    private fun argRow(
        model: BuildPaneModel,
        arg: BuildArg,
    ): Node {
        val field =
            TextField().apply {
                styleClass += "docker-build-arg-value"
                HBox.setHgrow(this, Priority.ALWAYS)
                textProperty().subscribe { text ->
                    write(model) { it.copy(buildArgs = withArg(it.buildArgs, arg.name, text)) }
                }
            }
        argFields[arg.name] = field
        return HBox(
            Label(arg.name).apply {
                styleClass += "docker-build-arg-name"
                minWidth = ARG_NAME_WIDTH
                prefWidth = ARG_NAME_WIDTH
            },
            field,
        ).apply {
            styleClass += "docker-build-arg-row"
            alignment = Pos.CENTER_LEFT
        }
    }

    private fun label(text: String) = Label(text).apply { minWidth = Region.USE_PREF_SIZE }

    private fun flag(
        text: String,
        tooltip: String,
    ) = CheckBox(text).apply {
        minWidth = Region.USE_PREF_SIZE
        this.tooltip = Tooltip(tooltip)
    }

    companion object {
        /** The first item of the Target combo: no `--target`. */
        const val LAST_STAGE = "(last stage)"
        private const val MISSING = "docker-build-missing"
        private const val ARG_NAME_WIDTH = 140.0
    }
}
