package dev.sluide.codereview

import javafx.beans.property.BooleanProperty
import javafx.beans.property.ObjectProperty
import javafx.beans.property.ReadOnlyIntegerProperty
import javafx.beans.property.ReadOnlyIntegerWrapper
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.property.SimpleStringProperty
import javafx.beans.property.StringProperty
import javafx.collections.ObservableList

/**
 * The rows of a review's diff list and the state behind them (ADR 0168): the files in tree order, each one's content,
 * Viewed and collapsed flags, Load diff and the revealed parts of its gaps. FX thread only.
 *
 * [layout], [filter] and [ignoreWhitespace] rebuild all rows; every other update replaces only the changed file's
 * range of [rows] in one change, so a list keeps its viewport.
 */
class ReviewDocument : ReviewLineSource {
    private val rowList = RowList()

    /** The rows of the files that pass [filter]; read-only. */
    val rows: ObservableList<ReviewRow> = rowList

    private var states = LinkedHashMap<String, FileState>()
    private var shown = emptyList<FileState>()

    /** All files in tree order, also those the filter leaves out. */
    val files: List<ReviewFile>
        get() = states.values.map { it.file }

    val layout: ObjectProperty<ReviewLayout> = SimpleObjectProperty(ReviewLayout.SPLIT)

    /**
     * Whether git hides whitespace changes; a change drops all contents (the files show Loading until
     * [setContent]) and the revealed parts of the gaps.
     */
    val ignoreWhitespace: BooleanProperty = SimpleBooleanProperty(false)

    /** Shows only the files whose path contains it, ignoring case ([matchesReviewFilter]). */
    val filter: StringProperty = SimpleStringProperty("")

    private val viewed = ReadOnlyIntegerWrapper(0)

    /** The number of viewed files, the filtered ones included. */
    val viewedCount: ReadOnlyIntegerProperty = viewed.readOnlyProperty

    init {
        layout.addListener { _, _, _ -> rebuildAll() }
        filter.addListener { _, _, _ -> rebuildAll() }
        ignoreWhitespace.addListener { _, _, _ ->
            for (state in states.values) {
                state.prepared = null
                state.expansions.clear()
            }
            rebuildAll()
        }
    }

    /**
     * Shows [files] in tree order. Files whose path was there before keep their content, flags and revealed lines;
     * the others are dropped.
     */
    fun setFiles(files: List<ReviewFile>) {
        val old = states
        states = LinkedHashMap()
        for (file in treeOrder(files)) {
            states[file.path] = old[file.path]?.also { it.file = file } ?: FileState(file)
        }
        rebuildAll()
        countViewed()
    }

    /**
     * Sets the content of the file at [path] and forgets its revealed lines. A viewed file whose [PreparedFile.contentKey]
     * differs from the one it was marked viewed with is unchecked and expanded.
     */
    fun setContent(
        path: String,
        prepared: PreparedFile,
    ) {
        val state = states[path] ?: return
        if (state.viewed) {
            when (state.viewedKey) {
                // Marked viewed while loading: the first content is what was viewed.
                null -> {
                    state.viewedKey = prepared.contentKey
                }

                prepared.contentKey -> {}

                else -> {
                    state.viewed = false
                    state.viewedKey = null
                    state.collapsed = false
                }
            }
        }
        state.prepared = prepared
        state.expansions.clear()
        replaceRows(state)
        countViewed()
    }

    /** Reveals [direction] of the [gap]-th unchanged run of the file at [path]. */
    fun expand(
        path: String,
        gap: Int,
        direction: ExpandDirection,
    ) {
        val state = states[path] ?: return
        val text = state.prepared?.body as? PreparedBody.Text ?: return
        val run = unchangedRuns(text.originalLines.size, text.currentLines.size, text.changes).getOrNull(gap) ?: return
        state.expansions[gap] = (state.expansions[gap] ?: GapExpansion()).expanded(direction, run.length)
        replaceRows(state)
    }

    /** Marks the file at [path] viewed and collapses it, or unmarks and expands it. */
    fun setViewed(
        path: String,
        viewed: Boolean,
    ) {
        val state = states[path] ?: return
        state.viewed = viewed
        state.viewedKey = if (viewed) state.prepared?.contentKey else null
        state.collapsed = viewed
        replaceRows(state)
        countViewed()
    }

    fun setCollapsed(
        path: String,
        collapsed: Boolean,
    ) {
        val state = states[path] ?: return
        state.collapsed = collapsed
        replaceRows(state)
    }

    /** Shows the lines of a file that started collapsed behind Load diff. */
    fun loadDiff(path: String) {
        val state = states[path] ?: return
        state.diffLoaded = true
        replaceRows(state)
    }

    /** The index of the header row of the file at [path], -1 when it is not shown. */
    fun headerRow(path: String): Int {
        val state = states[path] ?: return -1
        return if (state in shown) offset(state) else -1
    }

