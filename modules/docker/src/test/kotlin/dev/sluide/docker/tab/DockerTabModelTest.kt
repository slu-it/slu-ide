package dev.sluide.docker.tab

import dev.sluide.docker.cli.DockerException
import dev.sluide.docker.cli.FakeDockerCli
import dev.sluide.docker.events.ManualScheduler
import dev.sluide.docker.model.Container
import dev.sluide.docker.model.ContainerAction
import dev.sluide.docker.model.ContainerDetails
import dev.sluide.docker.model.ContainerState
import dev.sluide.docker.model.DockerEvent
import dev.sluide.docker.model.container
import dev.sluide.docker.tab.tree.ContainerRow
import dev.sluide.docker.tab.tree.ResourceKey
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** [DockerTabModel] on a session over a [FakeDockerCli]; results of the actions' threads come back through [fx]. */
class DockerTabModelTest {
    private val cli = FakeDockerCli()
    private val scheduler = ManualScheduler()
    private val session = fakeSession(cli, scheduler)
    private val fx = FxQueue()
    private val web = container("web", project = "shop", service = "web", workingDir = "/p/shop")
    private val db = container("db")

    init {
        cli.containers = listOf(web, db)
        cli.details = mapOf(web.id to details(web.id, "nginx:1.27"), db.id to details(db.id, "postgres:17"))
    }

    private val model = DockerTabModel(session, fx)

    @AfterTest
    fun close() {
        model.close()
        session.close()
    }

    private fun details(
        id: String,
        image: String,
    ) = ContainerDetails(
        id,
        id,
        image,
        null,
        ContainerState.RUNNING,
        null,
        null,
        null,
        null,
        0,
        null,
        emptyList(),
        emptyList(),
        emptyList(),
        emptyMap(),
        emptyList(),
        emptyList(),
        emptyList(),
    )

    /** Replaces the containers and lets the session reload them as after an event. */
    private fun reload(vararg containers: Container) {
        cli.containers = containers.toList()
        cli.pushEvent(DockerEvent("container", "start", "x"))
        scheduler.advance(200.milliseconds)
    }

    @Test
    fun `opening subscribes to the session and builds the tree from its lists and the project folder`() {
        assertEquals(1, cli.count("events"))
        assertEquals(
            "Containers (2)",
            model.tree
                .get()
                .first()
                .row.label,
        )

        model.projectFolder.set(Path.of("/p"))

        assertEquals(
            listOf("shop", "db"),
            model.tree
                .get()
                .first()
                .children
                .map { it.row.label },
        )
        model.filter.set("db")
        assertEquals(
            listOf("db"),
            model.tree
                .get()
                .first()
                .children
                .map { it.row.label },
        )
    }

    @Test
    fun `the selection survives reloads by identity and is cleared when its resource is gone`() {
        model.select(ResourceKey.ContainerKey(db.id))
        val stopped = db.copy(state = ContainerState.EXITED, status = "Exited (0)")

        reload(web, stopped)

        assertEquals(ResourceKey.ContainerKey(db.id), model.selection.get())
        assertEquals(stopped, (model.selectedRow.get() as ContainerRow).container)

        reload(web)

        assertNull(model.selection.get())
        assertNull(model.selectedRow.get())
    }

    @Test
    fun `a container not listed yet is selected once it appears`() {
        val late = container("late")
        model.select(ResourceKey.ContainerKey(late.id))
        assertNull(model.selection.get())

        reload(web, db, late)

        assertEquals(ResourceKey.ContainerKey(late.id), model.selection.get())
    }

    @Test
    fun `selecting a container loads its details, Loading meanwhile, and drops a result for another selection`() {
        val webLoaded = CountDownLatch(1)
        cli.failing = { call ->
            if (call == "containerDetails ${web.id}") webLoaded.await(5, TimeUnit.SECONDS)
            null
        }
        model.select(ResourceKey.ContainerKey(web.id))
        assertEquals(DetailsLoad.Loading, model.containerDetails.get())
        model.select(ResourceKey.ContainerKey(db.id))

        fx.runUntil { model.containerDetails.get() is DetailsLoad.Loaded }
        webLoaded.countDown()
        // The two loads' results, the late one for the container no longer selected last.
        fx.runUntil { fx.ran == 2 }

        assertEquals("postgres:17", (model.containerDetails.get() as DetailsLoad.Loaded).details.image)
    }

    @Test
    fun `a changed list entry reloads the details of the selected container, keeping the old ones meanwhile`() {
        model.select(ResourceKey.ContainerKey(db.id))
        fx.runUntil { model.containerDetails.get() is DetailsLoad.Loaded }
        cli.details = mapOf(db.id to details(db.id, "postgres:18"))

        reload(web, db.copy(status = "Up 1 second"))

        assertIs<DetailsLoad.Loaded>(model.containerDetails.get())
        fx.runUntil { (model.containerDetails.get() as DetailsLoad.Loaded).details.image == "postgres:18" }
        assertEquals(2, cli.count("containerDetails ${db.id}"))
    }

    @Test
    fun `details that cannot be loaded say why`() {
        cli.details = emptyMap()

        model.select(ResourceKey.ContainerKey(db.id))

        fx.runUntil { model.containerDetails.get() is DetailsLoad.Failed }
        assertEquals("Error: No such container: ${db.id}", (model.containerDetails.get() as DetailsLoad.Failed).message)
    }

    @Test
    fun `an action's key is running until it ends, and its failure goes to the error bar`() {
        val release = CountDownLatch(1)
        cli.failing = { call ->
            if (call.startsWith("act")) {
                release.await(5, TimeUnit.SECONDS)
                DockerException("Error response from daemon: cannot stop")
            } else {
                null
            }
        }

        model.run("stop", "key") { it.act(ContainerAction.STOP, db.id) }

        assertTrue("key" in model.running)
        release.countDown()
        fx.runUntil { model.running.isEmpty() }
        assertEquals("Error response from daemon: cannot stop", model.error.get())
    }

    @Test
    fun `closing unsubscribes and stops following the lists`() {
        model.close()

        assertTrue(cli.eventStreams.single().closed)
        cli.containers = emptyList()
        session.refresh()
        assertEquals(
            "Containers (2)",
            model.tree
                .get()
                .first()
                .row.label,
        )
    }
}
