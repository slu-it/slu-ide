package dev.sluide.databases.session

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private val log = System.getLogger(SessionListeners::class.java.name)

/**
 * The listeners of session `db#`[number]. Events are delivered one at a time, so Closed is the last a listener
 * gets: events after it are dropped. Listeners must not wait for the session (they run while others are held back).
 */
internal class SessionListeners(
    private val number: Int,
) {
    private val listeners = CopyOnWriteArrayList<(SessionEvent) -> Unit>()
    private val lock = ReentrantLock()
    private var closed = false

    fun add(listener: (SessionEvent) -> Unit) {
        listeners += listener
    }

    fun remove(listener: (SessionEvent) -> Unit) {
        listeners -= listener
    }

    fun emit(event: SessionEvent) =
        lock.withLock {
            if (closed) return
            if (event is SessionEvent.Closed) closed = true
            for (listener in listeners) {
                try {
                    listener(event)
                } catch (e: Exception) {
                    // The event itself may name the server (a Closed message); its type is enough.
                    log.log(
                        System.Logger.Level.ERROR,
                        "db#$number session listener failed on ${event::class.simpleName}",
                        e,
                    )
                }
            }
        }
}
