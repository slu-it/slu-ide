package dev.sluide.ide

import dev.sluide.java.OutputIgnoreChecks
import javafx.beans.value.ObservableValue
import javafx.util.Subscription
import java.nio.file.Path

/**
 * Registers one window's git with [OutputIgnoreChecks] for its project folder (ADR 0179), so that the Java module can
 * ask which of jdtls's output folders git does not ignore without running git itself. [ask] is the window's
 * `GitStatusService.unignoredFoldersAsync`.
 */
internal class OutputIgnoreRegistration(
    private val projectFolder: ObservableValue<Path?>,
    private val checks: OutputIgnoreChecks,
    private val ask: (folders: List<Path>, done: (List<Path>?) -> Unit) -> Unit,
) {
    private var registration: AutoCloseable? = null
    private var folderSubscription: Subscription? = null

    /** Registers for the current folder, and again whenever the folder changes; once per window. */
    fun track() {
        // `subscribe` also calls the consumer with the folder that is set now.
        folderSubscription = projectFolder.subscribe { _ -> registerFolder() }
    }

    private fun registerFolder() {
        registration?.close()
        registration = projectFolder.value?.let { folder -> checks.register(folder, ask) }
    }

    /** Ends the registration; called when the window closes. */
    fun close() {
        folderSubscription?.unsubscribe()
        registration?.close()
    }
}
