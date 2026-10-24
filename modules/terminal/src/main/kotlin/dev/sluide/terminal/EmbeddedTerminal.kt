package dev.sluide.terminal

import javafx.beans.property.ReadOnlyBooleanProperty
import javafx.scene.Node

/**
 * A terminal running one command in the user's login shell, shown by another module's view instead of in a tab.
 *
 * The caller must [close] a terminal it no longer shows, also after its command ended; until then it stays alive and
 * follows the settings.
 */
interface EmbeddedTerminal {
    /** The terminal's node; it fills the space it is given. */
    val node: Node

    /** Becomes true on the FX thread once the command ended (also after [close]). */
    val exited: ReadOnlyBooleanProperty

    /** The command's exit code once [exited]; null while it runs, or if it was closed before it ended. */
    val exitCode: Int?

    /** Whether the command still runs. */
    val isAlive: Boolean

    /** Sends Ctrl+C (byte 0x03) through the pseudo terminal, as typing it would; does nothing once exited. */
    fun interrupt()

    /**
     * Ends the command as closing a terminal tab does (SIGHUP via the closed pty) and releases everything; idempotent.
     * FX thread.
     */
    fun close()
}
