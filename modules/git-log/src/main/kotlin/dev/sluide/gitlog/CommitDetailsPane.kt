package dev.sluide.gitlog

import dev.sluide.git.GitCommitDetails
import javafx.animation.PauseTransition
import javafx.application.Platform
import javafx.beans.value.ObservableValue
import javafx.geometry.Insets
import javafx.geometry.Orientation
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.TextArea
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.text.Text
import javafx.scene.text.TextBoundsType
import javafx.util.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.ceil

/** How long the details of another commit stay while the next ones load, before "Loading…" replaces them. */
private val LOADING_DELAY = Duration.millis(300.0)

/**
 * The details of the selected commit, right of the git log's table (ADR 0037), in IntelliJ's order: the message (the
 * subject bold), the short hash and author, the author's email and date, when it was committed (only if that differs),
 * and its refs. The texts can be selected and copied; long ones wrap and scroll vertically. Without exactly one
 * selected commit, or while one loads for the first time, a placeholder says so.
 */
internal class CommitDetailsPane(
    state: ObservableValue<CommitDetailsState>,
) : StackPane() {
    internal val placeholder = Label().apply { styleClass += "git-details-placeholder" }
    internal val subject = SelectableText("git-details-subject")
    internal val body = SelectableText("git-details-body")
    internal val author = SelectableText("git-details-author")
    internal val committed = SelectableText("git-details-committed")
    internal val refs = VBox().apply { styleClass += "git-ref-lines" }

    private val content =
        VBox(subject, body, author, committed, refs).apply {
            styleClass += "git-details-content"
            minWidth = 0.0
            VBox.setMargin(body, Insets(8.0, 0.0, 0.0, 0.0))
            VBox.setMargin(author, Insets(12.0, 0.0, 0.0, 0.0))
            VBox.setMargin(refs, Insets(12.0, 0.0, 0.0, 0.0))
        }

    internal val scroll =
        ScrollPane(content).apply {
            styleClass += "git-details-scroll"
            isFitToWidth = true
            hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
            isFocusTraversable = false
        }

    /** The row whose details are shown, `null` while the placeholder is. */
    private var shown: GitLogRow? = null

    /** Shows "Loading…" if the details of another commit take a while. */
    internal val loading = PauseTransition(LOADING_DELAY).apply { setOnFinished { showPlaceholder("Loading…") } }

    private val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withZone(ZoneId.systemDefault())
    private val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())

    init {
        styleClass += "git-details"
        minWidth = 0.0
        children.addAll(scroll, placeholder)
        state.subscribe(::show)
    }

    private fun show(state: CommitDetailsState) {
        loading.stop()
        when (state) {
            CommitDetailsState.NoneSelected -> {
                showPlaceholder("No commit selected")
            }

            is CommitDetailsState.SeveralSelected -> {
                showPlaceholder("${state.count} commits selected")
            }

            is CommitDetailsState.Loading -> {
                val shown = shown
                when {
                    shown == null -> showPlaceholder("Loading…")

                    // The same commit again (after a refresh): its refs may have changed, the rest stays.
                    shown.commit.hash == state.row.commit.hash -> showRefs(state.row)

                    // Another commit: no flicker on fast moves through the rows, unless it takes a while.
                    else -> loading.playFromStart()
                }
            }

            is CommitDetailsState.Loaded -> {
                showDetails(state.row, state.details)
            }

            is CommitDetailsState.Failed -> {
                showPlaceholder("The commit details could not be read.")
            }
        }
    }

    private fun showPlaceholder(text: String) {
        shown = null
        placeholder.text = text
        placeholder.isVisible = true
        scroll.isVisible = false
    }

    private fun showDetails(
        row: GitLogRow,
        details: GitCommitDetails,
    ) {
        val sameCommit = shown?.commit?.hash == row.commit.hash
        shown = row
        val commit = details.commit
        val lines = details.message.lines()
        val subjectLines = lines.dropWhile(String::isBlank).takeWhile { it.isNotBlank() }
        val bodyText =
            lines
                .dropWhile(String::isBlank)
                .drop(subjectLines.size)
                .dropWhile(String::isBlank)
                .dropLastWhile(String::isBlank)
                .joinToString("\n")
        subject.text = subjectLines.joinToString("\n")
        // An empty message (--allow-empty-message) shows no empty bold line.
        subject.isVisible = subject.text.isNotEmpty()
        subject.isManaged = subject.isVisible
        body.text = bodyText
        body.isVisible = bodyText.isNotEmpty()
        body.isManaged = body.isVisible
        author.text =
            "${commit.hash.take(SHORT_HASH)} ${commit.author}\n${commit.authorEmail}\non ${at(commit.authorTime)}"
        val otherCommitter = details.committer != commit.author || details.committerEmail != commit.authorEmail
        committed.text =
            "committed on ${at(details.committerTime)}" + if (otherCommitter) " by ${details.committer}" else ""
        // As shown, to the minute: an amend within the same minute would add a line saying the same.
        committed.isVisible = otherCommitter || at(details.committerTime) != at(commit.authorTime)
        committed.isManaged = committed.isVisible
        showRefs(row)
        placeholder.isVisible = false
        scroll.isVisible = true
        if (!sameCommit) scroll.vvalue = 0.0
        content.requestLayout()
    }

    private fun showRefs(row: GitLogRow) {
        refs.children.setAll(refLines(row.refs))
        refs.isVisible = row.refs.isNotEmpty()
        refs.isManaged = refs.isVisible
    }

    /** Stops showing "Loading…" later; the window closes. */
    fun close() {
        if (Platform.isFxApplicationThread()) loading.stop() else Platform.runLater(loading::stop)
    }

    private fun at(time: Instant) = "${dateFormat.format(time)} at ${timeFormat.format(time)}"

    private companion object {
        /** As many characters of the hash as IntelliJ shows. */
        const val SHORT_HASH = 8
    }
}

