package dev.sluide.gitlog.details

import dev.sluide.git.GitCommitDetails
import dev.sluide.gitlog.data.BranchesState
import dev.sluide.gitlog.data.CommitDetailsState
import dev.sluide.gitlog.data.GitLogRow
import dev.sluide.gitlog.data.SHORT_HASH
import dev.sluide.gitlog.data.SHOWN_BRANCHES
import dev.sluide.gitlog.data.branchesLine
import dev.sluide.gitlog.data.hashCandidates
import dev.sluide.gitlog.ui.refLines
import javafx.animation.Animation
import javafx.animation.PauseTransition
import javafx.application.Platform
import javafx.beans.property.SimpleObjectProperty
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
 * its refs, and the branches that contain it ([branches], ADR 0045). The texts can be selected and copied; long ones wrap
 * and scroll vertically. Without exactly one selected commit, or while one loads for the first time, a placeholder says
 * so.
 */
internal class CommitDetailsPane(
    state: ObservableValue<CommitDetailsState>,
    private val branches: ObservableValue<BranchesState> = SimpleObjectProperty(BranchesState.None),
) : StackPane() {
    internal val placeholder = Label().apply { styleClass += "git-details-placeholder" }

    /** The subject and body, with links on the hashes of commits (ADR 0045). */
    internal val subject = LinkText("git-details-subject")
    internal val body = LinkText("git-details-body")
    internal val author = SelectableText("git-details-author")
    internal val committed = SelectableText("git-details-committed")
    internal val refs = VBox().apply { styleClass += "git-ref-lines" }

    /** The branches containing the commit, `Looking for branches…` while they take a while to read, or nothing. */
    internal val branchesLine = LinkText("git-details-branches")

    private val content =
        VBox(subject, body, author, committed, refs, branchesLine).apply {
            styleClass += "git-details-content"
            minWidth = 0.0
            VBox.setMargin(body, Insets(8.0, 0.0, 0.0, 0.0))
            VBox.setMargin(author, Insets(12.0, 0.0, 0.0, 0.0))
            VBox.setMargin(refs, Insets(12.0, 0.0, 0.0, 0.0))
            VBox.setMargin(branchesLine, Insets(12.0, 0.0, 0.0, 0.0))
        }

    internal val scroll =
        ScrollPane(content).apply {
            styleClass += "git-details-scroll"
            isFitToWidth = true
            hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
            isFocusTraversable = false
        }

    /** Goes to the commit of a full hash, e.g. one clicked in the message (ADR 0045); set by the log. */
    var openCommit: (String) -> Unit = {}

    /** The row whose details are shown, `null` while the placeholder is. */
    private var shown: GitLogRow? = null

    /** Shows "Loading…" if the details of another commit take a while. */
    internal val loading = PauseTransition(LOADING_DELAY).apply { setOnFinished { showPlaceholder("Loading…") } }

    /** Shows "Looking for branches…" if the branches take a while; nothing before, so that quick reads do not flicker. */
    internal val lookingForBranches =
        PauseTransition(LOADING_DELAY).apply {
            setOnFinished { showBranches(looking = true) }
        }

    /** The commit whose branches are all listed after a click on "Show all", until another commit is shown. */
    private var allBranchesOf: String? = null

    private val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withZone(ZoneId.systemDefault())
    private val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())

    init {
        styleClass += "git-details"
        minWidth = 0.0
        children.addAll(scroll, placeholder)
        state.subscribe(::show)
        branches.subscribe { _ -> showBranches() }
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
                showDetails(state.row, state.details, state.links)
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
        links: Map<String, String>,
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
        val subjectText = subjectLines.joinToString("\n")
        subject.show(subjectText, textLinks(subjectText, links))
        // An empty message (--allow-empty-message) shows no empty bold line.
        subject.isVisible = subjectText.isNotEmpty()
        subject.isManaged = subject.isVisible
        body.show(bodyText, textLinks(bodyText, links))
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
        if (!sameCommit) {
            scroll.vvalue = 0.0
            allBranchesOf = null
        }
        showBranches()
        content.requestLayout()
    }

    /** The words of [text] that [links] names a commit for, each a link to it. */
    private fun textLinks(
        text: String,
        links: Map<String, String>,
    ): List<TextLink> {
        if (links.isEmpty()) return emptyList()
        return hashCandidates(text).mapNotNull { range ->
            val hash = links[text.substring(range).lowercase()] ?: return@mapNotNull null
            TextLink(range.first, range.last + 1, "Go to ${hash.take(SHORT_HASH)}") { openCommit(hash) }
        }
    }

    private fun showRefs(row: GitLogRow) {
        refs.children.setAll(refLines(row.refs))
        refs.isVisible = row.refs.isNotEmpty()
        refs.isManaged = refs.isVisible
    }

    /**
     * Shows the line of the branches containing the shown commit, if they are read for it: "Looking for branches…"
     * only once [looking] (they took a while), at most [SHOWN_BRANCHES] names with "Show all" unless it was clicked.
     */
    private fun showBranches(looking: Boolean = false) {
        val hash = shown?.commit?.hash
        val state = branches.value
        if (state !is BranchesState.Reading || state.hash != hash) lookingForBranches.stop()
        when {
            state is BranchesState.Read && state.hash == hash -> {
                val line = branchesLine(state.names, all = allBranchesOf == hash)
                val showAll = line.showAll
                if (showAll == null) {
                    showBranchesLine(line.text)
                } else {
                    val start = line.text.length + 1
                    val link =
                        TextLink(start, start + showAll.length, "Show all branches") {
                            allBranchesOf = hash
                            showBranches()
                        }
                    showBranchesLine("${line.text} $showAll", listOf(link))
                }
            }

            state is BranchesState.Failed && state.hash == hash -> {
                showBranchesLine("Branches could not be read.")
            }

            state is BranchesState.Reading && state.hash == hash && looking -> {
                showBranchesLine("Looking for branches…")
            }

            state is BranchesState.Reading && state.hash == hash -> {
                showBranchesLine(null)
                if (lookingForBranches.status != Animation.Status.RUNNING) lookingForBranches.playFromStart()
            }

            else -> {
                showBranchesLine(null)
            }
        }
    }

    /** Shows [text] with [links] as the branches line; `null` hides it. */
    private fun showBranchesLine(
        text: String?,
        links: List<TextLink> = emptyList(),
    ) {
        branchesLine.isVisible = text != null
        branchesLine.isManaged = text != null
        branchesLine.show(text.orEmpty(), links)
    }

    /** Stops showing "Loading…" later; the window closes. */
    fun close() {
        val stop = {
            loading.stop()
            lookingForBranches.stop()
        }
        if (Platform.isFxApplicationThread()) stop() else Platform.runLater(stop)
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
