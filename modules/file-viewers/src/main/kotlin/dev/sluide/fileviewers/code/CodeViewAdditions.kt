package dev.sluide.fileviewers.code

import dev.sluide.fileviewers.StyledRange
import dev.sluide.fileviewers.ViewActivity
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.beans.value.ObservableValue
import javafx.collections.FXCollections
import javafx.collections.ListChangeListener
import javafx.collections.ObservableList
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import javafx.util.Subscription
import org.fxmisc.richtext.CodeArea
import org.fxmisc.richtext.model.StyleSpans

/**
 * What the extensions of a code view (ADR 0108) add to it beside gutter columns and menu items: [toolbar] items,
 * [banners], edit [locks], [overlay layers][layers] over the styles of [area] and [activities]. [ownToolbar]: the view
 * stands alone and shows the toolbar items itself, after its own [leading] controls; an embedding view places them in
 * its own toolbar. [lockChanged] is called when the first lock is taken and when the last one is closed. FX thread.
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

    val locks = EditLocks(lockChanged)
    val layers = OverlayLayers(area)
    val activities = ViewActivities()

    /** Shows [node] in the [banners] until the handle is closed. */
    fun addBanner(node: Node): AutoCloseable {
        banners.children += node
        return AutoCloseable { banners.children -= node }
    }

    /** Stops following the text and the added activities; closing the handles afterwards is harmless. */
    fun dispose() {
        layers.dispose()
        activities.dispose()
    }
}

/**
 * The toolbar items of a code view, in the order added; with [ownRow], shown in [row] at the top of the view after the
 * view's own [leading] controls (Soft Wrap, ADR 0130). The row always shows, without items too.
 */
internal class ToolbarItems(
    ownRow: Boolean,
    private val leading: List<Node> = emptyList(),
) {
    private val added = FXCollections.observableArrayList<Node>()

    /** The items, read-only. */
    val items: ObservableList<Node> = FXCollections.unmodifiableObservableList(added)

    /** The row showing the [items] in a view of its own; `null` in an embedded view. */
    val row: HBox? =
        if (!ownRow) {
            null
        } else {
            HBox().apply {
                styleClass += "code-editor-toolbar"
                // The toolbar's look, shared with the core's other views; its font comes from code-view.css.
                stylesheets +=
                    checkNotNull(
                        ToolbarItems::class.java.getResource("/dev/sluide/fileviewers/view-toolbar.css"),
                    ).toExternalForm()
                alignment = Pos.CENTER_LEFT
                children.setAll(leading + added)
                added.addListener(ListChangeListener { children.setAll(leading + added) })
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

/** Counts the locks taken on a view's text; calls [changed] when the first is taken and when the last is closed. */
internal class EditLocks(
    private val changed: () -> Unit,
) {
    private var count = 0

    /** Whether a lock is held, so the text must not change but by a reload. */
    val held: Boolean get() = count > 0

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
 * to styles the view sets anew (after highlighting, masks and find matches). Any change of the text clears them all.
 */
internal class OverlayLayers(
    private val area: CodeArea,
) {
    private val layers = LinkedHashMap<String, List<StyledRange>>()

    // The ranges no longer fit the text after a change; the edited text could also carry their classes on.
    private val clearing = area.plainTextChanges().subscribe { clear() }

    /** The ranges of all layers, in the order the layers were set. */
    private val ranges: List<StyledRange> get() = layers.values.flatten()

    private val classes: Set<String> get() =
        layers.values.flatMapTo(
            HashSet(),
        ) { ranges -> ranges.map { it.styleClass } }

    /** [spans] of the whole text with the layers' classes on their ranges, after the classes other styles have. */
    fun applyTo(spans: StyleSpans<Collection<String>>): StyleSpans<Collection<String>> =
        if (layers.isEmpty()) spans else withStyledRanges(spans, ranges, strip = classes)

    /**
     * Shows [ranges] (clamped to the text; empty ones dropped) as [layer] instead of its previous ones; none clears it.
     * Restyles only what differs, from the first to the last character the old or the new ranges touch.
     */
    fun set(
        layer: String,
        ranges: List<StyledRange>,
    ) {
        val length = area.length
        val wanted =
            ranges
                .map { StyledRange(it.start.coerceIn(0, length), it.end.coerceIn(0, length), it.styleClass) }
                .filter { it.start < it.end }
        val old = layers[layer].orEmpty()
        val stripped = classes + wanted.map { it.styleClass }
        if (wanted.isEmpty()) layers.remove(layer) else layers[layer] = wanted
        val touched = old + wanted
        if (touched.isEmpty()) return
        val from = touched.minOf { it.start }
        val to = minOf(touched.maxOf { it.end }, length)
        if (from >= to) return
        val styled = area.getStyleSpans(from, to)
        val changed = withStyledRanges(styled, this.ranges, from, stripped)
        val range = changedSpanRange(styled, changed) ?: return
        area.setStyleSpans(from + range.first, changed.subView(range.first, range.last + 1))
    }

    fun dispose() = clearing.unsubscribe()

    /** Removes every layer, its classes from all of the text, where an edit may have moved them. */
    private fun clear() {
        if (layers.isEmpty()) return
        val stripped = classes
        layers.clear()
        if (area.length == 0) return
        val styled = area.getStyleSpans(0, area.length)
        val bare = withStyledRanges(styled, emptyList(), strip = stripped)
        val range = changedSpanRange(styled, bare) ?: return
        area.setStyleSpans(range.first, bare.subView(range.first, range.last + 1))
    }
}