    /** The anchor of the row at [row]: a line row's side and line (the current one when it has one), else the file. */
    fun anchorAt(row: Int): ReviewAnchor {
        val at = rows[row]
        val current = at.currentLine()
        val original = at.originalLine()
        return when {
            current != null -> ReviewAnchor(at.path, ReviewSide.CURRENT, current)
            original != null -> ReviewAnchor(at.path, ReviewSide.ORIGINAL, original)
            else -> ReviewAnchor(at.path, null, null)
        }
    }

    /** The row of [anchor]: its line row if shown, else its file's header row, else -1. */
    fun rowOf(anchor: ReviewAnchor): Int {
        val header = headerRow(anchor.path)
        if (header < 0 || anchor.side == null || anchor.line == null) return header
        val end = header + (states[anchor.path]?.rowCount ?: 0)
        for (index in header until end) {
            val row = rows[index]
            val line = if (anchor.side == ReviewSide.CURRENT) row.currentLine() else row.originalLine()
            if (line == anchor.line) return index
        }
        return header
    }

    override fun line(
        path: String,
        side: ReviewSide,
        line: Int,
    ): ReviewLine {
        val text = states[path]?.prepared?.body as? PreparedBody.Text
        val content = text?.lines(side)?.getOrNull(line) ?: return ReviewLine("", emptyList(), emptyList())
        return ReviewLine(content, text.tokens(side)[line], text.words(side)[line].orEmpty())
    }

    private fun rebuildAll() {
        shown = states.values.filter { matchesReviewFilter(it.file.path, filter.get()) }
        val all = ArrayList<ReviewRow>()
        for (state in shown) {
            val fileRows = fileRows(state)
            state.rowCount = fileRows.size
            all += fileRows
        }
        rowList.replaceAll(all)
    }

    private fun replaceRows(state: FileState) {
        if (state !in shown) return
        val from = offset(state)
        val fileRows = fileRows(state)
        rowList.replace(from, from + state.rowCount, fileRows)
        state.rowCount = fileRows.size
    }

    /** The index of the first row of [state], which is shown. */
    private fun offset(state: FileState): Int {
        var offset = 0
        for (other in shown) {
            if (other === state) return offset
            offset += other.rowCount
        }
        error("${state.file.path} is not shown")
    }

    private fun countViewed() {
        viewed.set(states.values.count { it.viewed })
    }

    /** The header, the body unless collapsed, and the spacer of the file of [state]. */
    private fun fileRows(state: FileState): List<ReviewRow> {
        val path = state.file.path
        val prepared = state.prepared
        val header =
            ReviewRow.FileHeader(
                path,
                state.file,
                prepared?.added ?: 0,
                prepared?.removed ?: 0,
                state.collapsed,
                state.viewed,
            )
        val body = if (state.collapsed) emptyList() else body(state)
        return listOf(header) + body + ReviewRow.Spacer(path)
    }

    private fun body(state: FileState): List<ReviewRow> {
        val path = state.file.path
        val prepared = state.prepared ?: return listOf(ReviewRow.Notice(path, NoticeKind.LOADING))
        return when (val body = prepared.body) {
            PreparedBody.Binary -> {
                listOf(ReviewRow.Notice(path, NoticeKind.BINARY))
            }

            PreparedBody.TooLarge -> {
                listOf(ReviewRow.Notice(path, NoticeKind.TOO_LARGE))
            }

            is PreparedBody.Submodule -> {
                listOf(ReviewRow.Notice(path, NoticeKind.SUBMODULE, body.detail))
            }

            is PreparedBody.Text -> {
                val changed = prepared.added + prepared.removed
                when {
                    body.changes.isEmpty() -> {
                        listOf(ReviewRow.Notice(path, NoticeKind.NO_CONTENT_CHANGE, noChangeDetail(state.file, body)))
                    }

                    !state.diffLoaded && startsCollapsed(path, changed) -> {
                        listOf(ReviewRow.Notice(path, NoticeKind.LOAD_DIFF, "$changed changed lines"))
                    }

                    else -> {
                        lineRows(path, body, layout.get(), state.expansions)
                    }
                }
            }
        }
    }

    private fun noChangeDetail(
        file: ReviewFile,
        text: PreparedBody.Text,
    ): String =
        when {
            file.change == ReviewChange.RENAMED || file.change == ReviewChange.COPIED -> "Renamed without changes"
            text.ignoreWhitespace -> "Only whitespace changes"
            else -> "No content changes"
        }

    /** A file and what the document keeps for it. */
    private class FileState(
        var file: ReviewFile,
    ) {
        var prepared: PreparedFile? = null
        var viewed = false

        /** The content key the file had when it was marked viewed; `null` when marked while loading. */
        var viewedKey: String? = null
        var collapsed = false
        var diffLoaded = false
        val expansions = HashMap<Int, GapExpansion>()
        var rowCount = 0
    }
}
