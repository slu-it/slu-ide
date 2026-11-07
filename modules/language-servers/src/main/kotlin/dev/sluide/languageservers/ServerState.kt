package dev.sluide.languageservers

/** Where a language server of a window stands. */
sealed interface ServerState {
    /** Not started yet, or stopped with its window. */
    data object Stopped : ServerState

    /** Starting: waiting for the project trust, the process or the answer to `initialize`. */
    data object Starting : ServerState

    /** Initialized: requests and notifications reach it. */
    data object Running : ServerState

    /** Crashed and starts again: restart number [attempt] (from 1), after a pause. */
    data class Restarting(
        val attempt: Int,
    ) : ServerState

    /** Crashed after the last restart, or could not start; only a restart by the user starts it again. */
    data class Failed(
        val message: String,
    ) : ServerState

    /** Not available: the spec has no command (not installed). */
    data object Unavailable : ServerState

    /** Runs project code and the project trust does not allow it. */
    data object Untrusted : ServerState
}
