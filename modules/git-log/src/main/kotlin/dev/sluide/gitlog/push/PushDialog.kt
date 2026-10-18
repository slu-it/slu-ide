package dev.sluide.gitlog.push

import dev.sluide.fileicons.FILE_ICONS_CSS
import dev.sluide.git.GitChangedFile
import dev.sluide.gitlog.operations.PushOperation
import dev.sluide.gitlog.operations.RefConfirmation
import dev.sluide.gitlog.operations.commitCount
import dev.sluide.gitlog.operations.isProtectedBranch
import dev.sluide.gitlog.operations.nameProblem
import dev.sluide.gitlog.operations.refConfirmation
import dev.sluide.gitlog.ui.ChangeTreeView
import dev.sluide.gitlog.ui.GIT_LOG_CSS
import dev.sluide.gitlog.ui.SharedSplit
import javafx.beans.binding.Bindings
import javafx.beans.property.SimpleStringProperty
import javafx.beans.value.ObservableBooleanValue
import javafx.geometry.Pos
import javafx.geometry.Rectangle2D
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.control.MenuItem
import javafx.scene.control.SplitMenuButton
import javafx.scene.control.TextField
import javafx.scene.control.Tooltip
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyEvent
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.stage.Modality
import javafx.stage.Screen
import javafx.stage.Stage
import javafx.stage.Window
import javafx.util.Subscription
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal val FORCE_PUSH_BUTTON = ButtonType("Force Push", ButtonBar.ButtonData.OK_DONE)

/** Opens the Push dialog: IntelliJ's shortcut, Git → Push… in the IDE window (ADR 0049). */
internal val PUSH_KEYS = KeyCodeCombination(KeyCode.K, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN)

/**
 * The Push dialog of the git log (ADR 0049), as [reader] reads it: a window owned by the IDE window [ideWindow] (so it
 * stays above it), not modal, so that the diffs it opens can be looked at in the editor. At the top the branch, the
 * remote (a choice with several), the target branch, which can be edited (checked with [nameProblem], read again after
 * a pause), and "New" if the remote does not have it yet; left the outgoing commits, "All" first; right the files of
 * the selected row ([ChangeTreeView]), whose diffs go to [showDiff] with the commit and the parent and where to tell
 * what keeps one from being shown, Open Local File (F4) to [openFile] if the file [exists]. Below, notes (the target
 * has commits the branch has not, as of the last fetch, why nothing can be pushed) and Push, whose menu has Force Push
 * (asked first, never to a protected branch), and Cancel. Push and Force Push close the dialog and hand a
 * [PushOperation] to [push]; they are disabled while an operation is [running]. It reads again when it gains the
 * focus, as the user may have committed meanwhile. Themed like every window, with `git-log.css` and the `.git-log`
 * colours.
 */
