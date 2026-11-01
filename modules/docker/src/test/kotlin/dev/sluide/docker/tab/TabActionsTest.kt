package dev.sluide.docker.tab

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.docker.cli.DockerException
import dev.sluide.docker.cli.FakeDockerCli
import dev.sluide.docker.model.Container
import dev.sluide.docker.model.ContainerState
import dev.sluide.docker.model.ResourceKind
import dev.sluide.docker.model.container
import dev.sluide.docker.tab.tree.ContainerRow
import dev.sluide.docker.tab.tree.GroupRow
import dev.sluide.docker.tab.tree.NetworkRow
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The container, image, volume, network and group actions with fake dialogs, on a session over a [FakeDockerCli]. */
class TabActionsTest {
    /** Answers with the values the test sets and records every question. */
    private class FakeDialogs : DockerDialogs {
        val asked = mutableListOf<String>()
        var confirmAnswer = true
        var removeContainerAnswer: Boolean? = false
        var removeImageAnswer: Boolean? = false
        var pullAnswer: String? = "nginx:1.27"
        var volumeAnswer: String? = "cache"
        var networkAnswer: NewNetwork? = NewNetwork("backend", "overlay")

        override fun confirm(
            title: String,
            message: String,
            confirm: String,
        ) = confirmAnswer.also { asked += "$title: $message" }

        override fun confirmList(
            title: String,
            message: String,
            items: List<String>,
            confirm: String,
        ) = confirmAnswer.also { asked += "$title: $items" }

        override fun removeContainer(
            name: String,
            state: ContainerState,
        ) = removeContainerAnswer.also { asked += "remove $name $state" }

        override fun removeImage(
            reference: String,
            stoppedUsers: List<String>,
        ) = removeImageAnswer.also { asked += "remove $reference stopped=$stoppedUsers" }

        override fun askPull() = pullAnswer

        override fun askVolumeName() = volumeAnswer

        override fun askNetwork() = networkAnswer
    }

    private val cli = FakeDockerCli()
    private val session = fakeSession(cli)
    private val fx = FxQueue()
    private val dialogs = FakeDialogs()
    private val taskLog = CopyOnWriteArrayList<String>()
    private val tasks = BackgroundTasks.forTests { taskLog += it }
    private val shells = mutableListOf<String>()
    private val running = container("web")
    private val stopped = container("old", state = ContainerState.EXITED, image = "nginx:1.27")

    init {
        cli.containers = listOf(running, stopped)
        cli.images = listOf(image("nginx", "1.27"), image(null, null, id = "sha256:" + "d".repeat(64)))
        cli.volumes = listOf(volume("data", inUse = true), volume("orphan", inUse = false))
        cli.networks = listOf(network("bridge"), network("unused"))
    }

    private val model = DockerTabModel(session, fx)
    private val containerActions = ContainerActions(model, dialogs) { id, name -> shells += "$id $name" }
    private val resourceActions = ResourceActions(model, dialogs, tasks)
    private val actions = RowActions(containerActions, resourceActions)

    @AfterTest
    fun close() {
        model.close()
        session.close()
    }

    private fun containerAction(
        title: String,
        container: Container,
    ) = actions.of(ContainerRow(container)).single { it.title == title }

    private fun awaitCall(call: String) = fx.runUntil { call in cli.calls && model.running.isEmpty() }

    private fun awaitTask(line: String) = fx.runUntil { taskLog.any { line in it } }

    @Test
    fun `container buttons are enabled per state, and disabled while their action runs`() {
        assertEquals(
            listOf("Stop", "Restart", "Pause", "Kill", "Remove", "Shell"),
            actions.of(ContainerRow(running)).filter { it.enabled }.map { it.title },
        )
        assertEquals(
            listOf("Start", "Remove"),
            actions.of(ContainerRow(stopped)).filter { it.enabled }.map { it.title },
        )

        val release = CountDownLatch(1)
        cli.failing = { call ->
            if (call.startsWith("act")) release.await(5, TimeUnit.SECONDS)
            null
        }
        containerAction("Stop", running).run()

        assertFalse(containerAction("Stop", running).enabled)
        assertTrue(containerAction("Restart", running).enabled)
        release.countDown()
        awaitCall("act STOP ${running.id}")
        assertTrue(containerAction("Stop", running).enabled)
    }

    @Test
    fun `Start runs at once, Kill asks first and does nothing when declined`() {
        containerAction("Start", stopped).run()
        awaitCall("act START ${stopped.id}")

        dialogs.confirmAnswer = false
        containerAction("Kill", running).run()
        dialogs.confirmAnswer = true
        containerAction("Kill", running).run()

        awaitCall("act KILL ${running.id}")
        assertEquals(1, cli.count("act KILL ${running.id}"))
        assertEquals(List(2) { "Kill Container: ${killMessage("web")}" }, dialogs.asked)
    }

