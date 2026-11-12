package dev.sluide.codereview.model

import dev.sluide.codereview.ReviewChange
import dev.sluide.codereview.ReviewFile
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
internal class ReviewDocument : ReviewLineSource {
    private val rowList = RowList()

    /** The rows of the files that pass [filter]; read-only. */
    val rows: ObservableList<ReviewRow> = rowList

    private var states = LinkedHashMap<String, FileState>()

    /**
     * The content keys each path was marked viewed with, for the window's lifetime: a file is viewed again when the
     * content of its path is one of them (all changes, then one commit, then all again), not when it differs.
     */
    private val viewedKeys = HashMap<String, MutableSet<String>>()
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

    private val version = ReadOnlyIntegerWrapper(0)

    /** Counts the calls of [setFiles], so that a view of the file list can tell when to build it again. */
    val filesVersion: ReadOnlyIntegerProperty = version.readOnlyProperty

    private val viewed = ReadOnlyIntegerWrapper(0)

    /** The number of viewed files, the filtered ones included. */
    val viewedCount: ReadOnlyIntegerProperty = viewed.readOnlyProperty

    init {
        layout.addListener { _, _, _ -> rebuildAll() }
        filter.addListener { _, _, _ -> rebuildAll() }
        ignoreWhitespace.addListener { _, _, _ -> clearContents() }
    }

    /**
     * Drops the content of every file (they show Loading until [setContent]) and the revealed parts of the gaps;
     * the Viewed and collapsed flags stay until the next content decides them. For a load of other texts.
     */
    fun clearContents() {
        for (state in states.values) {
            state.prepared = null
            state.unreadable = false
            state.expansions.clear()
        }
        rebuildAll()
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
        version.set(version.get() + 1)
    }

    /**
     * Sets the content of the file at [path]; its revealed lines stay only when the [PreparedFile.contentKey] is the
     * one it had. The file is viewed (and collapsed) exactly when it was marked viewed with this content key, or
     * while it was loading; a viewed file with another content is unchecked and expanded.
     */
    fun setContent(
        path: String,
        prepared: PreparedFile,
    ) {
        val state = states[path] ?: return
        val key = prepared.contentKey
        if (state.viewedWhileLoading) {
            // What was marked viewed while loading is the first content that arrives.
            state.viewedWhileLoading = false
            viewedKeys.getOrPut(path) { HashSet() } += key
        }
        val isViewed = key in viewedKeys[path].orEmpty()
        if (isViewed && !state.viewed) {
            state.viewed = true
            state.collapsed = true
        } else if (!isViewed && state.viewed) {
            state.viewed = false
            state.collapsed = false
        }
        // The same texts keep their gaps; hiding whitespace has already forgotten the revealed lines.
        if (state.prepared?.contentKey != prepared.contentKey) state.expansions.clear()
        state.prepared = prepared
        state.unreadable = false
        replaceRows(state)
        countViewed()
    }

    /** Shows the file at [path] as one git could not read ([NoticeKind.UNREADABLE]) instead of its lines. */
    fun setUnreadable(path: String) {
        val state = states[path] ?: return
        state.prepared = null
        state.unreadable = true
        state.expansions.clear()
        replaceRows(state)
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

    /** Whether the file at [path] is marked viewed. */
    fun isViewed(path: String): Boolean = states[path]?.viewed == true

    /** Marks the file at [path] viewed and collapses it, or unmarks and expands it. */
    fun setViewed(
        path: String,
        viewed: Boolean,
    ) {
        val state = states[path] ?: return
        state.viewed = viewed
        state.collapsed = viewed
        if (viewed) {
            val key = state.prepared?.contentKey
            if (key == null) state.viewedWhileLoading = true else viewedKeys.getOrPut(path) { HashSet() } += key
        } else {
            state.viewedWhileLoading = false
            viewedKeys.remove(path)
        }
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
        if (state.unreadable) return listOf(ReviewRow.Notice(path, NoticeKind.UNREADABLE))
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
                        listOf(ReviewRow.Notice(path, NoticeKind.LOAD_DIFF, "$changed"))
                    }

                    else -> {
                        lineRows(path, body, layout.get(), state.expansions)
                    }
                }
            }
        }
    }

    /** Why a text file shows no lines: different texts differ only in ignored whitespace; equal ones by the change. */
    private fun noChangeDetail(
        file: ReviewFile,
        text: PreparedBody.Text,
    ): String =
        when {
            !text.identical -> "Only whitespace changes"
            file.change == ReviewChange.RENAMED -> "Renamed without changes"
            file.change == ReviewChange.COPIED -> "Copied without changes"
            else -> "No content changes"
        }

    /** A file and what the document keeps for it. */
    private class FileState(
        var file: ReviewFile,
    ) {
        var prepared: PreparedFile? = null
        var unreadable = false
        var viewed = false

        /** Marked viewed while its content was missing: the first content that arrives is what was viewed. */
        var viewedWhileLoading = false
        var collapsed = false
        var diffLoaded = false
        val expansions = HashMap<Int, GapExpansion>()
        var rowCount = 0
    }
}
