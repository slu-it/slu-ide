package dev.sluide.docker.tab

import dev.sluide.backgroundtasks.BackgroundTask
import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.docker.cli.DockerCli
import dev.sluide.docker.model.ContainerState
import dev.sluide.docker.model.Image
import dev.sluide.docker.model.Network
import dev.sluide.docker.model.PruneCandidates
import dev.sluide.docker.model.Volume
import dev.sluide.docker.model.pruneCandidates
import dev.sluide.docker.tab.details.imageUsers
import dev.sluide.docker.tab.tree.imageLabel
import dev.sluide.docker.tab.tree.volumeLabel
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference

/**
 * The image, volume and network actions of the Docker tab: Remove, Pull…, Prune…, Create…. Each asks first where it
 * removes something, then runs as a row of the window's [tasks] (ADR 0061); failures go to the tab's error bar. FX
 * thread only.
 */
internal class ResourceActions(
    private val model: DockerTabModel,
    private val dialogs: DockerDialogs,
    private val tasks: BackgroundTasks,
) {
    private val session get() = model.session

    /** What the prune actions would remove now. */
    fun pruneCandidates(): PruneCandidates =
        pruneCandidates(
            session.images.toList(),
            session.volumes.toList(),
            session.networks.toList(),
            session.containers.toList(),
        )

    fun removeImage(image: Image) {
        val users = imageUsers(image, session.containers.toList())
        val stopped = users.filter { it.state != ContainerState.RUNNING }.map { it.name }
        val force = dialogs.removeImage(imageLabel(image), stopped) ?: return
        task("Remove image ${imageLabel(image)}", "remove-image") { cli, _ -> cli.removeImage(image.reference, force) }
    }

    fun removeVolume(volume: Volume) {
        if (!dialogs.confirm(
                "Remove Volume",
                "Remove the volume ${volumeLabel(volume)}? Its data is lost.",
                "Remove",
            )
        ) {
            return
        }
        task("Remove volume ${volumeLabel(volume)}", "remove-volume") { cli, _ -> cli.removeVolume(volume.name) }
    }

    /** Whether [network] can be removed: not one of the engine's own. */
    fun canRemove(network: Network): Boolean = !network.builtin

    fun removeNetwork(network: Network) {
        if (!canRemove(network)) return
        if (!dialogs.confirm("Remove Network", "Remove the network ${network.name}?", "Remove")) return
        task("Remove network ${network.name}", "remove-network") { cli, _ -> cli.removeNetwork(network.id) }
    }

    /** Pull…: asks for `name:tag` and pulls it as a cancellable task that shows the CLI's progress. */
    fun pull() {
        val reference = dialogs.askPull()?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val name = "Pull $reference"
        val future = AtomicReference<CompletableFuture<*>>()
        val task = tasks.start(name) { future.get()?.cancel(true) }
        future.set(
            track(task, "pull") { cli -> cli.pull(reference) { line -> task.rename("$name: ${line.trim()}") } },
        )
    }

    fun pruneImages() =
        prune(
            "Prune Dangling Images",
            "dangling images",
            pruneCandidates().images.map(::imageLabel),
        ) { it.pruneImages() }

    fun pruneVolumes() =
        prune(
            "Prune Unused Volumes",
            "unused volumes",
            pruneCandidates().volumes.map(::volumeLabel),
        ) { it.pruneVolumes() }

    fun pruneNetworks() =
        prune(
            "Prune Unused Networks",
            "unused networks",
            pruneCandidates().networks.map { it.name },
        ) { it.pruneNetworks() }

    fun createVolume() {
        val name = dialogs.askVolumeName()?.trim()?.takeIf { it.isNotEmpty() } ?: return
        task("Create volume $name", "create-volume") { cli, _ -> cli.createVolume(name) }
    }

    fun createNetwork() {
        val network = dialogs.askNetwork()?.takeIf { it.name.isNotBlank() } ?: return
        task("Create network ${network.name}", "create-network") { cli, _ ->
            cli.createNetwork(network.name.trim(), network.driver)
        }
    }

    private fun prune(
        title: String,
        what: String,
        candidates: List<String>,
        prune: (DockerCli) -> String,
    ) {
        if (candidates.isEmpty()) return
        val message = "Remove these $what? The engine decides in the end and may find a few more or fewer."
        if (!dialogs.confirmList(title, message, candidates, "Remove")) return
        task("Prune $what", "prune") { cli, _ -> prune(cli) }
    }

    private fun task(
        name: String,
        threadName: String,
        work: (DockerCli, BackgroundTask) -> Any?,
    ) {
        val task = tasks.start(name)
        track(task, threadName) { cli -> work(cli, task) }
    }

    /** Runs [work] through the model and ends [task] with its outcome. */
    private fun <T> track(
        task: BackgroundTask,
        threadName: String,
        work: (DockerCli) -> T,
    ): CompletableFuture<T> =
        model.run(threadName, action = work).also { future ->
            future.whenComplete { _, failure -> if (failure == null) task.close() else task.failed(failure) }
        }
}
