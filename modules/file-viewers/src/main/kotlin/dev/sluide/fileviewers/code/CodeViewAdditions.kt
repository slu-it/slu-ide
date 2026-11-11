package dev.sluide.fileviewers.code

import dev.sluide.fileviewers.StyledRange
import dev.sluide.fileviewers.TextChange
import dev.sluide.fileviewers.ViewActivity
import dev.sluide.fileviewers.WrappingToolbar
import javafx.beans.property.ReadOnlyBooleanWrapper
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.beans.value.ObservableBooleanValue
import javafx.beans.value.ObservableValue
import javafx.collections.FXCollections
import javafx.collections.ListChangeListener
import javafx.collections.ObservableList
import javafx.scene.Node
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import javafx.util.Subscription
import org.fxmisc.richtext.CodeArea
import org.fxmisc.richtext.model.StyleSpans

private val log = System.getLogger(OverlayLayers::class.java.name)

/**
 * What the extensions of a code view (ADR 0108) add to it beside gutter columns and menu items: [toolbar] items,
 * [banners], [statusBar] items, edit [locks], [overlay layers][layers] over the styles of [area], [textChanges] for
 * listeners and [activities]. [ownToolbar]: the view stands alone and shows the toolbar items itself, after its own [leading]
 * controls; an embedding view places them in its own toolbar. [lockChanged] is called when the first lock is taken and
 * when the last one is closed. FX thread.
 */
internal class CodeViewAdditions(
    area: CodeArea,
    ownToolbar: Boolean,
    leading: List<Node>,
    lockChanged: () -> Unit,
) {
    val toolbar = ToolbarItems(ownToolbar, leading)

    /** The extensions' banners, below the find bar and the view's own banners. */
    val banners = VBox()

    /** The extensions' status items, in a bar below the text; hidden while it has none. */
    val statusBar = StatusBar()

    val locks = EditLocks(lockChanged)
    val layers = OverlayLayers(area)

    /** The changes of the text, which the [layers] take before the extensions' listeners get them. */
    val textChanges = TextChanges(area, layers::edited)
    val activities = ViewActivities()

    /** Shows [node] in the [banners] until the handle is closed. */
    fun addBanner(node: Node): AutoCloseable {
        banners.children += node
        return AutoCloseable { banners.children -= node }
    }

    /** Shows [node] in the [statusBar] after the items added before it until the handle is closed. */
    fun addStatusItem(node: Node): AutoCloseable = statusBar.add(node)

    /** Stops following the text and the added activities; closing the handles afterwards is harmless. */
    fun dispose() {
        textChanges.dispose()
        activities.dispose()
    }
}

/** The bar below a code view's text with the status items of its extensions; it takes no space while empty. */
internal class StatusBar : HBox() {
    init {
        styleClass += "code-status-bar"
        isFocusTraversable = false
        isVisible = false
        isManaged = false
        children.addListener(
            ListChangeListener {
                val any = children.isNotEmpty()
                isVisible = any
                isManaged = any
            },
        )
    }

    /** Adds [node] after the items added before; closing the handle removes it, a second close does nothing. */
    fun add(node: Node): AutoCloseable {
        children += node
        var open = true
        return AutoCloseable {
            if (open) {
                open = false
                children -= node
            }
        }
    }
}

/**
 * The toolbar items of a code view, in the order added; with [ownRow], shown in [row] at the top of the view after the
 * view's [own] controls (Soft Wrap, ADR 0130). The row always shows, without items too.
 */
internal class ToolbarItems(
    ownRow: Boolean,
    private val own: List<Node> = emptyList(),
) {
    private val added = FXCollections.observableArrayList<Node>()

    /** The items, read-only. */
    val items: ObservableList<Node> = FXCollections.unmodifiableObservableList(added)

    /** The row showing the [items] in a view of its own; `null` in an embedded view. */
    val row: WrappingToolbar? =
        if (!ownRow) {
            null
        } else {
            WrappingToolbar().apply {
                styleClass += "code-editor-toolbar"
                // The toolbar's look, shared with the core's other views; its font comes from code-view.css.
                stylesheets +=
                    checkNotNull(
                        ToolbarItems::class.java.getResource("/dev/sluide/fileviewers/view-toolbar.css"),
                    ).toExternalForm()
                leading.setAll(own + added)
                added.addListener(ListChangeListener { leading.setAll(own + added) })
            }
        }

    /** Adds [nodes] after the items added before; closing the handle removes them. */
    fun add(nodes: List<Node>): AutoCloseable {
        val adding = nodes.toList()
        added += adding
        var open = true
        return AutoCloseable {
            if (open) {
                open = false
                added.removeAll(adding.toSet())
            }
        }
    }
}

