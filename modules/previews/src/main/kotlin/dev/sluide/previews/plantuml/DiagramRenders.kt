package dev.sluide.previews.plantuml

import dev.sluide.plantuml.PlantUml
import dev.sluide.plantuml.Rendering
import java.util.concurrent.CompletableFuture

/**
 * The PlantUML renderings of one view (a `.puml` file's preview, a Markdown page's fences): each (source, first line)
 * is drawn by [render] in the background once, and kept, the [cacheSize] most recently used of them; more while the
 * page asks for more, since a key the page asks for is never dropped (it would be requested again, endlessly).
 *
 * A page asks [get] for each of its diagrams while it is built. A missing one is requested (once while it is pending),
 * and when it arrives [rerender] runs on the FX thread ([runLater]), so that the page is built again with it, but only
 * if the page asked for it: if its key is among those [get] was called with since the previous rerender began (since
 * creation before the first). A page built again forgets what earlier pages asked for, so a result for a text that was
 * edited away in the meantime is kept without a pointless reload. Pages built for other reasons (a save) in between add
 * their keys to the set: a result for a text saved over before it arrived still causes one reload, which shows the
 * page of the newer text again.
 *
 * All calls are on the FX thread; [render]'s futures complete on any thread. After [detach] a result is dropped.
 */
internal class DiagramRenders(
    private val render: (source: String, firstLine: Int) -> CompletableFuture<Rendering>,
    private val rerender: () -> Unit,
    private val runLater: (Runnable) -> Unit,
    private val cacheSize: Int = 64,
) {
    /** With [plantUml]'s renders; the pages pass `Platform::runLater`. */
    constructor(plantUml: PlantUml, rerender: () -> Unit, runLater: (Runnable) -> Unit) :
        this(plantUml::render, rerender, runLater)

    private data class Key(
        val source: String,
        val firstLine: Int,
    )

    /** In the order of use, the least recently used first. */
    private val cache = LinkedHashMap<Key, Rendering>(16, 0.75f, true)

    private val pending = HashSet<Key>()

    /** The keys [get] was asked for since the previous rerender began. */
    private val asked = HashSet<Key>()

    private var detached = false

    /**
     * FX thread. The kept rendering of [source] whose first line is [firstLine] in the caller's numbering, or null: then
     * it is requested (once while pending) and [rerender] runs on the FX thread when it arrives.
     */
    fun get(
        source: String,
        firstLine: Int,
    ): Rendering? {
        val key = Key(source, firstLine)
        asked += key
        cache[key]?.let { return it }
        if (pending.add(key)) render(source, firstLine).thenAccept { rendering -> runLater { arrived(key, rendering) } }
        return null
    }

    private fun arrived(
        key: Key,
        rendering: Rendering,
    ) {
        pending -= key
        if (detached) return
        cache[key] = rendering
        // Never a key the page asks for: it would be requested again and evict another, endlessly.
        val keys = cache.keys.iterator()
        while (cache.size > cacheSize && keys.hasNext()) if (keys.next() !in asked) keys.remove()
        if (key in asked) {
            // Before the rerender: what the new page asks for is the set the next result is checked against.
            asked.clear()
            rerender()
        }
    }

    /**
     * FX thread. Drops the results of pending requests (the engine cannot cancel them, they are drawn anyway); no
     * rerender afterwards.
     */
    fun detach() {
        detached = true
    }
}
