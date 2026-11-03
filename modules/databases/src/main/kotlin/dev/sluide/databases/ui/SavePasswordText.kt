package dev.sluide.databases.ui

/** The "Save password" checkbox label; [reason] is why saving is not up to the user (the box is then disabled). */
internal fun savePasswordLabel(reason: String?): String = reason?.let { "Save password ($it)" } ?: "Save password"

/** The hint under the connect prompt's password field. */
internal const val PASSWORD_PROMPT_HINT = "Kept in memory until Thoreau quits; saved encrypted when checked."

/** The prompt text of the connection dialog's password field. */
internal fun passwordPromptText(saved: Boolean): String =
    if (saved) "Saved; type to replace" else "Asked on connect when empty"