/**
 * Counts the locks taken on a view's text; calls [changed] when the first is taken and when the last is closed. The
 * read-only ones ([readOnlyLock]) are counted among them and also refuse Save and mark the view ([readOnlyMarked]).
 */
internal class EditLocks(
    private val changed: () -> Unit,
) {
    private var count = 0
    private var readOnlyCount = 0
    private val marked = ReadOnlyBooleanWrapper(false)

    /** Whether a lock is held, so the text must not change but by a reload. */
    val held: Boolean get() = count > 0

    /** Whether a read-only lock is held, so that Save does nothing. */
    val readOnlyHeld: Boolean get() = readOnlyCount > 0

    /** [readOnlyHeld] as it changes. */
    val readOnlyMarked: ObservableBooleanValue = marked.readOnlyProperty

    /** Takes a lock; closing it a second time does nothing. */
    fun lock(): AutoCloseable {
        if (count++ == 0) changed()
        var open = true
        return AutoCloseable {
            if (open) {
                open = false
                if (--count == 0) changed()
            }
        }
    }

    /** Takes a lock that also refuses Save and sets [readOnlyMarked]; closing it a second time does nothing. */
    fun readOnlyLock(): AutoCloseable {
        val lock = lock()
        if (readOnlyCount++ == 0) marked.set(true)
        var open = true
        return AutoCloseable {
            if (open) {
                open = false
                if (--readOnlyCount == 0) marked.set(false)
                lock.close()
            }
        }
    }
}

/** Combines the activities added to a view into its [activity]. */
internal class ViewActivities {
    private val added = mutableListOf<ObservableValue<ViewActivity>>()
    private val subscriptions = mutableListOf<Subscription>()
    private val combined = ReadOnlyObjectWrapper(ViewActivity.NONE)
    private var disposed = false

    /** [combinedActivity] of the activities added and not unsubscribed. */
    val activity: ObservableValue<ViewActivity> = combined.readOnlyProperty

    /** Takes [activity] into the combined one until unsubscribed; after [dispose], ignores it. */
    fun add(activity: ObservableValue<ViewActivity>): Subscription {
        if (disposed) return Subscription.EMPTY
        added += activity
        val listening = activity.subscribe { _ -> update() }
        subscriptions += listening
        return Subscription {
            if (subscriptions.remove(listening)) {
                listening.unsubscribe()
                added.remove(activity)
                update()
            }
        }
    }

    /** Stops listening to the activities, which may outlive the view. */
    fun dispose() {
        disposed = true
        subscriptions.forEach(Subscription::unsubscribe)
        subscriptions.clear()
        added.clear()
    }

    private fun update() = combined.set(combinedActivity(added.map { it.value }))
}

/** RUNNING if any of [activities] is, else FAILED if any is, else NONE. */
internal fun combinedActivity(activities: List<ViewActivity>): ViewActivity =
    when {
        ViewActivity.RUNNING in activities -> ViewActivity.RUNNING
        ViewActivity.FAILED in activities -> ViewActivity.FAILED
        else -> ViewActivity.NONE
    }

/**
 * Named layers of style classes over the styles of [area]: [set] replaces a layer's ranges, [applyTo] adds all layers
 * to styles the view sets anew (after highlighting, masks and find matches). A change of the text ([edited]) clears
 * every layer but those that follow edits, whose ranges move with the text ([followChanges], ADR 0141). Listeners
 * ([addListener]) are told every layer whose ranges changed.
 */
