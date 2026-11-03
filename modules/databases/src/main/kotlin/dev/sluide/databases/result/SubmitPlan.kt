package dev.sluide.databases.result

import dev.sluide.databases.connect.CommitMode

/**
 * How a submit of grid edits runs (ADR 0121, "Editing"). The delete question is asked on the FX thread, the submit
 * runs later on the session's thread: [decide] looks at the session there again, so deletes are never committed at
 * once without the question.
 */
sealed interface SubmitPlan {
    /** Runs the statements; [ownTransaction]: they are committed at once (Auto mode, no transaction of the user). */
    data class Run(
        val ownTransaction: Boolean,
    ) : SubmitPlan

    /** Runs nothing: the session changed since the question was decided. */
    data class Refused(
        val reason: String,
    ) : SubmitPlan

    companion object {
        const val TRANSACTION_ENDED = "The transaction ended; submit again"

        /** Auto mode without a transaction the user opened: a submit is committed at once. */
        fun commitsAtOnce(
            mode: CommitMode,
            pendingTransaction: Boolean,
        ): Boolean = mode == CommitMode.AUTO && !pendingTransaction

        /** Whether to ask before submitting: [deletes] would be committed at once, which is permanent. */
        fun asks(
            mode: CommitMode,
            pendingTransaction: Boolean,
            deletes: Int,
        ): Boolean = deletes > 0 && commitsAtOnce(mode, pendingTransaction)

        /**
         * On the session's thread, just before running: [asked] tells whether the deletes were confirmed. Deletes that
         * would now be committed at once without that (the user's transaction ended after the check) are refused.
         */
        fun decide(
            mode: CommitMode,
            pendingTransaction: Boolean,
            deletes: Int,
            asked: Boolean,
        ): SubmitPlan {
            val atOnce = commitsAtOnce(mode, pendingTransaction)
            return if (atOnce && deletes > 0 && !asked) Refused(TRANSACTION_ENDED) else Run(atOnce)
        }
    }
}
