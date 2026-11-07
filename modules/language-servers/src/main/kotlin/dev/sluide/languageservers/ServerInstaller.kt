package dev.sluide.languageservers

import dev.sluide.backgroundtasks.BackgroundTasks
import javafx.beans.value.ObservableValue

/** Installs a language server on the user's click: what the install banner and the settings page show and start. */
interface ServerInstaller {
    /** Whether the server can run; changes on the FX thread. */
    val availability: ObservableValue<ServerAvailability>

    /** Starts installing, shown as a cancellable row in [tasks] if given; does nothing while installing. */
    fun install(tasks: BackgroundTasks?)

    /** Stops a running install; it ends [ServerAvailability.Missing] or as it was. */
    fun cancel()
}

/** Whether an installer's server can run. */
sealed interface ServerAvailability {
    /** Installed: [LanguageServerSpec.command] answers a command. */
    data object Ready : ServerAvailability

    /** Not installed; [downloadBytes] is the size of the download if known. */
    data class Missing(
        val downloadBytes: Long?,
    ) : ServerAvailability

    /** Installing, [progress] from 0 to 1. */
    data class Installing(
        val progress: Double,
    ) : ServerAvailability

    /** The last install failed with [message]. */
    data class Failed(
        val message: String,
    ) : ServerAvailability
}
