package dev.sluide.docker.dockerfile

import kotlin.test.Test
import kotlin.test.assertEquals

/** What the build job adds to [dev.sluide.commandruns.RunJob]'s texts (those are RunTextsTest's): its task row. */
class BuildJobTest {
    @Test
    fun `the task row names the build and the file`() {
        assertEquals("Build: build myapp:1.0 (Dockerfile)", BuildJob("build myapp:1.0", "Dockerfile").backgroundName)
        assertEquals(
            "Build: build Dockerfile (app.Dockerfile)",
            BuildJob("build Dockerfile", "app.Dockerfile").backgroundName,
        )
    }
}
