package dev.sluide.docker

import dev.sluide.docker.model.EngineInfo

/** Whether the window's [DockerSession] reaches an engine. */
sealed interface EngineState {
    /** Nothing asked yet: no subscription, no refresh. */
    data object Idle : EngineState

    /** Looking the executable up or reaching the engine for the first time (or again after Retry). */
    data object Checking : EngineState

    /** The engine answers; the lists are current. */
    data class Available(
        val engine: EngineInfo,
    ) : EngineState

    /**
     * The CLI is there but failed, e.g. the engine is not running; [message] is the CLI's error. Retried while
     * subscribed.
     */
    data class Unavailable(
        val message: String,
    ) : EngineState

    /** The executable of the settings was not found; [message] says what to do. Tried again by `refresh()`. */
    data class NotFound(
        val message: String,
    ) : EngineState
}
