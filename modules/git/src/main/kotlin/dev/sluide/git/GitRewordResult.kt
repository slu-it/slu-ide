package dev.sluide.git

/** What changing the message of a commit gave ([GitRewords.reword], ADR 0100). */
sealed interface GitRewordResult {
    /** The message was changed and the branch moved; [head] is its new `HEAD`, a full hash. */
    data class Done(
        val head: String,
    ) : GitRewordResult

    /** The cleaned-up message equals the old one: nothing was written. */
    data object Unchanged : GitRewordResult

    /** Nothing was changed (or git was not run), with the [message] why; the branch is where it was. */
    data class Failed(
        val message: String,
    ) : GitRewordResult
}
