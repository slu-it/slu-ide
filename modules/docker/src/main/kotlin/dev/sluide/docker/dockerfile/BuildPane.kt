package dev.sluide.docker.dockerfile

import dev.sluide.commandruns.RunPane
import javafx.geometry.Orientation
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.SplitPane
import javafx.scene.control.TextArea
import javafx.scene.control.Tooltip
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import java.nio.file.Path

/**
 * The Build side of a Dockerfile's view: the toolbar with Build and Run… and the note why Build is disabled or
 * waits, the [form], the read-only preview of the command line (or the problem with the options) and below them the
 * Output ([runPane], without an arguments row). Disabled until [bind] connects it to the view's model. Styled by the
 * view's `dockerfile-view.css`. FX thread.
 */
internal class BuildPane {
    val build = button("Build", "docker-build-build")

    val runImage = button("Run…", "docker-build-run")

    /** What Run… runs or why it is disabled; on a box around it, as a disabled button shows no tooltip. */
    val runImageHint = Tooltip(runImageTooltip(null, false))

    private val runImageBox = StackPane(runImage).apply { Tooltip.install(this, runImageHint) }

    /** Why Build is disabled because of the engine; hidden while it answers. */
    val note =
        Label(NOT_IN_WINDOW).apply {
            styleClass += "docker-build-note"
            isWrapText = true
        }

    val form = BuildForm()

    /** The command line Build runs, selectable and wrapped, or the problem with the options. */
    val preview =
        TextArea().apply {
            styleClass += "docker-build-preview"
            isEditable = false
            isWrapText = true
            prefRowCount = PREVIEW_ROWS
            maxWidth = Double.MAX_VALUE
        }

    val runPane = RunPane("", "The output of the build shows here.", showsArguments = false)

    private val details =
        ScrollPane(
            VBox(form.node, preview).apply { styleClass += "docker-build-details-box" },
        ).apply {
            styleClass += "docker-build-details"
            isFitToWidth = true
            minHeight = 0.0
            VBox.setVgrow(this, Priority.ALWAYS)
        }

    private val top =
        VBox(
            HBox(build, runImageBox).apply {
                styleClass += "docker-build-toolbar"
                alignment = Pos.CENTER_LEFT
            },
            note,
            details,
        ).apply {
            styleClass += "docker-build-top"
            minHeight = 0.0
            isDisable = true
        }

    val node =
        SplitPane(top, runPane.node).apply {
            styleClass += "docker-build-pane"
            orientation = Orientation.VERTICAL
            setDividerPositions(DIVIDER)
            minWidth = 0.0
        }

    /** Connects every part to [model], whose file is in [folder]. */
    fun bind(
        model: BuildPaneModel,
        folder: () -> Path,
    ) {
        top.isDisable = false
        build.setOnAction { model.build() }
        runImage.setOnAction { model.runImage() }
        form.bind(model, folder)
        model.enabled.subscribe { enabled -> build.isDisable = !enabled }
        model.runImageEnabled.subscribe { enabled -> runImage.isDisable = !enabled }
        model.builtTag.subscribe { _ -> showRunTooltip(model) }
        model.running.subscribe { _ -> showRunTooltip(model) }
        model.engine.subscribe { _ -> showNote(model) }
        model.waitingBuild.subscribe { _ -> showNote(model) }
        model.preview.subscribe { value ->
            preview.styleClass.remove(PROBLEM)
            when (value) {
                is BuildPreview.Command -> {
                    preview.text = value.line
                }

                is BuildPreview.Problem -> {
                    preview.text = "✖ ${value.message}"
                    preview.styleClass += PROBLEM
                }
            }
        }
    }

    private fun showNote(model: BuildPaneModel) {
        val text = buildNote(model.engine.get(), model.waitingBuild.get())
        note.text = text ?: ""
        note.isVisible = text != null
        note.isManaged = text != null
    }

    private fun showRunTooltip(model: BuildPaneModel) {
        runImageHint.text = runImageTooltip(model.builtTag.get(), model.running.get())
    }

    private fun button(
        text: String,
        styleClass: String,
    ) = Button(text).apply {
        this.styleClass += styleClass
        minWidth = Region.USE_PREF_SIZE
        isDisable = true
    }

    private companion object {
        const val NOT_IN_WINDOW = "Builds run in an IDE window."
        const val PROBLEM = "docker-build-problem"
        const val DIVIDER = 0.6
        const val PREVIEW_ROWS = 3
    }
}
