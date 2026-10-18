package dev.sluide.git.cli

/**
 * What a fetch adds to git's [environment] (ADR 0043): `GIT_SSH_COMMAND=ssh -o BatchMode=yes`, so that ssh never asks
 * for a passphrase, password or host key on the terminal Thoreau may have been started from (ssh reads `/dev/tty`, not
 * its standard input); nothing if the user chose how git runs ssh: `GIT_SSH_COMMAND`, `GIT_SSH` or `GIT_SSH_VARIANT` in
 * the environment, or `core.sshCommand` or `ssh.variant` configured ([sshConfigured]), which the variable would
 * override or not fit.
 */
internal fun fetchEnvironment(
    environment: Map<String, String>,
    sshConfigured: Boolean,
): Map<String, String> {
    val chosen = sshConfigured || listOf("GIT_SSH_COMMAND", "GIT_SSH", "GIT_SSH_VARIANT").any { it in environment }
    return if (chosen) emptyMap() else mapOf("GIT_SSH_COMMAND" to "ssh -o BatchMode=yes")
}
