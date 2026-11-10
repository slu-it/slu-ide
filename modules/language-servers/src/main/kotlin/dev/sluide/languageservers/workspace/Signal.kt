package dev.sluide.languageservers.workspace

import javafx.beans.InvalidationListener
import javafx.beans.Observable
import java.util.concurrent.CopyOnWriteArrayList

private val log = System.getLogger(Signal::class.java.name)

/** An [Observable] that tells its invalidation listeners each time it [fire]s; FX thread. */
internal class Signal : Observable {
    private val listeners = CopyOnWriteArrayList<InvalidationListener>()

    override fun addListener(listener: InvalidationListener) {
        listeners += listener
    }

    override fun removeListener(listener: InvalidationListener) {
        listeners -= listener
    }

    /** Calls every listener; one that throws is logged and the others still run. */
    fun fire() =
        listeners.forEach { listener ->
            try {
                listener.invalidated(this)
            } catch (e: RuntimeException) {
                log.log(System.Logger.Level.ERROR, "lsp: a workspace listener failed", e)
            }
        }
}
