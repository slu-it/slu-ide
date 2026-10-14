package dev.sluide.fileviewers

import dev.sluide.textediting.SearchOptions
import dev.sluide.textediting.TextSearch
import javafx.animation.Animation
import javafx.animation.PauseTransition
import javafx.css.PseudoClass
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.control.ToggleButton
import javafx.scene.control.Tooltip
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyEvent
import javafx.scene.layout.HBox
import javafx.scene.layout.Pane
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import org.fxmisc.richtext.CodeArea
import org.fxmisc.richtext.model.StyleSpans
import org.fxmisc.richtext.model.StyleSpansBuilder
import org.fxmisc.wellbehaved.event.EventPattern.keyPressed
import org.fxmisc.wellbehaved.event.InputMap
import org.kordamp.ikonli.Ikon
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignA
import org.kordamp.ikonli.materialdesign2.MaterialDesignC
import org.reactfx.Subscription
import java.time.Duration
import javafx.util.Duration as FxDuration

/**
 * The find bar of a code view's [area], shown at the top of [container] while open: a query with options (match
 * case, whole words, regular expression), previous / next and a counter of the matches ("3 of 12"). Its [keys]
 * open it from the area (Shortcut+F) and go to the next or previous match (F3, Shift+F3).
 *
 * While open, the matches (at most [MAX_MARKED]) are styled `search-match`, the selected one also
 * `search-match-current`, over the token styles: [overlay] adds them to the styles a highlighting pass applies, and
 * they follow edits of the text once typing pauses. Going to a match selects it and scrolls to it; with more matches
 * than are marked, next and previous wrap around within the marked ones.
 *
 * Searching runs on the JavaFX thread within [TextSearch]'s time budget; a search that ran out of time says so and is
 * not repeated while typing, only when the query or the options change. In large texts the query is searched once
 * typing it pauses.
 */
