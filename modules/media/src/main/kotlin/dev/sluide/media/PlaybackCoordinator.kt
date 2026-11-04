package dev.sluide.media

import org.springframework.stereotype.Component

/**
 * One playback at a time, app-wide (all windows, ADR 0123): starting one pauses the one started before, whether a media
 * [Playback] or another [Pausable] (a Read Aloud reading, ADR 0124). Holds at most one reference, the last started
 * playback, until it is disposed. FX thread only.
 */
@Component
class PlaybackCoordinator {
    private var current: Pausable? = null

    /**
     * [playback] started playing: pauses the previously started one if it is another. Whatever its status: pausing a
     * paused, ended or failed playback does nothing, and one still loading would start once loaded.
     */
    fun started(playback: Pausable) {
        current?.takeIf { it !== playback }?.pause()
        current = playback
    }

    /** [playback] is gone: forgets it if it was the last started one. */
    fun disposed(playback: Pausable) {
        if (current === playback) current = null
    }
}
