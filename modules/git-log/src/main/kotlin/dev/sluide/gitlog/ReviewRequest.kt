package dev.sluide.gitlog

import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination

/** The key of Git → Review Branch, also named in the header button's tooltip. */
val REVIEW_BRANCH_KEYS: KeyCombination =
    KeyCodeCombination(KeyCode.R, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN)

/** What the user asked to review from the git log (ADR 0168); `ide` opens the review window for it. */
sealed interface ReviewRequest {
    /** A branch against its base: [ref] is a full refname (`refs/heads/x`, `refs/remotes/origin/x`) or `HEAD`. */
    data class Branch(
        val ref: String,
    ) : ReviewRequest

    /**
     * The selected commits, full hashes in table order (newest first); the review spans from the oldest's parent to
     * the newest.
     */
    data class Commits(
        val hashes: List<String>,
    ) : ReviewRequest
}
