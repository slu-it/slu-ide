package dev.sluide.gitlog.filter

import dev.sluide.git.GitLogFilter
import dev.sluide.git.GitLogOrder
import javafx.animation.PauseTransition
import javafx.application.Platform
import javafx.css.PseudoClass
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.CheckMenuItem
import javafx.scene.control.MenuButton
import javafx.scene.control.RadioMenuItem
import javafx.scene.control.SeparatorMenuItem
import javafx.scene.control.TextField
import javafx.scene.control.TextInputControl
import javafx.scene.control.ToggleButton
import javafx.scene.control.ToggleGroup
import javafx.scene.control.Tooltip
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseEvent
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.util.Duration
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignA
import org.kordamp.ikonli.materialdesign2.MaterialDesignC
import org.kordamp.ikonli.materialdesign2.MaterialDesignF
import org.kordamp.ikonli.materialdesign2.MaterialDesignM
import org.kordamp.ikonli.materialdesign2.MaterialDesignS

/**
 * The filters above the commit table (ADR 0039), as in IntelliJ: after the [branchesToggle] of the branches pane
 * (ADR 0040), a text or hash with Regex and Match Case toggles,
 * the [branch], [user] and [path] buttons with their popups and clear buttons, and the graph options. Every change
 * goes to [onChange] as the whole [filter]: a typed text 300 ms after the last keystroke or on Enter, anything else
 * right away. [reset] goes back to the defaults without telling.
 *
 * The items of the popups come from [branchItems], [userItems] and [pathItems] each time a popup is shown;
 * [userShowing] and [pathShowing] are told before, so that their items can be read.
 */
