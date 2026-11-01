package dev.sluide.docker.compose

import dev.sluide.docker.EngineState
import dev.sluide.docker.model.ContainerState
import java.nio.file.Path
import kotlin.io.path.name

/*
 * The decisions of the Compose pane, without controls (ADR 0084): what is enabled, the notes and the questions.
 */

/** What the pane knows of the project's resolved configuration. */
internal sealed interface ConfigLoad {
    /** Not asked yet: the engine is not available (yet). */
    data object None : ConfigLoad

    /** `docker compose config` runs for the first time (later loads keep showing the last result). */
    data object Loading : ConfigLoad

    data class Loaded(
        val config: ComposeConfig,
    ) : ConfigLoad

    /** The file could not be resolved; [message] is compose's error (invalid YAML, a failed validation). */
    data class Failed(
        val message: String,
    ) : ConfigLoad
}

/** The actions of a service's row. */
internal enum class ServiceAction(
    val title: String,
) {
    UP("Up"),
    STOP("Stop"),
    RESTART("Restart"),
    LOGS("Logs"),
    SHELL("Shell"),
}

/** The answer to the question about unsaved changes before an Up. */
internal enum class UnsavedChoice { SAVE_AND_UP, UP_SAVED, CANCEL }

/** Whether the toolbar's commands can run: the engine answers and the file's configuration was read. */
internal fun commandsEnabled(
    engine: EngineState,
    config: ConfigLoad,
): Boolean = engine is EngineState.Available && config is ConfigLoad.Loaded

/** Why the commands are disabled because of the engine, or `null` while it answers. */
internal fun engineNote(engine: EngineState): String? =
    when (engine) {
        is EngineState.Available -> null
        EngineState.Idle, EngineState.Checking -> "Connecting to Docker…"
        is EngineState.Unavailable -> "Docker is not reachable: ${engine.message}"
        is EngineState.NotFound -> engine.message
    }

/** The note above the options: why the commands are disabled, or that an Up waits for the configuration. */
internal fun toolbarNote(
    engine: EngineState,
    waitingUp: Boolean,
): String? = engineNote(engine) ?: "Up waits for the configuration…".takeIf { waitingUp }

/** What the services area shows instead of the services, or `null` for the services. */
internal fun configNote(config: ConfigLoad): String? =
    when (config) {
        ConfigLoad.None -> null
        ConfigLoad.Loading -> "Reading the compose file…"
        is ConfigLoad.Loaded -> if (config.config.services.isEmpty()) "The file defines no services." else null
        is ConfigLoad.Failed -> "✖ The compose file cannot be used: ${config.message}"
    }

/** The note naming the override file compose gets besides the file, or `null` without one. */
internal fun overrideNote(override: Path?): String? = override?.let { "Also uses ${it.name}" }

/**
 * The actions that make sense for [service] while the commands are [enabled]: Up for the file's services (not for
 * orphans), Stop for running or restarting containers, Restart and Shell for running ones, Logs once a container
 * exists.
 */
internal fun serviceActions(
    service: ServiceState,
    enabled: Boolean,
): Set<ServiceAction> {
    if (!enabled) return emptySet()
    val states = service.containers.map { it.state }.toSet()
    return buildSet {
        if (!service.orphan) add(ServiceAction.UP)
        if (states.any { it in STOPPABLE }) add(ServiceAction.STOP)
        if (ContainerState.RUNNING in states) add(ServiceAction.RESTART)
        if (service.containers.isNotEmpty()) add(ServiceAction.LOGS)
        if (ContainerState.RUNNING in states) add(ServiceAction.SHELL)
    }
}

/** The states of containers that Stop stops. */
internal val STOPPABLE = setOf(ContainerState.RUNNING, ContainerState.RESTARTING)

/** Asked before an Up while the editor of [name] has unsaved changes. */
internal fun unsavedQuestion(name: String) = "$name has unsaved changes."

/** Asked before an Up while the configuration has unresolved variables. */
internal fun unresolvedQuestion(names: Collection<String>): String {
    val sorted = names.sorted()
    return if (sorted.size == 1) {
        "Variable ${sorted.single()} is not set and defaults to a blank string. Up anyway?"
    } else {
        "Variables ${sorted.joinToString(", ")} are not set and default to a blank string. Up anyway?"
    }
}

/** Asked before Down and Remove Volumes of [project]. */
internal fun downVolumesQuestion(project: String) =
    "Stop and remove the containers and networks of $project, and also its volumes with all their data?"

/** The warning line of unresolved variables, or `null` without any. */
internal fun unresolvedNote(names: Collection<String>): String? =
    names.takeIf { it.isNotEmpty() }?.sorted()?.let {
        "⚠ Not set, default to a blank string: ${it.joinToString(", ")}"
    }

/** The label of the profiles picker: the chosen ones, or that none is chosen (or that the file has none). */
internal fun profilesLabel(
    chosen: Set<String>,
    available: Set<String>,
): String =
    when {
        chosen.isNotEmpty() -> "Profiles: ${chosen.sorted().joinToString(", ")}"
        available.isEmpty() -> "No profiles"
        else -> "Profiles: none"
    }

/** What the project-name field holds: a name to use (`null` = compose's own), or text that is no valid name. */
internal sealed interface ProjectNameInput {
    data class Valid(
        val name: String?,
    ) : ProjectNameInput

    data object Invalid : ProjectNameInput
}

internal fun projectNameInput(text: String): ProjectNameInput {
    val name = text.trim()
    return when {
        name.isEmpty() -> ProjectNameInput.Valid(null)
        isValidProjectName(name) -> ProjectNameInput.Valid(name)
        else -> ProjectNameInput.Invalid
    }
}

/** The variables of the Environment table's rows (name, value) to keep: those with a valid name. */
internal fun variablesOf(rows: List<Pair<String, String>>): List<ComposeVariable> =
    rows.filter { isValidVariableName(it.first) }.map { ComposeVariable(it.first, it.second) }

/** How the env files list reads while it is empty: the default `.env` ([envFiles] `null`), or none at all. */
internal fun envFilesPlaceholder(
    envFiles: List<String>?,
    defaultExists: Boolean,
): String =
    when {
        envFiles != null -> "No env files: none is read."
        defaultExists -> "Uses .env (default)"
        else -> "Uses .env (default; there is none beside the file)"
    }

/** The entry of the env file [chosen] in the list: relative to the compose file's [folder] if inside it. */
internal fun envFileEntry(
    folder: Path,
    chosen: Path,
): String {
    val base = folder.toAbsolutePath().normalize()
    val path = chosen.toAbsolutePath().normalize()
    return if (path.startsWith(base)) base.relativize(path).toString() else path.toString()
}

/**
 * The env files after adding [entry] to [envFiles]: the first one added to the default keeps the `.env` it replaces
 * when it exists ([defaultExists]), as compose reads only the given files then. An entry already listed is not added
 * again.
 */
internal fun withEnvFile(
    envFiles: List<String>?,
    entry: String,
    defaultExists: Boolean,
): List<String> {
    val current = envFiles ?: if (defaultExists) listOf(".env") else emptyList()
    return if (entry in current) current else current + entry
}