internal class OverlayLayers(
    private val area: CodeArea,
) {
    private val layers = LinkedHashMap<String, LayerRanges>()
    private val listeners = mutableListOf<(String, List<StyledRange>) -> Unit>()

    /**
     * Calls [listener] after every change of a layer's ranges, with the layer and its ranges (clamped to the text; none
     * when it was cleared, also by an edit), until unsubscribed.
     */
    fun addListener(listener: (layer: String, ranges: List<StyledRange>) -> Unit): Subscription {
        // A wrapper of its own, so that the same listener added twice is removed once per subscription.
        val added: (String, List<StyledRange>) -> Unit = { layer, ranges -> listener(layer, ranges) }
        listeners += added
        return Subscription { listeners.remove(added) }
    }

    /** The ranges of all layers, in the order the layers were set. */
    private val ranges: List<StyledRange> get() = layers.values.flatMap { it.ranges }

    private val classes: Set<String> get() =
        layers.values.flatMapTo(
            HashSet(),
        ) { layer -> layer.ranges.map { it.styleClass } }

    /** [spans] of the whole text with the layers' classes on their ranges, after the classes other styles have. */
    fun applyTo(spans: StyleSpans<Collection<String>>): StyleSpans<Collection<String>> =
        if (layers.isEmpty()) spans else withStyledRanges(spans, ranges, strip = classes)

    /**
     * Shows [ranges] (clamped to the text; empty ones dropped) as [layer] instead of its previous ones; none clears it.
     * With [following], the layer follows edits ([edited]) until it is set again. Restyles only what differs, from the
     * first to the last character the old or the new ranges touch.
     */
    fun set(
        layer: String,
        ranges: List<StyledRange>,
        following: Boolean = false,
    ) {
        val length = area.length
        val wanted =
            ranges
                .map { StyledRange(it.start.coerceIn(0, length), it.end.coerceIn(0, length), it.styleClass) }
                .filter { it.start < it.end }
        val old = layers[layer]?.ranges.orEmpty()
        val stripped = classes + wanted.map { it.styleClass }
        if (wanted.isEmpty()) layers.remove(layer) else layers[layer] = LayerRanges(wanted, following)
        val touched = old + wanted
        if (touched.isEmpty()) return
        restyle(touched.minOf { it.start }, touched.maxOf { it.end }, stripped)
        if (old != wanted) tell(layer, wanted)
    }

    /**
     * Takes [changes] of the text, made together (one change, or a multi-change such as Replace All, each in the text
     * the one before left), into the layers: clears those that do not follow edits and moves the ranges of the others
     * ([followRange]). Then restyles once, where the classes of dropped ranges went with the text and over the
     * inserted texts, which may have taken on the classes before them.
     */
    fun edited(changes: List<TextChange>) {
        if (layers.isEmpty()) return
        val stripped = classes
        val before = LinkedHashMap(layers)
        val followed = followChanges(layers, changes)
        layers.clear()
        layers.putAll(followed.layers)
        followed.restyle?.let { restyle(it.first, it.last + 1, stripped) }
        for ((layer, old) in before) {
            val now = layers[layer]?.ranges.orEmpty()
            if (now != old.ranges) tell(layer, now)
        }
    }

    private fun tell(
        layer: String,
        ranges: List<StyledRange>,
    ) {
        for (listener in listeners.toList()) {
            try {
                listener(layer, ranges)
            } catch (e: Exception) {
                log.log(System.Logger.Level.ERROR, "A listener of a code editor's overlay layers failed", e)
            }
        }
    }

    /** Sets the styles of [start, end) (clamped to the text) anew: without the classes [stripped], with the layers'. */
    private fun restyle(
        start: Int,
        end: Int,
        stripped: Set<String>,
    ) {
        val from = start.coerceIn(0, area.length)
        val to = end.coerceIn(from, area.length)
        if (from >= to) return
        val styled = area.getStyleSpans(from, to)
        val changed = withStyledRanges(styled, ranges, from, stripped)
        val range = changedSpanRange(styled, changed) ?: return
        area.restyleKeepingView(from + range.first, changed.subView(range.first, range.last + 1))
    }
}
