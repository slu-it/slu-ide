package dev.sluide.git

/**
 * What a git command that changes the repository gave: whether it [succeeded][success], and git's own [message].
 * [changed] tells that the change was made although git failed afterwards, e.g. a branch was checked out but a
 * post-checkout hook failed.
 */
data class GitResult(
    val success: Boolean,
    val message: String,
    val changed: Boolean = success,
)
