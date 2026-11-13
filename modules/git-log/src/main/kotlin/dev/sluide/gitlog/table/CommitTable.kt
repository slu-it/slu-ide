package dev.sluide.gitlog.table

import dev.sluide.git.GitCommit
import dev.sluide.git.GitPick
import dev.sluide.git.GitRef
import dev.sluide.git.GitRefKind
import dev.sluide.gitlog.actions.RefActions
import dev.sluide.gitlog.data.GitLogPages
import dev.sluide.gitlog.data.GitLogRow
import dev.sluide.gitlog.data.GitLogState
import dev.sluide.gitlog.data.SHORT_HASH
import dev.sluide.gitlog.operations.CheckoutRevision
import dev.sluide.gitlog.ui.COPY
import dev.sluide.gitlog.ui.GitLogIcons
import dev.sluide.gitlog.ui.contextMenuIcon
import dev.sluide.gitlog.ui.contextMenuItem
import dev.sluide.gitlog.ui.withSeparators
import javafx.beans.binding.Bindings
import javafx.beans.value.ObservableBooleanValue
import javafx.beans.value.ObservableStringValue
import javafx.scene.control.ContextMenu
import javafx.scene.control.Label
import javafx.scene.control.Menu
import javafx.scene.control.MenuItem
import javafx.scene.control.SelectionMode
import javafx.scene.control.TableView
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.input.ContextMenuEvent
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The commits of the [pages] with their graph, newest first, loaded as the user scrolls; several can be selected. The
 * context menu shows what applies to the selection ([commitMenuEntries], ADR 0172): the branches at the commit, review,
 * copying hashes (also Shortcut+C), comparing a commit with the working tree ([compareWithLocal], ADR 0044), checking
 * it out, creating branches and tags at it (ADR 0041), cherry-picking, reverting and resetting to it (ADR 0042) and
 * editing its message (ADR 0100) through [actions]. Escape ends a comparison ([stopComparing]); while it lists an
 * empty side, the placeholder says [emptySide]. Cherry-pick, revert and Edit Commit Message are greyed out while git
 * is [busy] with something; Reset and Edit Commit Message need a `HEAD` commit.
 */
