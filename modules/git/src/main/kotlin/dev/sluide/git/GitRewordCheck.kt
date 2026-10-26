package dev.sluide.git

/** Whether the message of a commit can be changed, as [GitRewords.check] reads it (ADR 0100). */
sealed interface GitRewordCheck {
    /**
     * It can: [message] is the commit's full current message (as `%B`, UTF-8, without trailing newlines);
     * [otherBranches] are the short names of the other local branches that contain the commit and keep the old one;
     * [pushedTo] is the short name of the current branch's upstream (`origin/feature`) if it contains the commit, else
     * `null`.
     */
    data class Allowed(
        val message: String,
        val otherBranches: List<String>,
        val pushedTo: String?,
    ) : GitRewordCheck

    /** It cannot, for this [reason]. */
    data class Refused(
        val reason: String,
    ) : GitRewordCheck
}
