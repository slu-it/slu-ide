package dev.sluide.docker.compose

import dev.sluide.docker.model.ContainerState
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.Tooltip
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox

/**
 * The services of the Compose pane (ADR 0119), one row each in file order, orphans last: a state dot, the name, the
 * summary (`running`, `2/3 running`, `not created`), the ports, the profiles (dimmed) and the buttons Up, Stop,
 * Restart, Logs and Shell, enabled per [serviceActions]. Instead of the rows it shows the [configNote] (reading,
 * failed). The line below shows the failure of the last row action. FX thread.
 */
internal class ServiceList {
    private val rows = VBox().apply { styleClass += "compose-services" }

    /** Reading, failed, or no services; hidden while there are rows. */
    val note =
        Label().apply {
            styleClass += "compose-services-note"
            isWrapText = true
            isVisible = false
            isManaged = false
        }

    /** The override note, e.g. "Also uses compose.override.yaml". */
    val overrideNote =
        Label().apply {
            styleClass += "compose-override-note"
            isVisible = false
            isManaged = false
        }

    /** The failure of the last Stop or Restart. */
    val error =
        Label().apply {
            styleClass += "compose-error"
            isWrapText = true
            isVisible = false
            isManaged = false
        }

    /** Scrolls with the Environment section below it (ComposePane), so neither squeezes the other. */
    val node = VBox(overrideNote, note, rows, error).apply { styleClass += "compose-services-box" }

    /** Connects the list to [model]: rebuilt when the services, the configuration or the enablement change. */
    fun bind(model: ComposePaneModel) {
        model.services.subscribe { _ -> show(model) }
        model.enabled.subscribe { _ -> show(model) }
        model.config.subscribe { _ -> show(model) }
        model.override.subscribe { override -> shown(overrideNote, overrideNote(override)) }
        model.error.subscribe { error -> shown(this.error, error) }
    }

    private fun show(model: ComposePaneModel) {
        val config = model.config.get()
        shown(note, configNote(config))
        val services = if (config is ConfigLoad.Loaded) model.services.get() else emptyList()
        val enabled = model.enabled.get()
        rows.children.setAll(services.map { row(it, serviceActions(it, enabled), model) })
    }

    private fun row(
        service: ServiceState,
        actions: Set<ServiceAction>,
        model: ComposePaneModel,
    ): HBox {
        val dot = Region().apply { styleClass += listOf("docker-state-dot", dotClass(service)) }
        val name =
            Label(if (service.orphan) "${service.name} (orphan)" else service.name).apply {
                styleClass += "compose-service-name"
                minWidth = Region.USE_PREF_SIZE
            }
        val summary = Label(service.summary).apply { styleClass += "compose-service-summary" }
        val ports =
            Label(
                service.service
                    ?.ports
                    .orEmpty()
                    .joinToString(", "),
            ).apply {
                styleClass += "compose-service-ports"
                minWidth = 0.0
            }
        val profiles =
            Label(
                service.service
                    ?.profiles
                    .orEmpty()
                    .sorted()
                    .joinToString(", "),
            ).apply {
                styleClass += "compose-service-profiles"
                minWidth = 0.0
            }
        val spacer = Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }
        val buttons =
            ServiceAction.entries.map { action ->
                Button(action.title).apply {
                    styleClass += listOf("compose-service-action", "compose-service-${action.name.lowercase()}")
                    minWidth = Region.USE_PREF_SIZE
                    isDisable = action !in actions
                    setOnAction { model.perform(service, action) }
                }
            }
        return HBox(dot, name, summary, ports, profiles, spacer, *buttons.toTypedArray()).apply {
            styleClass += "compose-service-row"
            alignment = Pos.CENTER_LEFT
            userData = service.name
            (service.service?.image ?: service.containers.firstOrNull()?.image)?.let {
                Tooltip.install(
                    this,
                    Tooltip(it),
                )
            }
        }
    }

    private fun dotClass(service: ServiceState): String {
        val states = service.containers.map { it.state }
        return when {
            ContainerState.RUNNING in states -> "docker-state-running"
            ContainerState.RESTARTING in states -> "docker-state-restarting"
            ContainerState.PAUSED in states -> "docker-state-paused"
            else -> "docker-state-stopped"
        }
    }

    private fun shown(
        label: Label,
        text: String?,
    ) {
        label.text = text ?: ""
        label.isVisible = text != null
        label.isManaged = text != null
    }
}