/**
 * Read-only text that can be selected and copied with the mouse and Shortcut+C, styled flat (`git-log.css`), wrapped
 * and always as high as its text, so that only the details pane scrolls. It takes the focus only when clicked.
 */
internal class SelectableText(
    styleClass: String,
) : TextArea() {
    /** Measures the wrapped text as the skin lays it out, with the same (taller) line bounds as its text node. */
    private val measure = Text().apply { boundsType = TextBoundsType.LOGICAL_VERTICAL_CENTER }

    init {
        this.styleClass += "git-details-text"
        this.styleClass += styleClass
        isEditable = false
        isWrapText = true
        isFocusTraversable = false
        prefColumnCount = 1
        prefRowCount = 1
        minWidth = 0.0
        textProperty().addListener { _ -> requestLayout() }
        fontProperty().addListener { _ -> requestLayout() }
        // It is as high as its text, so its scroll bars are never needed; one shown in a layout before the width was
        // known would narrow the text and so stay.
        skinProperty().subscribe { _ ->
            (lookup(".scroll-pane") as? ScrollPane)?.apply {
                vbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
                hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
            }
        }
    }

    override fun getContentBias() = Orientation.HORIZONTAL

    override fun computePrefHeight(width: Double): Double {
        measure.font = font
        measure.text = text
        // Without a width yet, as many lines as the text has.
        measure.wrappingWidth = if (width > 0) maxOf(1.0, width - snappedLeftInset() - snappedRightInset()) else 0.0
        // A pixel to spare, so that rounding never brings up the text area's own scroll bar.
        return snappedTopInset() + snappedBottomInset() + ceil(measure.layoutBounds.height) + 1
    }

    override fun computeMinHeight(width: Double) = computePrefHeight(width)

    override fun computeMaxHeight(width: Double) = computePrefHeight(width)
}