    @Test
    fun `Remove asks about volumes and forces running containers, Shell opens a shell`() {
        dialogs.removeContainerAnswer = true
        containerAction("Remove", running).run()
        awaitCall("removeContainer ${running.id} volumes=true force=true")

        dialogs.removeContainerAnswer = false
        containerAction("Remove", stopped).run()
        awaitCall("removeContainer ${stopped.id} volumes=false force=false")

        dialogs.removeContainerAnswer = null
        containerAction("Remove", stopped).run()
        assertEquals(
            listOf("remove web RUNNING", "remove old EXITED", "remove old EXITED"),
            dialogs.asked,
        )
        assertEquals(1, cli.count("removeContainer ${stopped.id} volumes=false force=false"))

        containerAction("Shell", running).run()
        assertEquals(listOf("${running.id} web"), shells)
    }

    @Test
    fun `a paused container is removed by force, as docker rm refuses it otherwise`() {
        val paused = container("napping", state = ContainerState.PAUSED)

        containerAction("Remove", paused).run()

        awaitCall("removeContainer ${paused.id} volumes=false force=true")
        assertEquals(listOf("remove napping PAUSED"), dialogs.asked)
    }

    @Test
    fun `removing an image used by stopped containers offers force, and runs as a background task`() {
        dialogs.removeImageAnswer = true

        resourceActions.removeImage(cli.images.first())

        awaitTask("finished in")
        assertEquals(listOf("remove nginx:1.27 stopped=[old]"), dialogs.asked)
        assertTrue("removeImage nginx:1.27 force=true" in cli.calls)
        assertTrue(taskLog.first().endsWith(" started: Remove image nginx:1.27"), taskLog.toString())
    }

    @Test
    fun `Pull asks for the reference, shows the CLI's progress in the task's name and ends it`() {
        cli.pullProgress = listOf("1.27: Pulling from library/nginx", "Status: Downloaded newer image")

        resourceActions.pull()

        awaitTask("finished in")
        assertTrue("pull nginx:1.27" in cli.calls)
        assertTrue(taskLog.any { "Pull nginx:1.27: Status: Downloaded newer image" in it }, taskLog.toString())
    }

    @Test
    fun `cancelling the Pull task interrupts the pull and reports no error`() {
        val pulling = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        cli.failing = { call ->
            if (call.startsWith("pull")) {
                pulling.countDown()
                try {
                    Thread.sleep(5_000) // Stands for the CLI's pull until the cancel interrupts it.
                } catch (e: InterruptedException) {
                    interrupted.countDown()
                    throw DockerException("pull interrupted")
                }
            }
            null
        }

        resourceActions.pull()
        assertTrue(pulling.await(5, TimeUnit.SECONDS))
        tasks.shown.single().cancel()

        assertTrue(interrupted.await(5, TimeUnit.SECONDS))
        awaitTask("cancelled after")
        fx.drain()
        assertEquals(null, model.error.get())
    }

    @Test
    fun `a failed task ends as failed and shows in the error bar`() {
        cli.failing = { if (it == "createVolume cache") DockerException("volume exists") else null }

        resourceActions.createVolume()

        fx.runUntil { model.error.get() != null }
        assertEquals("volume exists", model.error.get())
        awaitTask("failed")
    }

    @Test
    fun `prunes list their candidates and are disabled when there are none`() {
        val images = actions.of(GroupRow(ResourceKind.IMAGE, 2))
        assertEquals(listOf("Pull…", "Prune Dangling Images…"), images.map { it.title })
        assertTrue(images.last().enabled)

        actions.of(GroupRow(ResourceKind.VOLUME, 2)).last().run()
        awaitCall("pruneVolumes")
        assertEquals(listOf("Prune Unused Volumes: [orphan]"), dialogs.asked)

        cli.networks = listOf(network("bridge"))
        session.refresh()
        val networks = actions.of(GroupRow(ResourceKind.NETWORK, 1))
        assertFalse(networks.last().enabled)
        networks.last().run()
        assertEquals(1, dialogs.asked.size, "an empty prune asks nothing")
    }

    @Test
    fun `Create Volume and Create Network run with the dialogs' answers`() {
        actions.of(GroupRow(ResourceKind.VOLUME, 2)).first().run()
        actions.of(GroupRow(ResourceKind.NETWORK, 2)).first().run()

        fx.runUntil { "createVolume cache" in cli.calls && "createNetwork backend driver=overlay" in cli.calls }
    }

    @Test
    fun `the engine's own networks cannot be removed`() {
        assertFalse(actions.of(NetworkRow(network("bridge"))).single().enabled)
        assertTrue(actions.of(NetworkRow(network("unused"))).single().enabled)

        resourceActions.removeNetwork(network("bridge"))
        resourceActions.removeNetwork(network("unused"))

        awaitCall("removeNetwork net-unused")
        assertEquals(listOf("Remove Network: Remove the network unused?"), dialogs.asked)
    }
}
