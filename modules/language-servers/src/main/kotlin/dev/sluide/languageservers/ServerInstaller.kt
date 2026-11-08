package dev.sluide.languageservers

import dev.sluide.backgroundtasks.BackgroundTasks
import javafx.beans.value.ObservableValue

/** Installs a language server on the user's click: what the install banner and the settings page show and start. */
interface ServerInstaller {
    /** Whether the server can run; changes on the FX thread. */
    val availability: ObservableValue<ServerAvailability>

    /** The name users know the installed server by, for the install banner: `ty`; `null` says "its language server". */
    val toolName: String? get() = null

    /** Starts installing, shown as a cancellable row in [tasks] if given; does nothing while installing. */
    fun install(tasks: BackgroundTasks?)

    /** Stops a running install; it ends [ServerAvailability.Missing] or as it was. */
    fun cancel()

    /**
     * Calls [listener] on the FX thread whenever the command [LanguageServerSpec.command] returns changes (an install,
     * update, rollback or delete, a changed custom path), so that running servers can be restarted with it; closing
     * the result removes the listener. The default never calls it.
     */
    fun onCommandChanged(listener: () -> Unit): AutoCloseable = AutoCloseable {}
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
