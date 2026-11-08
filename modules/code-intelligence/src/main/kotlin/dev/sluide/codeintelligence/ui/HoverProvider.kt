package dev.sluide.codeintelligence.ui

import javafx.scene.Node

/**
 * One source of what the hover popup shows for a place in the text (ADR 0145): the diagnostics there, the language
 * server's hover. FX thread.
 */
fun interface HoverProvider {
    /**
     * Starts finding the sections for [offset] and calls [done] once on the FX thread with them; `null` or an empty
     * list is nothing. [done] may be called at once. Closing the handle means the answer is no longer wanted (the
     * mouse moved on, the text changed): the provider stops its work, and a late [done] is ignored anyway.
     */
    fun hover(
        offset: Int,
        done: (List<Node>?) -> Unit,
    ): AutoCloseable
}

/** The hover providers of one document binding, asked in the order added. FX thread. */
class HoverProviders {
    private val added = mutableListOf<HoverProvider>()

    /** Adds [provider] after the ones added before; closing the handle removes it. */
    fun add(provider: HoverProvider): AutoCloseable {
        added += provider
        return AutoCloseable { added -= provider }
    }

    /** The providers in the order added. */
    val providers: List<HoverProvider> get() = added.toList()
}
