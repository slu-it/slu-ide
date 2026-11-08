package dev.sluide.codeintelligence.server

import dev.sluide.codeintelligence.binding.BindingFeature
import dev.sluide.codeintelligence.binding.DocumentBinding
import dev.sluide.languageservers.ServerState
import javafx.scene.control.MenuItem

/**
 * "Restart Python Language Server" in the context menu of a bound editor (ADR 0145): restarts the window's server of
 * the language, e.g. after a `pip install`; disabled with the reason when there is nothing to restart.
 */
internal object RestartServerItem : BindingFeature {
    override fun attach(binding: DocumentBinding): AutoCloseable =
        binding.addContextMenuItems {
            val label = "Restart ${binding.spec.name} Language Server"
            val reason = restartBlocker(binding.session.state.value)
            listOf(
                MenuItem(if (reason == null) label else "$label ($reason)").apply {
                    isDisable = reason != null
                    setOnAction { binding.session.restart() }
                },
            )
        }
}

/** Why a server in [state] cannot be restarted from the menu, `null` if it can. */
internal fun restartBlocker(state: ServerState): String? =
    when (state) {
        ServerState.Stopped -> "not started"
        ServerState.Unavailable -> "not installed"
        ServerState.Untrusted -> "project not trusted"
        ServerState.Starting, ServerState.Running, is ServerState.Restarting, is ServerState.Failed -> null
    }

/** Whether a server in [state] has been started, so that a changed command means restarting it. */
internal fun hasStarted(state: ServerState): Boolean = restartBlocker(state) == null
