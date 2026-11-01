package dev.sluide.docker.tab

import dev.sluide.docker.cli.shellQuoted
import dev.sluide.docker.model.ContainerAction
import dev.sluide.docker.model.ContainerState

/** What the Docker tab offers for a container: its header buttons and the items of its row's context menu. */
internal enum class ContainerCommand(
    val title: String,
    /** The lifecycle action it runs, `null` for those with a flow of their own. */
    val action: ContainerAction?,
) {
    START("Start", ContainerAction.START),
    STOP("Stop", ContainerAction.STOP),
    RESTART("Restart", ContainerAction.RESTART),
    PAUSE("Pause", ContainerAction.PAUSE),
    UNPAUSE("Unpause", ContainerAction.UNPAUSE),
    KILL("Kill", ContainerAction.KILL),
    REMOVE("Remove", null),
    SHELL("Shell", null),
}

/**
 * The commands that make sense for a container in [state]: Start for created, exited and dead ones; Stop, Restart,
 * Pause, Kill and Shell for running ones; Unpause for paused ones; Stop and Kill for restarting ones; Remove always
 * (running ones with force, after the confirmation says so).
 */
internal fun availableCommands(state: ContainerState): Set<ContainerCommand> =
    when (state) {
        ContainerState.CREATED, ContainerState.EXITED, ContainerState.DEAD -> {
            setOf(ContainerCommand.START, ContainerCommand.REMOVE)
        }

        ContainerState.RUNNING -> {
            setOf(
                ContainerCommand.STOP,
                ContainerCommand.RESTART,
                ContainerCommand.PAUSE,
                ContainerCommand.KILL,
                ContainerCommand.SHELL,
                ContainerCommand.REMOVE,
            )
        }

        ContainerState.PAUSED -> {
            setOf(ContainerCommand.UNPAUSE, ContainerCommand.REMOVE)
        }

        ContainerState.RESTARTING -> {
            setOf(ContainerCommand.STOP, ContainerCommand.KILL, ContainerCommand.REMOVE)
        }

        ContainerState.REMOVING, ContainerState.UNKNOWN -> {
            setOf(ContainerCommand.REMOVE)
        }
    }

/** What the Shell action runs in the login shell: bash in the container if it has one, else sh. */
internal fun shellCommand(
    executable: String,
    containerId: String,
): String {
    val shell = shellQuoted("command -v bash >/dev/null 2>&1 && exec bash || exec sh")
    return "${shellQuoted(executable)} exec -it ${shellQuoted(containerId)} sh -c $shell"
}

/** The question before Kill. */
internal fun killMessage(name: String): String = "Kill $name? It gets no time to shut down cleanly (SIGKILL)."

/** Whether removing a container in [state] needs force: `docker rm` refuses running, restarting and paused ones. */
internal fun removalForced(state: ContainerState): Boolean =
    state == ContainerState.RUNNING || state == ContainerState.RESTARTING || state == ContainerState.PAUSED

/** The question before removing a container in [state]; it says when the container is stopped and removed by force. */
internal fun removeContainerMessage(
    name: String,
    state: ContainerState,
): String =
    when {
        state == ContainerState.PAUSED -> "$name is paused. Stop it and remove it?"
        removalForced(state) -> "$name is running. Stop it and remove it?"
        else -> "Remove the container $name?"
    }
