package dev.sluide.docker.run

import dev.sluide.backgroundtasks.BackgroundTask
import dev.sluide.docker.cli.DockerCli
import dev.sluide.docker.cli.ResolvedDocker
import dev.sluide.editorarea.EditorTab
import dev.sluide.terminal.TerminalStartException
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/** What [ImageRunner] needs from the window and the Docker tool; all on the FX thread unless noted. */
internal interface RunHost {
    val projectFolder: Path?

    /** The folder of attached runs, and the base of `~/` volumes, while no project is open. */
    val home: Path

    /** The engine's CLI and environment; `null` while Docker is not available. */
    val docker: ResolvedDocker?

    /** The names of the session's networks. */
    val networks: List<String>

    /** The names of the session's volumes. */
    val volumes: List<String>

    /** Shows the Run Container dialog; the options to run, `null` if cancelled. */
    fun ask(initial: RunOptions): RunOptions?

    /** Runs [action] with the CLI off the FX thread ([dev.sluide.docker.DockerSession.submit]). */
    fun submit(
        name: String,
        action: (DockerCli) -> String,
    ): CompletableFuture<String>

    /** A row of the background task list; [cancel] runs when the user cancels it. */
    fun startTask(
        name: String,
        cancel: () -> Unit,
    ): BackgroundTask

    /** Starts [command] in a terminal tab ([dev.sluide.terminal.Terminals.open]). */
    fun openTerminal(
        folder: Path,
        name: String,
        command: String,
        environment: Map<String, String>,
    ): EditorTab

    fun openTab(tab: EditorTab)

    fun showContainer(
        id: String,
        logs: Boolean,
    )

    fun showError(message: String)

    /** Hands [action] to the FX thread. */
    fun onFx(action: Runnable)
}

/**
 * Run… for an image: loads the values last used for its repository, asks, remembers what was asked, then starts the
 * container: detached through the CLI as a background task that ends by showing the container with its logs, attached
 * in a terminal tab named "<image> (run)". Failures go to the Docker tab's error bar. FX thread only.
 */
internal class ImageRunner(
    private val host: RunHost,
    private val store: DockerRunStore,
) {
    fun run(reference: String) {
        val folder = host.projectFolder
        val asked = host.ask(store.load(reference, folder))?.withoutBlankRows() ?: return
        // The dialog always runs the image that was asked for, whatever it was prefilled with.
        val options = asked.copy(image = reference)
        store.save(options, folder)
        validate(options)?.let { return host.showError(it) }
        val docker = host.docker ?: return host.showError("Docker is not available")
        if (options.detached) {
            detached(options.image, runArgs(options, folder, host.home))
        } else {
            attached(docker, options, attachedCommand(docker.executable.toString(), options, folder, host.home))
        }
    }

    private fun detached(
        image: String,
        args: List<String>,
    ) {
        val cancel =
            java.util.concurrent.atomic
                .AtomicReference<CompletableFuture<*>>()
        val task = host.startTask("Run $image") { cancel.get()?.cancel(true) }
        val future = host.submit("run") { cli -> cli.run(args) }
        cancel.set(future)
        future.whenComplete { id, failure ->
            host.onFx {
                if (failure == null) {
                    task.close()
                    host.showContainer(id, true)
                } else {
                    task.failed(failure)
                    val cause = if (failure is CompletionException) failure.cause ?: failure else failure
                    if (cause !is CancellationException) {
                        host.showError(cause.message?.takeIf { it.isNotBlank() } ?: cause.toString())
                    }
                }
            }
        }
    }

    private fun attached(
        docker: ResolvedDocker,
        options: RunOptions,
        command: String,
    ) {
        try {
            host.openTab(
                host.openTerminal(
                    host.projectFolder ?: host.home,
                    "${options.image} (run)",
                    command,
                    docker.environment + attachedEnvironment(options),
                ),
            )
        } catch (e: TerminalStartException) {
            host.showError("The container cannot be started. ${e.message}")
        }
    }
}
