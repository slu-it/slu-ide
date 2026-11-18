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
import dev.sluide.docker.tab.tree.ImageRow
import dev.sluide.docker.tab.tree.NetworkRow
import dev.sluide.docker.tab.tree.VolumeRow
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
    private val runs = mutableListOf<String>()
    private val actions = RowActions(containerActions, resourceActions) { runs += it }

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

    /** The names of the tasks started so far. */
    private fun started() = taskLog.filter { " started: " in it }.map { it.substringAfter(" started: ") }

    private fun calls(prefix: String) = cli.calls.filter { it.startsWith(prefix) }

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
        assertTrue(model.running.isEmpty(), "declined: nothing is started")
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
        assertTrue(model.running.isEmpty(), "cancelled: nothing is started")
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
    fun `prunes list their candidates, and each button is enabled exactly when it would remove something`() {
        fun prunes() =
            listOf(ResourceKind.IMAGE, ResourceKind.VOLUME, ResourceKind.NETWORK).map { kind ->
                actions.of(GroupRow(kind, 0)).single { it.title.startsWith("Prune") }.enabled
            }
        val images = actions.of(GroupRow(ResourceKind.IMAGE, 2))
        assertEquals(listOf("Pull…", "Prune Dangling Images…"), images.map { it.title })
        assertEquals(listOf(true, true, true), prunes())

        actions.of(GroupRow(ResourceKind.VOLUME, 2)).last().run()
        awaitCall("pruneVolumes")
        assertEquals(listOf("Prune Unused Volumes: [orphan]"), dialogs.asked)

        cli.images = listOf(image("nginx", "1.27"))
        cli.volumes = listOf(volume("data", inUse = true))
        cli.networks = listOf(network("bridge"))
        session.refresh()
        assertEquals(listOf(false, false, false), prunes())
        actions.of(GroupRow(ResourceKind.NETWORK, 1)).last().run()
        assertEquals(1, dialogs.asked.size, "an empty prune asks nothing")
    }

    @Test
    fun `Create Volume and Create Network run with the dialogs' answers`() {
        actions.of(GroupRow(ResourceKind.VOLUME, 2)).first().run()
        actions.of(GroupRow(ResourceKind.NETWORK, 2)).first().run()

        fx.runUntil { "createVolume cache" in cli.calls && "createNetwork backend driver=overlay" in cli.calls }
        assertEquals(listOf("Create volume cache", "Create network backend"), started())
    }

    @Test
    fun `Create Volume and Create Network do nothing for a cancelled or blank dialog, and trim the name`() {
        dialogs.volumeAnswer = null
        resourceActions.createVolume()
        dialogs.volumeAnswer = ""
        resourceActions.createVolume()
        dialogs.volumeAnswer = "  "
        resourceActions.createVolume()
        dialogs.networkAnswer = null
        resourceActions.createNetwork()
        dialogs.networkAnswer = NewNetwork("", "bridge")
        resourceActions.createNetwork()
        dialogs.networkAnswer = NewNetwork("  ", "bridge")
        resourceActions.createNetwork()
        assertEquals(emptyList(), started(), "a task starts at once, on the calling thread")

        dialogs.volumeAnswer = " cache "
        resourceActions.createVolume()
        dialogs.networkAnswer = NewNetwork(" backend ", "bridge")
        resourceActions.createNetwork()

        fx.runUntil { calls("create").size == 2 }
        assertEquals(listOf("createNetwork backend driver=bridge", "createVolume cache"), calls("create").sorted())
    }

    @Test
    fun `Pull does nothing for a cancelled or blank dialog, and pulls the trimmed reference`() {
        for (answer in listOf(null, "", "  ")) {
            dialogs.pullAnswer = answer
            resourceActions.pull()
        }
        assertEquals(emptyList(), taskLog.toList())
        assertEquals(emptyList(), calls("pull"))

        dialogs.pullAnswer = "  redis:7 "
        cli.pullProgress = listOf("  Status: padded  ")
        resourceActions.pull()

        awaitTask("finished in")
        assertEquals(listOf("pull redis:7"), calls("pull"))
        assertEquals(listOf("Pull redis:7"), started())
        assertTrue(taskLog.single { "finished in" in it }.endsWith("Pull redis:7: Status: padded"), taskLog.toString())
    }

    @Test
    fun `an image is removed after its dialog, naming only its stopped users`() {
        cli.containers = cli.containers + container("live", image = "nginx:1.27")
        session.refresh()
        val nginx = cli.images.first()
        val untagged = cli.images.last()

        dialogs.removeImageAnswer = null
        resourceActions.removeImage(nginx)
        assertEquals(emptyList(), started(), "cancelled")

        dialogs.removeImageAnswer = false
        actions.of(ImageRow(nginx)).last().run()
        actions.of(ImageRow(untagged)).last().run()

        fx.runUntil { calls("removeImage").size == 2 }
        assertEquals(
            listOf(
                "remove nginx:1.27 stopped=[old]",
                "remove nginx:1.27 stopped=[old]",
                "remove <none> dddddddddddd stopped=[]",
            ),
            dialogs.asked,
        )
        assertEquals(
            listOf("removeImage nginx:1.27 force=false", "removeImage ${untagged.id} force=false"),
            calls("removeImage").sorted(),
        )
        assertEquals(listOf("Remove image nginx:1.27", "Remove image <none> dddddddddddd"), started())
    }

    @Test
    fun `Remove on a volume asks first with its shortened name, declining removes nothing`() {
        val anonymous = volume("a".repeat(64), anonymous = true)
        val question = "Remove Volume: Remove the volume aaaaaaaaaaaa…? Its data is lost."

        dialogs.confirmAnswer = false
        actions.of(VolumeRow(anonymous)).single().run()
        assertEquals(emptyList(), started(), "declined")
        dialogs.confirmAnswer = true
        actions.of(VolumeRow(anonymous)).single().run()

        awaitCall("removeVolume ${anonymous.name}")
        assertEquals(listOf(question, question), dialogs.asked)
        assertEquals(1, calls("removeVolume").size)
        assertEquals(listOf("Remove volume aaaaaaaaaaaa…"), started())
    }

    @Test
    fun `Remove on a network asks first, declining removes nothing`() {
        val unused = network("unused")
        val question = "Remove Network: Remove the network unused?"

        dialogs.confirmAnswer = false
        resourceActions.removeNetwork(unused)
        assertEquals(emptyList(), started(), "declined")
        dialogs.confirmAnswer = true
        actions.of(NetworkRow(unused)).single().run()

        awaitCall("removeNetwork net-unused")
        assertEquals(listOf(question, question), dialogs.asked)
        assertEquals(1, calls("removeNetwork").size)
        assertEquals(listOf("Remove network unused"), started())
    }

    @Test
    fun `each prune names its candidates, and removes nothing when declined`() {
        dialogs.confirmAnswer = false
        resourceActions.pruneImages()
        resourceActions.pruneVolumes()
        resourceActions.pruneNetworks()
        assertEquals(
            listOf(
                "Prune Dangling Images: [<none> dddddddddddd]",
                "Prune Unused Volumes: [orphan]",
                "Prune Unused Networks: [unused]",
            ),
            dialogs.asked,
        )
        assertEquals(emptyList(), started(), "declined")

        dialogs.confirmAnswer = true
        resourceActions.pruneImages()
        resourceActions.pruneVolumes()
        resourceActions.pruneNetworks()

        fx.runUntil { calls("prune").size == 3 }
        assertEquals(listOf("pruneImages", "pruneNetworks", "pruneVolumes"), calls("prune").sorted())
        assertEquals(
            listOf("Prune dangling images", "Prune unused volumes", "Prune unused networks"),
            started(),
        )
    }

    @Test
    fun `the group buttons run Pull, Prune Images and Prune Networks, the other rows offer nothing`() {
        actions.of(GroupRow(ResourceKind.IMAGE, 2)).first().run()
        actions.of(GroupRow(ResourceKind.IMAGE, 2)).last().run()
        actions.of(GroupRow(ResourceKind.NETWORK, 2)).last().run()

        fx.runUntil { "pull nginx:1.27" in cli.calls && "pruneImages" in cli.calls && "pruneNetworks" in cli.calls }
        assertEquals(emptyList(), actions.of(GroupRow(ResourceKind.CONTAINER, 2)))
        assertEquals(emptyList(), actions.of(null))
    }

    @Test
    fun `an image offers Run before Remove, and Run hands its reference to the Run dialog`() {
        val tagged = cli.images.first()
        val untagged = cli.images.last()

        assertEquals(listOf("Run…", "Remove"), actions.of(ImageRow(tagged)).map { it.title })
        actions.of(ImageRow(tagged)).first().run()
        actions.of(ImageRow(untagged)).first().run()

        assertEquals(listOf("nginx:1.27", untagged.id), runs)
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
