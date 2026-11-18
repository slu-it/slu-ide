package dev.sluide.docker.compose

import kotlin.test.Test
import kotlin.test.assertEquals

/** What the compose job adds to [dev.sluide.commandruns.RunJob]'s texts (those are RunTextsTest's): its task row. */
class ComposeJobTest {
    @Test
    fun `the task row names the command and the file`() {
        assertEquals("Compose: up web (compose.yaml)", ComposeJob("up web", "compose.yaml").backgroundName)
        assertEquals("Compose: down -v (my.yml)", ComposeJob("down -v", "my.yml").backgroundName)
    }
}