internal class FindBar(
    private val area: CodeArea,
    private val container: Pane,
) {
    val query = TextField().apply { styleClass += "find-query" }
    val matchCase = optionButton("Aa", "Match case")
    val wholeWord = optionButton("W", "Whole words")
    val regex = optionButton(".*", "Regular expression")

    /** The number of matches and which is selected, or why the query is invalid. */
    val counter = Label().apply { styleClass += "find-counter" }

    val node =
        VBox(
            HBox(
                query,
                matchCase,
                wholeWord,
                regex,
                counter,
                Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                iconButton(MaterialDesignA.ARROW_UP, "Previous match (Shift+F3)", ::previous),
                iconButton(MaterialDesignA.ARROW_DOWN, "Next match (F3)", ::next),
                iconButton(MaterialDesignC.CLOSE, "Close (Escape)", ::close),
            ).apply {
                styleClass += "find-row"
                alignment = Pos.CENTER_LEFT
            },
        ).apply { styleClass += "find-bar" }

    /** The matches of the query in the text, as far as they [are current][stale]. */
    var matches: List<IntRange> = emptyList()
        private set

    /** The index of the match that is selected in the area, if one is. */
    var current: Int? = null
        private set

    private var error: String? = null

    /** Whether there are more matches than [matches] holds. */
    private var truncated = false

    /** Searches the query once typing it pauses, in large texts. */
    private val queryPause = PauseTransition(QUERY_PAUSE).apply { setOnFinished { search() } }

    /** Whether the text changed since [matches] were found. */
    private var stale = false

    /** Finds the matches again once typing pauses, while the bar is open. */
    private var following: Subscription? = null

    val isOpen: Boolean get() = node in container.children

    /** Shortcut+F, F3 and Shift+F3 in the area, and Escape while the bar is open. */
    val keys: InputMap<KeyEvent> =
        InputMap.sequence(
            InputMap.consume(keyPressed(KeyCode.F, KeyCombination.SHORTCUT_DOWN)) { open() },
            InputMap.consume(keyPressed(KeyCode.F3)) { next(focus = false) },
            InputMap.consume(keyPressed(KeyCode.F3, KeyCombination.SHIFT_DOWN)) { previous(focus = false) },
            InputMap.consumeWhen(keyPressed(KeyCode.ESCAPE), { isOpen }) { close() },
        )

    init {
        query.textProperty().subscribe { _ -> if (area.length > LARGE_TEXT) queryPause.playFromStart() else search() }
        for (option in listOf(matchCase, wholeWord, regex)) option.selectedProperty().subscribe { _ -> search() }
        // Filters: the fields' own handlers would otherwise take Enter and Escape.
        node.addEventFilter(KeyEvent.KEY_PRESSED) { event ->
            when {
                event.code == KeyCode.ENTER && event.target == query -> if (event.isShiftDown) previous() else next()
                event.code == KeyCode.ESCAPE -> close()
                event.code == KeyCode.F3 && event.isShiftDown -> previous()
                event.code == KeyCode.F3 -> next()
                FIND.match(event) -> focusQuery()
                else -> return@addEventFilter
            }
            event.consume()
        }
        area.selectionProperty().subscribe { _ -> if (isOpen && !stale) markCurrent() }
    }

    /**
     * Shows the bar, with the focus in the query if [focus]. A selection within one line becomes the query, and the
     * match there is selected.
     */
    fun open(focus: Boolean = true) {
        val selected = area.selectedText
        if (selected.isNotEmpty() && '\n' !in selected) query.text = selected
        if (!isOpen) {
            container.children.add(0, node)
            following =
                Subscription.multi(
                    area.plainTextChanges().subscribe { stale = true },
                    area.multiPlainChanges().successionEnds(FOLLOW_PAUSE).subscribe {
                        if (stale) {
                            refresh()
                            restyle()
                        }
                    },
                )
            search()
        }
        if (focus) focusQuery()
    }

    /** Hides the bar and its highlighted matches; the focus goes back to the text. */
    fun close() {
        if (!isOpen) return
        container.children -= node
        following?.unsubscribe()
        following = null
        queryPause.stop()
        matches = emptyList()
        current = null
        restyle()
        area.requestFocus()
    }

    /**
     * Selects the next match after the selection, the first after the last; opens the bar if it is closed, with the
     * focus in the query if [focus].
     */
    fun next(focus: Boolean = true) {
        prepare(focus)
        select(TextSearch.next(matches, area.selection.end))
    }

    /**
     * Selects the match before the selection, the last before the first; opens the bar if it is closed, with the
     * focus in the query if [focus].
     */
    fun previous(focus: Boolean = true) {
        prepare(focus)
        select(TextSearch.previous(matches, area.selection.start))
    }

    /** Opens the bar if needed and brings the matches up to date, also for a query still waiting to be searched. */
    private fun prepare(focus: Boolean) {
        if (!isOpen) open(focus = focus)
        if (queryPause.status == Animation.Status.RUNNING) {
            queryPause.stop()
            search()
        }
        refresh()
    }

    /** Stops following the text, e.g. when its view is closed. */
    fun dispose() {
        following?.unsubscribe()
        following = null
    }

    /**
     * [spans], the token styles of the area's current text, with the matches added while the bar is open. Finds them
     * again first if the text changed since.
     */
    fun overlay(spans: StyleSpans<Collection<String>>): StyleSpans<Collection<String>> {
        if (!isOpen) return spans
        refresh()
        return withMatches(spans)
    }

    /** Finds the matches of the query and selects the first at or after the selection, as the query is typed. */
    private fun search() {
        if (!isOpen) return
        find()
        select(TextSearch.next(matches, area.selection.start))
        restyle()
    }

    private fun find() {
        val result = TextSearch.find(area.text, query.text, options, limit = MAX_MARKED)
        matches = result.matches
        error = result.error
        truncated = result.truncated
        stale = false
        current = currentIndex()
        showCounter()
    }

    /** Finds the matches again if the text changed, unless the last search ran out of time. */
    private fun refresh() {
        if (!stale) return
        if (error == TextSearch.TOO_LONG) stale = false else find()
    }

    private val options: SearchOptions
        get() = SearchOptions(matchCase.isSelected, wholeWord.isSelected, regex.isSelected)

    /** Selects the match at [index] and scrolls to it; the selection's listener marks it as the current one. */
    private fun select(index: Int?) {
        if (index == null) return
        val match = matches[index]
        area.selectRange(match.first, match.last + 1)
        area.requestFollowCaret()
    }

    /** Marks the match the area selects as the current one, if it changed; restyles only the two matches. */
    private fun markCurrent() {
        val index = currentIndex()
        val old = current
        if (index == old) return
        current = index
        showCounter()
        old?.let(::restyleMatch)
        index?.let(::restyleMatch)
    }

    private fun currentIndex(): Int? {
        val selection = area.selection
        val index = matches.binarySearchBy(selection.start) { it.first }
        return index.takeIf { it >= 0 && matches[it].last + 1 == selection.end }
    }

    private fun showCounter() {
        val count = matches.size
        val total = if (truncated) "$count+" else "$count"
        val error = error
        counter.text =
            when {
                error != null -> error
                query.text.isEmpty() -> ""
                count == 0 -> "No results"
                current != null -> "${checkNotNull(current) + 1} of $total"
                count == 1 && !truncated -> "1 result"
                else -> "$total results"
            }
        counter.pseudoClassStateChanged(ERROR, error != null || (count == 0 && query.text.isNotEmpty()))
    }

    private fun focusQuery() {
        query.requestFocus()
        query.selectAll()
    }

    /** Shows the current [matches] in the area: removes the old match styles and adds the new ones. */
    private fun restyle() {
        if (area.length == 0) return
        val styled = area.getStyleSpans(0, area.length)
        val wanted = withMatches(styled.mapStyles { style -> style.filterNot { it in MATCH_CLASSES } })
        val range = changedSpanRange(styled, wanted) ?: return
        area.setStyleSpans(range.first, wanted.subView(range.first, range.last + 1))
    }

    /** Gives the match at [index] the style of a match, or of the current one, over its token styles. */
    private fun restyleMatch(index: Int) {
        val match = matches[index]
        val classes = if (index == current) CURRENT_MATCH else MATCH
        val styled = area.getStyleSpans(match.first, match.last + 1)
        area.setStyleSpans(match.first, styled.mapStyles { style -> style.filterNot { it in MATCH_CLASSES } + classes })
    }

    /** [spans] with the match styles added. */
    private fun withMatches(spans: StyleSpans<Collection<String>>): StyleSpans<Collection<String>> {
        if (matches.isEmpty()) return spans
        val builder = StyleSpansBuilder<Collection<String>>()
        var position = 0
        matches.forEachIndexed { index, match ->
            if (match.first > position) builder.add(emptyList(), match.first - position)
            builder.add(if (index == current) CURRENT_MATCH else MATCH, match.last + 1 - match.first)
            position = match.last + 1
        }
        if (spans.length() > position) builder.add(emptyList(), spans.length() - position)
        return spans.overlay(builder.create()) { token, search -> if (search.isEmpty()) token else token + search }
    }

    private fun optionButton(
        text: String,
        tooltip: String,
    ) = ToggleButton(text).apply {
        styleClass += "find-option"
        this.tooltip = Tooltip(tooltip)
        isFocusTraversable = false
    }

    private fun iconButton(
        icon: Ikon,
        tooltip: String,
        action: () -> Unit,
    ) = Button(null, FontIcon(icon)).apply {
        styleClass += "find-button"
        this.tooltip = Tooltip(tooltip)
        isFocusTraversable = false
        setOnAction { action() }
    }
}

/** Shortcut+F, also in the bar: focuses the query again. */
private val FIND = KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN)

private val MATCH = listOf("search-match")
private val CURRENT_MATCH = listOf("search-match", "search-match-current")
private val MATCH_CLASSES = CURRENT_MATCH.toSet()

/** The counter shows an error or that nothing matches. */
private val ERROR = PseudoClass.getPseudoClass("error")

/** How long typing pauses before the matches are found again in the changed text. */
private val FOLLOW_PAUSE = Duration.ofMillis(150)

/** How long typing the query pauses before it is searched in a large text. */
private val QUERY_PAUSE = FxDuration.millis(300.0)

/** Texts above this length (about 1 MiB) are searched once typing the query pauses. */
private const val LARGE_TEXT = 1 shl 20

/** At most this many matches are marked and counted ("10000+"). */
internal const val MAX_MARKED = 10_000
