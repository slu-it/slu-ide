package dev.sluide.docker.compose

import dev.sluide.docker.cli.ComposeConfigOutput
import dev.sluide.docker.model.ContainerState
import dev.sluide.docker.model.container
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServiceStateTest {
    private val config = parseComposeConfig(ComposeConfigOutput(composeFixture("config-all-profiles.json"), ""))

    @Test
    fun `without containers every service is not created, in the order of the config`() {
        val states = serviceStates(config, emptyList())

        assertEquals(listOf("app", "db", "debug", "web", "worker"), states.map { it.name })
        assertEquals(setOf("not created"), states.map { it.summary }.toSet())
        assertFalse(states.any { it.orphan || it.running })
    }

    @Test
    fun `the given order is the file order, unnamed services follow`() {
        val states = serviceStates(config, emptyList(), order = listOf("web", "gone", "db", "web"))

        assertEquals(listOf("web", "db", "app", "debug", "worker"), states.map { it.name })
    }

    @Test
    fun `containers are joined by project and service label`() {
        val web = container("demo-web-1", project = "demo", service = "web")
        val stopped = container("demo-db-1", project = "demo", service = "db", state = ContainerState.EXITED)
        val otherProject = container("other-web-1", project = "other", service = "web")
        val standalone = container("loner")

        val states = serviceStates(config, listOf(web, stopped, otherProject, standalone)).associateBy { it.name }

        assertEquals(listOf(web), states.getValue("web").containers)
        assertEquals("running", states.getValue("web").summary)
        assertTrue(states.getValue("web").running)
        assertEquals("exited", states.getValue("db").summary)
        assertFalse(states.getValue("db").running)
        assertEquals("not created", states.getValue("app").summary)
        assertEquals(5, states.size, "containers of other projects and standalone ones add nothing")
    }

    @Test
    fun `replicas are counted`() {
        val replicas =
            listOf(
                container("demo-web-2", project = "demo", service = "web", state = ContainerState.EXITED),
                container("demo-web-1", project = "demo", service = "web"),
                container("demo-web-3", project = "demo", service = "web"),
            )

        val web = serviceStates(config, replicas).single { it.name == "web" }

        assertEquals("2/3 running", web.summary)
        assertEquals(listOf("demo-web-1", "demo-web-2", "demo-web-3"), web.containers.map { it.name })
    }

    @Test
    fun `containers of services no longer in the file are orphans, last and by name`() {
        val old = container("demo-zeta-1", project = "demo", service = "zeta")
        val older = container("demo-alpha-1", project = "demo", service = "alpha", state = ContainerState.EXITED)

        val states = serviceStates(config, listOf(old, older))

        assertEquals(listOf("alpha", "zeta"), states.takeLast(2).map { it.name })
        assertTrue(states.takeLast(2).all { it.orphan && it.service == null })
        assertEquals("exited", states.single { it.name == "alpha" }.summary)
        assertFalse(states.first().orphan)
    }

    @Test
    fun `one-off containers of compose run are no replicas of their service`() {
        val web = container("demo-web-1", project = "demo", service = "web")
        val oneOff = container("demo-web-run-1a2b", project = "demo", service = "web", oneOff = true)
        val orphanRun = container("demo-gone-run-3c4d", project = "demo", service = "gone", oneOff = true)

        val states = serviceStates(config, listOf(web, oneOff, orphanRun))

        assertEquals(listOf(web), states.single { it.name == "web" }.containers)
        assertEquals("running", states.single { it.name == "web" }.summary)
        assertFalse(states.any { it.orphan }, "a one-off container does not make an orphan")
    }
}
