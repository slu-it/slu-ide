package dev.sluide.git.ssh

import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * What ssh asks, told by its prompt text alone (it sets no `SSH_ASKPASS_PROMPT` for these, measured with OpenSSH 10.0;
 * ADR 0194, `src/test/resources/ssh-askpass/README.md`).
 */
internal sealed interface SshPrompt {
    /** `Enter passphrase for key '<path>': `: the key's path as ssh shows it (relative, `..` kept, cut at 100 chars). */
    data class KeyPassphrase(
        val keyFile: String,
    ) : SshPrompt

    /** The unknown host prompt: its [text] up to the final question line, without the line break before it. */
    data class UnknownHost(
        val text: String,
    ) : SshPrompt

    /** Anything else (a password, a PIN, the host prompt's "Please type 'yes', …" re-prompt): never answered. */
    data object Other : SshPrompt
}

/** The kind of ssh's prompt [text]. */
internal fun sshPrompt(text: String): SshPrompt {
    // The fixed prefix and suffix around the path, which may hold quotes itself; they must not overlap.
    val framing = PASSPHRASE_PREFIX.length + PASSPHRASE_SUFFIX.length
    if (text.length > framing && text.startsWith(PASSPHRASE_PREFIX) && text.endsWith(PASSPHRASE_SUFFIX)) {
        return SshPrompt.KeyPassphrase(text.substring(PASSPHRASE_PREFIX.length, text.length - PASSPHRASE_SUFFIX.length))
    }
    val question = text.substringAfterLast('\n', missingDelimiterValue = "")
    if (text.startsWith(HOST_PREFIX) && question.startsWith(HOST_QUESTION)) {
        return SshPrompt.UnknownHost(text.substringBeforeLast('\n'))
    }
    return SshPrompt.Other
}

/**
 * The key file ssh named as [shown] in its prompt: a relative path resolved against [root], the working directory of git
 * and ssh, then normalized (ssh has expanded `~` itself); `null` if [shown] is no path.
 */
internal fun keyFile(
    root: Path,
    shown: String,
): Path? =
    try {
        root.resolve(shown).normalize()
    } catch (_: InvalidPathException) {
        null
    }

private const val PASSPHRASE_PREFIX = "Enter passphrase for key '"
private const val PASSPHRASE_SUFFIX = "': "
private const val HOST_PREFIX = "The authenticity of host "
private const val HOST_QUESTION = "Are you sure you want to continue connecting"