internal class CommitTable(
    private val pages: GitLogPages,
    emptySide: ObservableStringValue,
    private val busy: ObservableBooleanValue,
    private val actions: RefActions,
) : TableView<GitLogRow>(pages.rows) {
    /** Opens the files a commit differs in from the working tree (ADR 0044); set by the view. */
    var compareWithLocal: (GitCommit) -> Unit = {}

    /** Asks to review the commits with these full hashes, top row first (ADR 0168); set by the view. */
    var review: (List<String>) -> Unit = {}

    /** Ends the comparison shown (ADR 0044); set by the view. */
    var stopComparing: () -> Unit = {}

    /** The submenus for the branches at a commit (ADR 0063); set by the view, called when the menu opens. */
    var branchMenus: (List<GitRef>) -> List<Menu> = { emptyList() }

    private val dateFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())

    private val entryItems =
        CommitMenuEntry.entries.associateWith { entry ->
            contextMenuItem(entry.id, entry.icon, "") { run(entry) }
        }

    /** The fixed item of [entry]; it is in the menu only while the entry applies to the selection. */
    internal fun item(entry: CommitMenuEntry): MenuItem = entryItems.getValue(entry)

    override fun createDefaultSkin() = PagingSkin(this, pages::shown)

    init {
        styleClass += "git-log-table"
        selectionModel.selectionMode = SelectionMode.MULTIPLE
        columns.addAll(
            subjectColumn(),
            column("Author", 160.0) { it.commit.author }.apply { minWidth = 50.0 },
            column("Date", 140.0) { dateFormat.format(it.commit.authorTime) }.apply { minWidth = 50.0 },
        )
        columnResizePolicy = SubjectTakesTheRest
        placeholder =
            Label().apply {
                textProperty().bind(
                    Bindings.createStringBinding(
                        {
                            val state = pages.state.get()
                            if (state == GitLogState.NO_MATCH && emptySide.get().isNotEmpty()) {
                                emptySide.get()
                            } else {
                                state.message
                            }
                        },
                        pages.state,
                        emptySide,
                    ),
                )
            }
        contextMenu = ContextMenu()
        // JavaFX opens no menu without items, and then never tells it is showing: the items are chosen before.
        addEventFilter(ContextMenuEvent.CONTEXT_MENU_REQUESTED) {
            refreshMenu()
        }
        // Again when it shows, so that it never shows the items of an earlier selection or state.
        contextMenu.setOnShowing { refreshMenu() }
        // While git starts or ends something with the menu open, the shown items follow without a rebuild.
        actions.running.subscribe { _ -> followState() }
        busy.subscribe { _ -> followState() }
        addEventHandler(KeyEvent.KEY_PRESSED) { event ->
            if (COPY.match(event)) {
                copyRevisionNumbers()
                event.consume()
            } else if (event.code == KeyCode.ESCAPE && pages.comparison != null) {
                stopComparing()
                event.consume()
            }
        }
    }

    /** Chooses, names and enables the items for the selection and the state now (ADR 0172). */
    internal fun refreshMenu() {
        val selected = selectionModel.selectedItems
        val sections = currentSections()
        sections.flatten().forEach { shown ->
            item(shown.entry).apply {
                text = shown.text
                isDisable = !shown.enabled
            }
        }
        val fixed = sections.map { section -> section.map { item(it.entry) } }
        contextMenu.items.setAll(withSeparators(listOf(branchSection(selected.singleOrNull()?.refs.orEmpty())) + fixed))
    }

    private fun currentSections() =
        commitMenuEntries(
            selectionModel.selectedItems.size,
            hasHead =
                pages.refs
                    ?.refs
                    .orEmpty()
                    .any { it.kind == GitRefKind.HEAD },
            onBranch = pages.refs?.currentBranch != null,
            running = actions.running.get(),
            busy = busy.get(),
        )

    /** Enables and disables the items an open menu shows as the state is now; the popup is not rebuilt. */
    private fun followState() {
        if (!contextMenu.isShowing) return
        currentSections().flatten().forEach { shown -> item(shown.entry).isDisable = !shown.enabled }
    }

    /** The branch submenus at the commit: the one branch's own, or one Branches menu with a submenu for each. */
    private fun branchSection(refs: List<GitRef>): List<MenuItem> {
        val menus =
            branchMenus(refs.filter { it.kind == GitRefKind.LOCAL_BRANCH || it.kind == GitRefKind.REMOTE_BRANCH })
        if (menus.size < 2) return menus
        return listOf(
            Menu("Branches", contextMenuIcon(GitLogIcons.branches)).apply {
                isMnemonicParsing = false
                items.setAll(menus)
            },
        )
    }

    private fun run(entry: CommitMenuEntry) {
        val commit = singleCommit()
        when (entry) {
            CommitMenuEntry.REVIEW -> {
                review(selectedHashes())
            }

            CommitMenuEntry.COPY_REVISION_NUMBER -> {
                copyRevisionNumbers()
            }

            CommitMenuEntry.CHECKOUT_REVISION -> {
                commit?.let { actions.start(CheckoutRevision(it.hash, it.hash.take(SHORT_HASH))) }
            }

            CommitMenuEntry.NEW_BRANCH -> {
                commit?.let { actions.newBranch(it.hash, it.hash.take(SHORT_HASH)) }
            }

            CommitMenuEntry.NEW_TAG -> {
                commit?.let { actions.newTag(it.hash) }
            }

            CommitMenuEntry.CHERRY_PICK -> {
                commit?.let { actions.pick(it, GitPick.CHERRY_PICK) }
            }

            CommitMenuEntry.REVERT -> {
                commit?.let { actions.pick(it, GitPick.REVERT) }
            }

            CommitMenuEntry.RESET -> {
                commit?.let(actions::resetTo)
            }

            CommitMenuEntry.COMPARE_WITH_LOCAL -> {
                commit?.let { compareWithLocal(it) }
            }

            CommitMenuEntry.EDIT_MESSAGE -> {
                commit?.let(actions::editMessage)
            }
        }
    }

    /** The commit of the one selected row; `null` for none or several. */
    private fun singleCommit() =
        selectionModel.selectedItems
            .singleOrNull()
            ?.commit

    /** The full hashes of the selected commits in table order. */
    private fun selectedHashes() =
        selectionModel.selectedIndices
            .sorted()
            .map { pages.rows[it].commit.hash }

    /** Copies the full hashes of the selected commits, one per line in table order. */
    private fun copyRevisionNumbers() {
        val hashes = selectedHashes()
        if (hashes.isEmpty()) return
        Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString(hashes.joinToString("\n")) })
    }
}