internal class PushDialog(
    private val ideWindow: Window?,
    val reader: PushReader,
    private val running: ObservableBooleanValue,
    private val nameProblem: (String) -> String?,
) {
    /** Runs a push; the dialog is closed by then. */
    var push: (PushOperation) -> Unit = {}

    /** Shows the diff of a file of a commit against a parent; what keeps it from being shown goes to the last one. */
    var showDiff: (commit: String, parent: String?, file: GitChangedFile, tell: (String) -> Unit) -> Unit =
        { _, _, _, _ -> }

    /** Opens a file of the working tree. */
    var openFile: (GitChangedFile) -> Unit = {}

    /** Whether a file is in the working tree now. */
    var exists: (GitChangedFile) -> Boolean = { false }

    /** While the fields are set from what was read, so that this does not read again. */
    private var updating = false

    /** What is wrong with the target name; `null` if nothing. */
    private val problem = SimpleStringProperty(null)

    internal val branchLabel = Label().apply { styleClass += "git-push-branch" }

    internal val remoteBox = ComboBox<String>().apply { styleClass += "git-push-remote" }

    /** The remote's name while there is only one. */
    internal val remoteLabel = Label().apply { styleClass += "git-push-remote-name" }

    internal val targetField =
        TextField().apply {
            styleClass += "git-push-target"
            prefColumnCount = TARGET_COLUMNS
        }

    internal val newBadge =
        Label("New").apply {
            styleClass += "git-push-new"
            isVisible = false
            managedProperty().bind(visibleProperty())
        }

    internal val problemLabel =
        Label().apply {
            styleClass += "git-push-problem"
            textProperty().bind(problem.map { it }.orElse(""))
            visibleProperty().bind(problem.isNotNull)
            managedProperty().bind(visibleProperty())
        }

    internal val commitList =
        ListView<PushRow>().apply {
            styleClass += "git-push-commits"
            setCellFactory { PushCell() }
        }

    internal val commitPlaceholder =
        Label().apply {
            styleClass += "git-push-placeholder"
            isWrapText = true
        }

    internal val tree =
        ChangeTreeView().apply {
            showDiff = ::showFileDiff
            openFile = { file -> this@PushDialog.openFile(file) }
            exists = { file -> this@PushDialog.exists(file) }
        }

    internal val filesPlaceholder = Label().apply { styleClass += "git-push-placeholder" }

    internal val behindLabel = note("git-push-behind")

    internal val reasonLabel = note("git-push-reason")

    internal val protectedLabel = note("git-push-protected")

    /** Why a diff cannot be shown, until the next selection or read. */
    internal val statusLabel = note("git-push-status")

    internal val fetchNote = note("git-push-fetch-note")

    /** Whether Push can push now ([canPush]). */
    internal val pushAllowed =
        Bindings.createBooleanBinding(
            { canPush(reader.state.get(), problem.get(), reader.loading.get(), running.get()) },
            reader.state,
            reader.loading,
            running,
            problem,
        )

    /** Whether Force Push can push now ([canForcePush]): never to a protected branch (ADR 0049). */
    internal val forceAllowed =
        Bindings.createBooleanBinding(
            { canForcePush(reader.state.get(), problem.get(), reader.loading.get(), running.get()) },
            reader.state,
            reader.loading,
            running,
            problem,
        )

    internal val forcePushItem =
        MenuItem("Force Push").apply {
            setOnAction { pushNow(force = true) }
            disableProperty().bind(forceAllowed.not())
        }

    /**
     * Push, with Force Push in its menu; enabled while either can push. With only Force Push possible (`HEAD` behind
     * the target), its own label is muted and pushes nothing.
     */
    internal val pushButton =
        SplitMenuButton(forcePushItem).apply {
            text = "Push"
            styleClass += "git-push-button"
            setOnAction { pushNow(force = false) }
            disableProperty().bind(pushAllowed.or(forceAllowed).not())
            pushAllowed.subscribe { allowed ->
                styleClass.remove(FORCE_ONLY)
                if (!allowed) styleClass += FORCE_ONLY
            }
            // Why Force Push is disabled for a protected branch, once the user looks for it.
            setOnShowing { if (isProtectedBranch(targetField.text.orEmpty())) showProtected() }
        }

    internal val cancelButton =
        Button("Cancel").apply {
            styleClass += "git-push-cancel"
            isCancelButton = true
            setOnAction { close() }
        }

    /** The commits left, the files of the selected row right. */
    private val split =
        SharedSplit(
            StackPane(commitList),
            StackPane(tree, filesPlaceholder).apply { styleClass += "git-push-files" },
            COMMITS_SHARE,
        ).apply { styleClass += "git-push-body" }

    internal val stage: Stage =
        Stage().apply {
            ideWindow?.let(::initOwner)
            initModality(Modality.NONE)
            (ideWindow as? Stage)?.let { icons.setAll(it.icons) }
            isResizable = true
            title = TITLE
            scene =
                Scene(
                    BorderPane().apply {
                        // .git-log gives the looked-up colours of the changes: the window is one of its own.
                        styleClass.addAll("git-log", "git-push")
                        stylesheets += GIT_LOG_CSS
                        stylesheets += FILE_ICONS_CSS
                        top =
                            VBox(
                                HBox(
                                    branchLabel,
                                    Label("→").apply { styleClass += "git-push-arrow" },
                                    remoteBox,
                                    remoteLabel,
                                    Label(":"),
                                    targetField,
                                    newBadge,
                                ).apply {
                                    styleClass += "git-push-row"
                                    alignment = Pos.CENTER_LEFT
                                },
                                problemLabel,
                            ).apply { styleClass += "git-push-top" }
                        center = split
                        bottom =
                            VBox(
                                behindLabel,
                                reasonLabel,
                                protectedLabel,
                                statusLabel,
                                fetchNote,
                                HBox(
                                    Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                                    pushButton,
                                    cancelButton,
                                ).apply {
                                    styleClass += "git-push-buttons"
                                    alignment = Pos.CENTER_RIGHT
                                },
                            ).apply { styleClass += "git-push-bottom" }
                    },
                    WIDTH,
                    HEIGHT,
                )
            // Escape closes it from anywhere, also from the target field; not while a popup is open.
            scene.addEventFilter(KeyEvent.KEY_PRESSED) { event ->
                if (event.code == KeyCode.ESCAPE && !remoteBox.isShowing && !pushButton.isShowing) {
                    close()
                    event.consume()
                }
            }
            // The user may have committed or checked out meanwhile: read again when they come back.
            focusedProperty().subscribe { _, focused ->
                if (focused && reader.state.get() != null && !reader.loading.get()) reload()
            }
            setOnHidden {
                reader.close()
                writeEnded.unsubscribe()
            }
        }

    /**
     * Reads again when an operation ends, e.g. a fetch or commit: what the dialog shows may have changed, and it must
     * not push with what it read before.
     */
    private val writeEnded: Subscription =
        running.subscribe { _, now -> if (!now && stage.isShowing && reader.state.get() != null) reload() }

    init {
        commitList.placeholder = commitPlaceholder
        reader.state.subscribe { state -> if (state != null) showState(state) }
        reader.files.subscribe { files -> showFiles(files) }
        commitList.selectionModel.selectedItemProperty().subscribe { _ -> if (!updating) requestFiles() }
        tree.selectionModel.selectedItemProperty().subscribe { _ -> statusLabel.text = "" }
        targetField.textProperty().subscribe { _, text -> targetChanged(text) }
        remoteBox.valueProperty().subscribe { _, remote ->
            stage.title = remote?.let { "$TITLE to $it" } ?: TITLE
            if (!updating) reader.load(remote, targetField.text)
        }
        // Enter in the target field does not push.
        targetField.setOnAction { it.consume() }
        targetField.isDisable = true
        remoteBox.isDisable = true
        remoteBox.isVisible = false
        remoteBox.isManaged = false
        commitPlaceholder.text = pushPlaceholder(null)
        problem.set(null)
    }

    /** Shows the dialog and reads what it shows, or brings it to the front if it is shown already. */
    fun show() {
        if (stage.isShowing) {
            stage.toFront()
            stage.requestFocus()
            // Not left to the focus, which a window that has it already does not gain.
            reload()
            return
        }
        reader.load(null, null)
        ideWindow?.let(::placeOver)
        stage.show()
    }

    fun close() {
        stage.close()
    }

    /** Reads again for the remote and target shown. */
    private fun reload() {
        reader.load(remoteBox.value, targetField.text)
    }

    private fun targetChanged(text: String?) {
        val name = text.orEmpty()
        problem.set(nameProblem(name))
        if (!isProtectedBranch(name)) hideProtected()
        if (!updating) reader.load(remoteBox.value, name, DEBOUNCE_MILLIS)
    }

    private fun showState(state: PushState) {
        updating = true
        try {
            branchLabel.text = state.branch ?: "HEAD"
            if (remoteBox.items != state.remotes) remoteBox.items.setAll(state.remotes)
            remoteBox.value = state.remote
            remoteLabel.text = state.remote.orEmpty()
            val several = state.remotes.size > 1
            remoteBox.isVisible = several
            remoteBox.isManaged = several
            remoteLabel.isVisible = !several
            remoteLabel.isManaged = !several
            if (targetField.text != state.target) targetField.text = state.target
            val blocked = state.failed || state.blocked != null
            // Nothing to name without a branch or a remote.
            problem.set(if (blocked) null else nameProblem(targetField.text.orEmpty()))
            targetField.isDisable = blocked
            remoteBox.isDisable = blocked
            newBadge.isVisible = state.isNew
            newBadge.tooltip =
                state.trackingRef?.takeIf { state.isNew }?.let {
                    Tooltip("$it does not exist: the push creates ${state.target} on ${state.remote}.")
                }
            val remote = state.remote
            // Without commits to send, the note below says it all.
            val behind = state.outgoing?.takeIf { it.commits.isNotEmpty() }?.behind ?: 0
            behindLabel.text = remote?.let { behindNote(it, state.target, behind) }.orEmpty()
            // Why a normal push sends nothing; why nothing can be pushed at all is said in place of the commits.
            reasonLabel.text = state.nothingToPushNote.orEmpty()
            fetchNote.text = remote?.takeIf { !blocked }?.let { "As of the last fetch of $it." }.orEmpty()
            showRows(state)
        } finally {
            updating = false
        }
        listOf(behindLabel, reasonLabel, fetchNote).forEach { it.isVisible = it.text.isNotEmpty() }
        requestFiles()
    }

    /** Lists the commits of [state], keeping the selected commit if it is still listed, else selecting All. */
    private fun showRows(state: PushState) {
        val selected = commitList.selectionModel.selectedItem
        val rows = pushRows(state.outgoing)
        commitPlaceholder.text = pushPlaceholder(state)
        if (commitList.items != rows) commitList.items.setAll(rows)
        val index = rows.indexOf(selected).takeIf { selected is PushRow.Commit && it >= 0 } ?: 0
        if (rows.isEmpty()) {
            commitList.selectionModel.clearSelection()
        } else if (commitList.selectionModel.selectedIndex != index) {
            commitList.selectionModel.clearAndSelect(index)
        }
    }

    /** Reads the files of the selected row, unless they are shown or read already. */
    private fun requestFiles() {
        val state = reader.state.get()
        val key = pushFilesOf(commitList.selectionModel.selectedItem, state?.outgoing)
        if (key == null) {
            reader.noFiles()
            return
        }
        val files = reader.files.get()
        val current =
            when (files) {
                is PushFiles.Loaded -> files.commit to files.parent
                is PushFiles.Loading -> files.commit to files.parent
                else -> null
            }
        if (current != key) reader.files(key.first, key.second)
    }

    private fun showFiles(files: PushFiles) {
        statusLabel.text = ""
        statusLabel.isVisible = false
        when (files) {
            PushFiles.None -> {
                tree.clear()
                showFilesPlaceholder("")
            }

            is PushFiles.Loading -> {
                tree.clear()
                showFilesPlaceholder("Loading…")
            }

            is PushFiles.Loaded -> {
                tree.show(files.files, keep = false)
                showFilesPlaceholder(if (files.files.isEmpty()) "No files changed." else "")
            }

            PushFiles.Failed -> {
                tree.clear()
                showFilesPlaceholder("The files could not be read.")
            }
        }
    }

    private fun showFilesPlaceholder(text: String) {
        filesPlaceholder.text = text
        filesPlaceholder.isVisible = text.isNotEmpty()
    }

    /** Hands the diff of [file] of the files shown to [showDiff]. */
    private fun showFileDiff(file: GitChangedFile) {
        val files = reader.files.get() as? PushFiles.Loaded ?: return
        statusLabel.text = ""
        statusLabel.isVisible = false
        showDiff(files.commit, files.parent, file) { message ->
            if (reader.files.get() === files) {
                statusLabel.text = message
                statusLabel.isVisible = true
            }
        }
    }

    private fun showProtected() {
        protectedLabel.text = "Force push to ${targetField.text} is not allowed (protected branch)."
        protectedLabel.isVisible = true
    }

    private fun hideProtected() {
        protectedLabel.text = ""
        protectedLabel.isVisible = false
    }

    /** Pushes as the dialog says, forced after asking; closes the dialog first. */
    private fun pushNow(force: Boolean) {
        if (!(if (force) forceAllowed else pushAllowed).get()) return
        val state = reader.state.get() ?: return
        val outgoing = state.outgoing ?: return
        val branch = state.branch ?: return
        val remote = state.remote ?: return
        val head = state.head ?: return
        val operation =
            PushOperation(
                remote,
                branch,
                state.target,
                force,
                if (force) outgoing.remoteCommit else null,
                setUpstream = !state.hasUpstream,
                count = outgoing.commits.size,
                more = outgoing.more,
            )
        if (force) {
            val (question, details) = forcePushQuestion(branch, remote, state.target, outgoing.behind, head)
            val confirmation = RefConfirmation("Force Push", question, FORCE_PUSH_BUTTON, operation, details)
            val answer = refConfirmation(stage, confirmation).showAndWait().orElse(ButtonType.CANCEL)
            if (answer != FORCE_PUSH_BUTTON) return
        }
        close()
        push(operation)
    }

    /** Puts the dialog in the middle of [window], within the screen it is on. */
    private fun placeOver(window: Window) {
        val bounds = Rectangle2D(window.x, window.y, window.width.coerceAtLeast(1.0), window.height.coerceAtLeast(1.0))
        val area = (Screen.getScreensForRectangle(bounds).firstOrNull() ?: Screen.getPrimary()).visualBounds
        val width = minOf(WIDTH, area.width)
        val height = minOf(HEIGHT, area.height)
        stage.width = width
        stage.height = height
        stage.x = (window.x + (window.width - width) / 2).coerceIn(area.minX, area.maxX - width)
        stage.y = (window.y + (window.height - height) / 2).coerceIn(area.minY, area.maxY - height)
    }

    /** A row of the commits: All with their number, a commit's subject, author and date, or that there are more. */
    private inner class PushCell : ListCell<PushRow>() {
        private val subject =
            Label().apply {
                styleClass += "git-push-subject"
                minWidth = 0.0
                maxWidth = Double.MAX_VALUE
                HBox.setHgrow(this, Priority.ALWAYS)
            }
        private val author = Label().apply { styleClass += "git-push-author" }
        private val date = Label().apply { styleClass += "git-push-date" }
        private val box = HBox(subject, author, date).apply { styleClass += "git-push-cell" }

        override fun updateItem(
            item: PushRow?,
            empty: Boolean,
        ) {
            super.updateItem(item, empty)
            text = null
            if (empty || item == null) {
                graphic = null
                return
            }
            val commit = (item as? PushRow.Commit)?.commit
            subject.text =
                when (item) {
                    is PushRow.All -> "All ${commitCount(item.count, item.more)}"
                    is PushRow.Commit -> item.commit.subject
                    PushRow.More -> "and more…"
                }
            subject.styleClass.removeAll("git-push-all", "git-push-more")
            if (item is PushRow.All) subject.styleClass += "git-push-all"
            if (item == PushRow.More) subject.styleClass += "git-push-more"
            author.text = commit?.author.orEmpty()
            date.text = commit?.let { DATE_FORMAT.format(it.authorTime) }.orEmpty()
            listOf(author, date).forEach {
                it.isVisible = commit != null
                it.isManaged = commit != null
            }
            graphic = box
        }
    }

    private companion object {
        const val TITLE = "Push Commits"

        /** The style class of Push while only Force Push can push. */
        const val FORCE_ONLY = "git-push-force-only"
        const val WIDTH = 900.0
        const val HEIGHT = 560.0
        const val TARGET_COLUMNS = 20
        const val COMMITS_SHARE = 0.5

        /** How long editing the target pauses before it is read. */
        const val DEBOUNCE_MILLIS = 300L

        val DATE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())

        fun note(styleClass: String) =
            Label().apply {
                this.styleClass += styleClass
                isWrapText = true
                isVisible = false
                managedProperty().bind(visibleProperty())
            }
    }
}
