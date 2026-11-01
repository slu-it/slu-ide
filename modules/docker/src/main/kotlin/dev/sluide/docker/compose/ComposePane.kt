package dev.sluide.docker.compose

import dev.sluide.commandruns.RunPane
import javafx.geometry.Orientation
import javafx.scene.control.ScrollPane
import javafx.scene.control.SplitPane
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import java.nio.file.Path

/**
 * The Compose side of a compose file's view (ADR 0119): the [toolbar] with the options, the [services], the
 * [environment] and below them the Output ([runPane], without an arguments row). Disabled until [bind] connects it
 * to the view's model. Styled by the view's `compose-view.css`. FX thread.
 */
internal class ComposePane {
    val toolbar = ComposeToolbar()
    val services = ServiceList()
    val environment = EnvironmentSection()
    val runPane = RunPane("", "The output of the Compose commands shows here.", showsArguments = false)

    /** The services and the Environment section scroll together below the fixed toolbar. */
    private val details =
        ScrollPane(VBox(services.node, environment.node)).apply {
            styleClass += "compose-details"
            isFitToWidth = true
            minHeight = 0.0
            VBox.setVgrow(this, Priority.ALWAYS)
        }

    private val top =
        VBox(toolbar.node, details).apply {
            styleClass += "compose-pane-top"
            minHeight = 0.0
        }

    val node =
        SplitPane(top, runPane.node).apply {
            styleClass += "compose-pane"
            orientation = Orientation.VERTICAL
            setDividerPositions(DIVIDER)
            minWidth = 0.0
        }

    /** Connects every part to [model], whose file is in [folder]. */
    fun bind(
        model: ComposePaneModel,
        folder: () -> Path,
    ) {
        toolbar.bind(model)
        services.bind(model)
        environment.bind(model, folder)
    }

    private companion object {
        const val DIVIDER = 0.55
    }
}
