package dev.sluide.encryptedfiles.ui

import dev.sluide.encryptedfiles.SluencFailure
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/** The extension of encrypted files, lower case, without the dot. */
internal const val SLUENC_EXTENSION = "sluenc"

/** The name of the file inside [name]: without `.sluenc` (any case); `notes.md` for `notes.md.sluenc`. */
internal fun innerName(name: String): String =
    if (name.endsWith(".$SLUENC_EXTENSION", ignoreCase = true)) name.dropLast(SLUENC_EXTENSION.length + 1) else name

/** The path the inner view is shown under: beside [file], named [innerName]; never read or written. */
internal fun virtualPath(file: Path): Path = file.resolveSibling(innerName(file.fileName.toString()))

/** What the locked pane says when no viewer shows the inner type. */
internal fun cannotShowText(innerName: String): String {
    val extension = innerName.substringAfterLast('.', "")
    return if (extension.isEmpty()) "Cannot show this file's content." else "Cannot show .$extension files."
}

/** What the locked pane says above the password field. */
internal fun encryptedTitle(name: String): String = "$name is encrypted."

/**
 * The locked pane's message for an unlock that failed with [failure] (the file's [formatVersion] for a newer one), or
 * for a clean tab locked because its file changed on disk to bytes its key cannot read.
 */
internal fun failureMessage(
    failure: SluencFailure,
    formatVersion: Int? = null,
): String =
    when (failure) {
        SluencFailure.WRONG_PASSWORD -> "Wrong password."
        SluencFailure.NOT_SLUENC -> "Not a Thoreau encrypted file."
        SluencFailure.NEWER_VERSION -> "Written by a newer Thoreau (format ${formatVersion ?: "unknown"})."
        SluencFailure.UNSUPPORTED_PARAMETERS -> "Unsupported encryption parameters."
        SluencFailure.DAMAGED -> "The file is damaged."
        SluencFailure.KEY_MISMATCH -> CHANGED_ELSEWHERE
    }

/** The strip's warning while a tab with unsaved changes cannot read its changed file. */
internal fun changedOnDiskWarning(
    failure: SluencFailure,
    formatVersion: Int? = null,
): String =
    if (failure == SluencFailure.KEY_MISMATCH) {
        "Changed elsewhere with another password. Saving replaces it with your version."
    } else {
        "${failureMessage(failure, formatVersion)} Saving replaces it with your version."
    }

/** Asked before saving over a file that changed on disk to bytes the tab's key cannot read. */
internal fun replaceQuestion(
    name: String,
    failure: SluencFailure,
    formatVersion: Int? = null,
): String =
    if (failure == SluencFailure.KEY_MISMATCH) {
        "Replace $name? It was changed elsewhere with another password; saving writes your version under the " +
            "password you unlocked it with."
    } else {
        "Replace $name? ${failureMessage(failure, formatVersion)} Saving writes your version under the password " +
            "you unlocked it with."
    }

/** Asked before locking a tab with unsaved changes. */
internal fun unsavedQuestion(name: String): String = "$name has unsaved changes."

/** Asked once before Lock All when [count] tabs have unsaved changes. */
internal fun lockAllQuestion(count: Int): String =
    if (count == 1) "1 encrypted file has unsaved changes." else "$count encrypted files have unsaved changes."

/** Said after Lock All when [count] tabs stayed unlocked because saving their changes failed. */
internal fun stayedUnlockedText(count: Int): String =
    if (count == 1) {
        "1 encrypted file stays unlocked: its changes could not be saved."
    } else {
        "$count encrypted files stay unlocked: their changes could not be saved."
    }

/**
 * The strip's message after a save failed, e.g. before a lock: [e]'s reason, never its content (the exceptions here
 * carry paths and fixed texts only).
 */
internal fun saveErrorText(
    name: String,
    e: IOException,
): String = "$name could not be saved. ${ioReason(e)}"

/** Shown in the strip when Change Password wrote the file but the view failed afterwards (e.g. its reload). */
internal fun changedButFailedText(e: IOException): String =
    "The password was changed and the file saved, but showing it again failed. ${ioReason(e)}"

private fun ioReason(e: IOException): String =
    when (e) {
        is NoSuchFileException -> "Its folder does not exist."
        is AccessDeniedException -> "Access denied."
        is FileSystemException -> e.reason ?: "The file system refused it."
        else -> e.message ?: "Unknown error."
    }

/** The message of the IOException a save throws when the Replace question was cancelled. */
internal const val REPLACE_CANCELLED = "Replacing the changed file was cancelled."

/** The strip's message when the idle lock did not save over a file that changed on disk (or is gone). */
internal const val IDLE_NOT_LOCKED = "Not locked: the file changed on disk. Save to lock."

internal const val CHANGED_ELSEWHERE = "Changed elsewhere with another password. Unlock again."
internal const val READ_FAILED = "Cannot read the file."
internal const val OUT_OF_MEMORY = "Not enough memory to unlock."
internal const val UNLOCK_FAILED = "Cannot unlock the file."
internal const val UNLOCKING = "Unlocking…"
internal const val LOCKED_MARKER_TEXT = "Encrypted, locked"
internal const val UNLOCKED_TEXT = "Encrypted · unlocked"
internal const val LOCK_TOOLTIP = "Lock (Shortcut+Shift+L)"

internal const val WRONG_CURRENT = "The current password is wrong."
internal const val NOT_UNLOCKED = "The file was locked meanwhile."
internal const val CHANGE_FAILED = "Cannot change the password."
internal const val CANNOT_REWRITE = "The content cannot be saved, so the password was not changed."
internal const val CHANGE_PASSWORD_TEXT = "Change Password…"
internal const val CHANGE_PASSWORD_TITLE = "Change Password"
internal const val CHANGE_PASSWORD_BUTTON = "Change Password"
internal const val CHANGING = "Changing…"
