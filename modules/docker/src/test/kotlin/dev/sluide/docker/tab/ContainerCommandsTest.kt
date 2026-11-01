package dev.sluide.docker.tab

import dev.sluide.docker.model.ContainerState
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals

class ContainerCommandsTest {
    @ParameterizedTest
    @CsvSource(
        "CREATED, START REMOVE",
        "EXITED, START REMOVE",
        "DEAD, START REMOVE",
        "RUNNING, STOP RESTART PAUSE KILL REMOVE SHELL",
        "PAUSED, UNPAUSE REMOVE",
        "RESTARTING, STOP KILL REMOVE",
        "REMOVING, REMOVE",
        "UNKNOWN, REMOVE",
    )
    fun `the commands offered follow the container's state, Remove always`(
        state: ContainerState,
        commands: String,
    ) {
        assertEquals(
            commands.split(" ").map(ContainerCommand::valueOf).toSet(),
            availableCommands(state),
        )
    }

    @Test
    fun `the shell command quotes the executable and the id for sh and prefers bash`() {
        assertEquals(
            "'/usr/bin/docker' exec -it 'abc123' sh -c 'command -v bash >/dev/null 2>&1 && exec bash || exec sh'",
            shellCommand("/usr/bin/docker", "abc123"),
        )
        assertEquals(
            "'/Applications/My Docker/it'\\''s/docker' exec -it 'x' sh -c " +
                "'command -v bash >/dev/null 2>&1 && exec bash || exec sh'",
            shellCommand("/Applications/My Docker/it's/docker", "x"),
        )
    }

    @Test
    fun `the remove question says when the container is running or paused and so removed by force`() {
        assertEquals(
            "web-1 is running. Stop it and remove it?",
            removeContainerMessage("web-1", ContainerState.RUNNING),
        )
        assertEquals(
            "web-1 is running. Stop it and remove it?",
            removeContainerMessage("web-1", ContainerState.RESTARTING),
        )
        assertEquals("web-1 is paused. Stop it and remove it?", removeContainerMessage("web-1", ContainerState.PAUSED))
        assertEquals("Remove the container web-1?", removeContainerMessage("web-1", ContainerState.EXITED))
        assertEquals(
            listOf(ContainerState.RUNNING, ContainerState.PAUSED, ContainerState.RESTARTING),
            ContainerState.entries.filter(::removalForced),
        )
    }
}