internal class FilterBar(
    branchItems: () -> List<FilterItem>,
    userItems: () -> List<FilterItem>,
    pathItems: () -> List<FilterItem>,
    userShowing: () -> Unit,
    pathShowing: () -> Unit,
    freePath: (String) -> FilterItem?,
) : HBox() {
    /** Gets every changed filter. */
    var onChange: (GitLogFilter) -> Unit = {}

    /** Called when Down is pressed in the text field, to move the focus to the commits. */
    var onDown: () -> Unit = {}

    private var resetting = false

    /** Applies a typed text 300 ms after the last keystroke. */
    internal val debounce = PauseTransition(Duration.millis(300.0)).apply { setOnFinished { apply() } }

    internal val text =
        TextField().apply {
            styleClass += "git-filter-text"
            promptText = "Text or hash"
            prefColumnCount = 14
            HBox.setHgrow(this, Priority.ALWAYS)
            textProperty().subscribe { _, _ -> if (!resetting) debounce.playFromStart() }
            setOnAction { apply() }
            addEventHandler(KeyEvent.KEY_PRESSED) { event ->
                when (event.code) {
                    KeyCode.ESCAPE -> {
                        if (text.isNotEmpty()) {
                            clear()
                            apply()
                            event.consume()
                        }
                    }

                    KeyCode.DOWN -> {
                        onDown()
                        event.consume()
                    }

                    else -> {}
                }
            }
        }

    /** Shows or hides the branches pane left of the bar (ADR 0040); shown at first. */
    internal val branchesToggle =
        ToggleButton(null, FontIcon(MaterialDesignF.FILE_TREE_OUTLINE)).apply {
            styleClass += "git-filter-branches"
            tooltip = Tooltip("Branches")
            isSelected = true
        }

    internal val regex = toggle(".*", "Regex")
    internal val matchCase = toggle("Cc", "Match Case")

    /** The text field with a magnifier and the toggles, looking like one text field. */
    internal val search =
        HBox(FontIcon(MaterialDesignM.MAGNIFY), text, regex, matchCase).apply {
            styleClass += "git-filter-search"
            alignment = Pos.CENTER_LEFT
        }

    internal val branch = FilterButton("Branch", MaterialDesignS.SOURCE_BRANCH, branchItems, null, {}, ::apply)
    internal val user =
        FilterButton("User", MaterialDesignA.ACCOUNT_OUTLINE, userItems, ::freeUser, userShowing, ::apply)
    internal val path =
        FilterButton("Path", MaterialDesignF.FOLDER_OUTLINE, pathItems, freePath, pathShowing, ::apply)

    private val order = ToggleGroup()
    internal val dateOrder = RadioMenuItem("Sort by Date").apply { toggleGroup = order }
    internal val topoOrder = RadioMenuItem("Sort Topologically").apply { toggleGroup = order }
    internal val firstParent = CheckMenuItem("First Parent Only")
    internal val noMerges = CheckMenuItem("No Merges")

    internal val options =
        MenuButton(null, FontIcon(MaterialDesignC.COG_OUTLINE)).apply {
            styleClass += "git-filter-options"
            tooltip = Tooltip(OPTIONS)
            items.addAll(dateOrder, topoOrder, SeparatorMenuItem(), firstParent, noMerges)
        }

    init {
        styleClass += "git-log-filters"
        alignment = Pos.CENTER_LEFT
        // Does not keep the split pane from narrowing the table; what does not fit is cut off at the right.
        minWidth = 0.0
        dateOrder.isSelected = true
        // A narrow bar shrinks the text field (to a minimum), not the buttons, whose texts stay readable.
        search.minWidth = SEARCH_MIN_WIDTH
        for (control in listOf(branchesToggle, regex, matchCase, branch, user, path, options)) {
            control.minWidth = USE_PREF_SIZE
        }
        children.addAll(
            branchesToggle,
            search,
            branch,
            user,
            path,
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            options,
        )
        for (item in listOf(regex, matchCase)) item.selectedProperty().subscribe { _, _ -> apply() }
        order.selectedToggleProperty().subscribe { _, toggle ->
            if (toggle != null) {
                updateOptions()
                apply()
            }
        }
        for (item in listOf(firstParent, noMerges)) {
            item.selectedProperty().subscribe { _, _ ->
                updateOptions()
                apply()
            }
        }
        // The buttons take the focus for the keyboard (Tab), but a click gives it back to where it was.
        addEventFilter(MouseEvent.MOUSE_PRESSED) { event -> if (clicked(event) != null) rememberFocus() }
        addEventFilter(MouseEvent.MOUSE_RELEASED) { event ->
            val control = clicked(event)
            // The cog's menu opens on the press: the focus goes back when it closes.
            if (control != null && control !== options) Platform.runLater(::restoreFocus)
        }
        options.showingProperty().subscribe { _, showing -> if (!showing) restoreFocus() }
    }

    /** The focus owner before a click on one of the bar's buttons, and its caret and anchor if it takes text. */
    private var focusBefore: Node? = null
    private var caretBefore = 0
    private var anchorBefore = 0

    /** The buttons and toggles of the bar; a click on them leaves the focus where it was. */
    private val clickable get() =
        listOf(
            branchesToggle,
            regex,
            matchCase,
            branch.button,
            branch.clear,
            user.button,
            user.clear,
            path.button,
            path.clear,
            options,
        )

    /** The button or toggle [event] is aimed at, if any. */
    private fun clicked(event: MouseEvent): Node? {
        var node = event.target as? Node
        while (node != null && node !== this) {
            if (clickable.any { it === node }) return node
            node = node.parent
        }
        return null
    }

    private fun rememberFocus() {
        val owner = scene?.focusOwner
        // Focused with Tab: the button keeps it.
        if (owner == null || clickable.any { it === owner }) return
        focusBefore = owner
        (owner as? TextInputControl)?.let {
            caretBefore = it.caretPosition
            anchorBefore = it.anchor
        }
    }

    private fun restoreFocus() {
        val node = focusBefore ?: return
        focusBefore = null
        if (node.scene !== scene) return
        node.requestFocus()
        // Gaining the focus may select all of a text field's text: typing would replace it.
        (node as? TextInputControl)?.selectRange(anchorBefore, caretBefore)
    }

    /**
     * Marks the options button while an option differs from the default (`:on`, `git-log.css`), and lists them in its
     * tooltip.
     */
    private fun updateOptions() {
        val on =
            listOf(
                topoOrder.isSelected to topoOrder,
                firstParent.isSelected to firstParent,
                noMerges.isSelected to noMerges,
            ).filter { it.first }
                .map { it.second.text }
        options.pseudoClassStateChanged(ON, on.isNotEmpty())
        options.tooltip.text = if (on.isEmpty()) OPTIONS else "$OPTIONS: ${on.joinToString(", ")}"
    }

    /** The filter the controls show; Regex and Match Case only count with a text. */
    val filter: GitLogFilter
        get() {
            val text = text.text.orEmpty().trim()
            return GitLogFilter(
                text = text,
                regex = text.isNotEmpty() && regex.isSelected,
                matchCase = text.isNotEmpty() && matchCase.isSelected,
                revisions = branch.values,
                users = user.values,
                paths = path.values,
                order = if (topoOrder.isSelected) GitLogOrder.TOPOLOGICAL else GitLogOrder.DATE,
                firstParent = firstParent.isSelected,
                noMerges = noMerges.isSelected,
            )
        }

    /** Focuses the text field and selects its text. */
    fun focusText() {
        text.requestFocus()
        text.selectAll()
    }

    /** Filters by exactly the branches [values] (full refnames or `HEAD`), as if checked in the Branch popup. */
    fun setBranches(values: List<String>) = branch.select(values)

    /** Back to the defaults, without telling [onChange]; popups close. Keeps the branches pane shown or hidden. */
    fun reset() {
        resetting = true
        try {
            debounce.stop()
            text.clear()
            regex.isSelected = false
            matchCase.isSelected = false
            dateOrder.isSelected = true
            firstParent.isSelected = false
            noMerges.isSelected = false
            for (button in listOf(branch, user, path)) button.reset()
            options.hide()
        } finally {
            resetting = false
        }
    }

    /**
     * Clears every filter (ADR 0045) and tells [onChange] once: the text (Regex and Match Case stay as they are), the
     * branches, users and paths, and the graph options; popups close.
     */
    fun clearFilters() {
        val regexOn = regex.isSelected
        val matchCaseOn = matchCase.isSelected
        reset()
        resetting = true
        try {
            regex.isSelected = regexOn
            matchCase.isSelected = matchCaseOn
        } finally {
            resetting = false
        }
        apply()
    }

    /** Hands the filter to [onChange] now, also a typed text still waiting for its delay. */
    private fun apply() {
        debounce.stop()
        if (!resetting) onChange(filter)
    }

    private companion object {
        const val OPTIONS = "Graph Options"
        const val SEARCH_MIN_WIDTH = 100.0

        /** An option differs from the default. */
        val ON: PseudoClass = PseudoClass.getPseudoClass("on")
    }

    private fun freeUser(text: String): FilterItem? = text.trim().ifEmpty { null }?.let { FilterItem(it, it) }

    private fun toggle(
        label: String,
        tip: String,
    ) = ToggleButton(label).apply {
        styleClass += "git-filter-toggle"
        tooltip = Tooltip(tip)
    }
}

/**
 * [text] as a path for the path filter: without leading `./` and `/` and trailing `/`; `null` if nothing is left, or
 * if it has a `..` segment (git would fail for a path outside the repository, and with it the whole log).
 */
internal fun normalizeFilterPath(text: String): String? {
    var path = text.trim()
    while (true) {
        val stripped = path.removePrefix("./").trimStart('/')
        if (stripped == path) break
        path = stripped
    }
    path = path.trimEnd('/')
    if (path.split('/').any { it == ".." }) return null
    return path.ifEmpty { null }
}
