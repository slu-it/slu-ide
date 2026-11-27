package dev.sluide.git.cli

import dev.sluide.git.GitCancellation
import dev.sluide.git.ssh.SshAccess
import java.nio.file.Path
import kotlin.time.Duration

/**
 * What a call that contacts a remote adds to git's [environment] for ssh, by its [access] (ADR 0043, 0194):
 * - [SshAccess.Batch]: `GIT_SSH_COMMAND=ssh -o BatchMode=yes`, so that ssh never asks for a passphrase, password or
 *   host key on the terminal Thoreau may have been started from (ssh reads `/dev/tty`, not its standard input);
 *   nothing if the user chose how git runs ssh: `GIT_SSH_COMMAND`, `GIT_SSH` or `GIT_SSH_VARIANT` in the environment,
 *   or `core.sshCommand` or `ssh.variant` configured ([SshSettings.sshChosen]), which the variable would override or
 *   not fit.
 * - [SshAccess.Prompting]: the session's askpass variables, also with the user's own ssh command (only OpenSSH reads
 *   them); ssh asks through Thoreau's dialogs, never on a terminal. Where batch mode would be set,
 *   `GIT_SSH_COMMAND=ssh -o PasswordAuthentication=no -o KbdInteractiveAuthentication=no` too: ssh would otherwise try
 *   a password login, and the askpass's "no answer" is an empty password sent to the server (up to three times). With
 *   the user's own command it may still do so (ADR 0194). And `GIT_ASKPASS=` (empty), unless the user set
 *   `GIT_ASKPASS` or `core.askPass` ([SshSettings.askPass]): git asks for https credentials through the first of
 *   `GIT_ASKPASS`, `core.askPass` and `SSH_ASKPASS` that is set, runs it only if it is not empty, and would otherwise
 *   run the session's helper.
 */
internal fun sshEnvironment(
    environment: Map<String, String>,
    configured: SshSettings,
    access: SshAccess,
): Map<String, String> {
    val chosen =
        configured.sshChosen || listOf("GIT_SSH_COMMAND", "GIT_SSH", "GIT_SSH_VARIANT").any { it in environment }
    if (access is SshAccess.Prompting) {
        // Without batch mode ssh would try a password login, and the askpass's "no answer" is an empty password.
        val noPasswords = if (chosen) emptyMap() else mapOf("GIT_SSH_COMMAND" to NO_PASSWORDS)
        // git asks GIT_ASKPASS, core.askPass, then SSH_ASKPASS for https credentials, and runs only a non-empty one.
        // An empty GIT_ASKPASS ends that chain before our SSH_ASKPASS; the user's own askpass stays git's.
        val own = configured.askPass || "GIT_ASKPASS" in environment
        val gitAskpassOff = if (own) emptyMap() else mapOf("GIT_ASKPASS" to "")
        return access.environment + noPasswords + gitAskpassOff
    }
    return if (chosen) emptyMap() else mapOf("GIT_SSH_COMMAND" to "ssh -o BatchMode=yes")
}

private const val NO_PASSWORDS = "ssh -o PasswordAuthentication=no -o KbdInteractiveAuthentication=no"

/**
 * What a call in the repository at [root] that contacts a remote adds to git's environment ([sshEnvironment]). If the
 * configuration cannot be read, ssh counts as not chosen (batch mode, or a session without password logins) and
 * `core.askPass` as set (so that a user's own askpass is never turned off).
 */
internal fun GitCli.remoteEnvironment(
    root: Path,
    access: SshAccess,
): Map<String, String> = sshEnvironment(environment, sshSettings(root) ?: UNREADABLE, access)

private val UNREADABLE = SshSettings(sshChosen = false, askPass = true)

/**
 * [GitCli.writeWithOutput] of a call in the repository at [root] that contacts a remote, with the [environment] of
 * [access] ([remoteEnvironment], given by a caller that runs several such calls); a session of [access] hears how it
 * ended, also when git failed, timed out or was cancelled ([SshAccess.Prompting.ended], success for exit 0).
 */
internal fun GitCli.writeRemote(
    root: Path,
    arguments: List<String>,
    access: SshAccess,
    cancellation: GitCancellation,
    timeout: Duration,
    environment: Map<String, String> = remoteEnvironment(root, access),
): GitWriteOutput {
    val written =
        writeWithOutput(root, arguments, timeout = timeout, environment = environment, cancellation = cancellation)
    (access as? SshAccess.Prompting)?.ended(written.exitValue == 0)
    return written
}
