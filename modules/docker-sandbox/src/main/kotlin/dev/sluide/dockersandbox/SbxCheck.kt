package dev.sluide.dockersandbox

import dev.sluide.terminal.loginShellFinds
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Whether `sbx` is installed: found by the user's login shell, which is where the sandboxes run it. Starts a login shell,
 * which may take a while with a slow profile, so this must not run on the FX thread.
 */
internal object SbxCheck {
    /** Whether `command -v sbx` succeeds in the login shell started with [environment]; false after [timeout]. */
    fun installed(
        environment: Map<String, String>,
        timeout: Duration = 10.seconds,
    ): Boolean = loginShellFinds("sbx", environment, timeout)
}
