package dev.sluide.docker.tab

import dev.sluide.docker.model.Container

/**
 * The container actions of the Docker tab (header buttons and context menu): which are enabled, and what each does.
 * Kill and Remove ask first; Shell goes to [openShell]; the others run at once. FX thread only.
 */
internal class ContainerActions(
    private val model: DockerTabModel,
    private val dialogs: DockerDialogs,
    private val openShell: (id: String, name: String) -> Unit,
) {
    /** Whether [command] can be used on [container] now: offered for its state and not already running. */
    fun enabled(
        command: ContainerCommand,
        container: Container,
    ): Boolean =
        command in availableCommands(container.state) && actionKey(container.id, command.name) !in model.running

    /** Runs [command] on [container], asking first where it is destructive. */
    fun perform(
        command: ContainerCommand,
        container: Container,
    ) {
        val key = actionKey(container.id, command.name)
        when (command) {
            ContainerCommand.SHELL -> {
                openShell(container.id, container.name)
            }

            ContainerCommand.REMOVE -> {
                val force = removalForced(container.state)
                val volumes = dialogs.removeContainer(container.name, container.state) ?: return
                model.run("remove", key) { cli -> cli.removeContainer(container.id, volumes, force) }
            }

            ContainerCommand.KILL -> {
                if (!dialogs.confirm("Kill Container", killMessage(container.name), "Kill")) return
                model.run("kill", key) { cli -> cli.act(checkNotNull(command.action), container.id) }
            }

            else -> {
                val action = checkNotNull(command.action)
                model.run(action.command, key) { cli -> cli.act(action, container.id) }
            }
        }
    }
}
