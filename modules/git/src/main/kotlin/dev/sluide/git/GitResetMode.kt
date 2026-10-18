package dev.sluide.git

import java.nio.file.Files

/** How `git reset` moves the current branch (or a detached `HEAD`). */
enum class GitResetMode(
    /** The option: `--soft`. */
    val option: String,
) {
    /** Index and files stay; the differences are staged. */
    SOFT("--soft"),

    /** Files stay; the index is reset, the differences are not staged. */
    MIXED("--mixed"),

    /** Index and tracked files are reset; their local changes are lost. */
    HARD("--hard"),
}
