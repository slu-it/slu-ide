package dev.sluide.gitlog.actions

import dev.sluide.git.ssh.PassphraseNotice
import java.nio.file.Path

/** What the passphrase dialog says above its field when ssh asks again (ADR 0194). */
internal fun noticeText(notice: PassphraseNotice): String =
    when (notice) {
        PassphraseNotice.WRONG -> "Wrong passphrase, try again."
        PassphraseNotice.SAVED_REJECTED -> "The saved passphrase was not accepted and was removed."
    }

/** [label] of a check box that is disabled with the [reason] it cannot be checked, if there is one. */
internal fun checkBoxLabel(
    label: String,
    reason: String?,
): String = if (reason == null) label else "$label ($reason)"

/** The question of the passphrase dialog: the key's path with the [home] folder written `~`. */
internal fun passphraseQuestion(
    keyFile: Path,
    home: Path? = userHome(),
): String = "Enter the passphrase for ${abbreviateHome(keyFile, home)}."

/** [path] with the [home] folder at its start written `~` (as ssh shows it in its own messages); else as it is. */
internal fun abbreviateHome(
    path: Path,
    home: Path?,
): String {
    if (home == null || !path.startsWith(home)) return path.toString()
    val rest = home.relativize(path).toString()
    return if (rest.isEmpty()) "~" else "~/$rest"
}

/** Unlock needs a passphrase: an empty field would only make ssh stop asking for the key. */
internal fun unlockDisabled(passphrase: String): Boolean = passphrase.isEmpty()

private fun userHome(): Path? = homeFolder(System.getProperty("user.home"))

/** The home folder named by the `user.home` [property]; `null` if it is not set or blank. */
internal fun homeFolder(property: String?): Path? = property?.takeIf { it.isNotBlank() }?.let(Path::of)
