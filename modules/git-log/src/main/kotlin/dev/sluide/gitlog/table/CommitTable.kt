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
import dev.sluide.gitlog.operations.pickTitle
import dev.sluide.gitlog.ui.COPY
import javafx.beans.binding.Bindings
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.value.ObservableBooleanValue
import javafx.beans.value.ObservableStringValue
import javafx.scene.control.ContextMenu
import javafx.scene.control.Label
import javafx.scene.control.Menu
import javafx.scene.control.MenuItem
import javafx.scene.control.SelectionMode
import javafx.scene.control.SeparatorMenuItem
import javafx.scene.control.TableView
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The commits of the [pages] with their graph, newest first, loaded as the user scrolls; several can be selected. The
 * context menu copies hashes (also Shortcut+C), compares a commit with the working tree ([compareWithLocal], ADR 0044),
 * checks it out, creates branches and tags at it (ADR 0041), cherry-picks, reverts and resets to it (ADR 0042) through
 * [actions], and opens Go to ([openGoTo], ADR 0045). Escape ends a comparison ([stopComparing]); while it lists an
 * empty side, the placeholder says [emptySide]. Cherry-pick and revert are not offered while git is [busy] with
 * something.
 */
internal class CommitTable(
    private val pages: GitLogPages,
    emptySide: ObservableStringValue,
    busy: ObservableBooleanValue,
    private val actions: RefActions,
) : TableView<GitLogRow>(pages.rows) {
    /** Opens the files a commit differs in from the working tree (ADR 0044); set by the view. */
    var compareWithLocal: (GitCommit) -> Unit = {}

    /** Opens Go to Hash/Branch/Tag (ADR 0045); set by the view. */
    var openGoTo: () -> Unit = {}

    /** Ends the comparison shown (ADR 0044); set by the view. */
    var stopComparing: () -> Unit = {}

    /** The submenus for the branches at a commit (ADR 0063); set by the view, called when the menu opens. */
    var branchMenus: (List<GitRef>) -> List<Menu> = { emptyList() }

    /** The submenus and separator put in front of the fixed items the last time the menu opened. */
    private val branchItems = mutableListOf<MenuItem>()

    private val dateFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())

    /** Whether the refs read last have a `HEAD` commit, which a reset needs. */
    private val hasHead = SimpleBooleanProperty(false)

    private val copyRevisionNumber = MenuItem("Copy Revision Number").apply { setOnAction { copyRevisionNumbers() } }

    internal val goToItem = MenuItem("Go to Hash/Branch/Tag…").apply { setOnAction { openGoTo() } }

    internal val compareWithLocalItem =
        MenuItem("Compare with Local").apply { setOnAction { singleCommit()?.let { compareWithLocal(it) } } }

    internal val checkoutRevisionItem =
        MenuItem("Checkout Revision").apply {
            setOnAction {
                singleCommit()?.let {
                    actions.start(
                        CheckoutRevision(it.hash, it.hash.take(SHORT_HASH)),
                    )
                }
            }
        }
    internal val newBranchItem =
        MenuItem("New Branch…").apply {
            setOnAction {
                singleCommit()?.let {
                    actions.newBranch(
                        it.hash,
                        it.hash.take(SHORT_HASH),
                    )
                }
            }
        }
    internal val newTagItem =
        MenuItem(
            "New Tag…",
        ).apply { setOnAction { singleCommit()?.let { actions.newTag(it.hash) } } }
    internal val cherryPickItem =
        MenuItem(pickTitle(GitPick.CHERRY_PICK)).apply {
            setOnAction { singleCommit()?.let { actions.pick(it, GitPick.CHERRY_PICK) } }
        }
    internal val revertItem =
        MenuItem(pickTitle(GitPick.REVERT)).apply {
            setOnAction { singleCommit()?.let { actions.pick(it, GitPick.REVERT) } }
        }
    internal val resetItem =
        MenuItem("Reset Current Branch to Here…").apply { setOnAction { singleCommit()?.let(actions::resetTo) } }

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
        contextMenu =
            ContextMenu(
                copyRevisionNumber,
                compareWithLocalItem,
                SeparatorMenuItem(),
                checkoutRevisionItem,
                newBranchItem,
                newTagItem,
                SeparatorMenuItem(),
                cherryPickItem,
                revertItem,
                resetItem,
                SeparatorMenuItem(),
                goToItem,
            )
        contextMenu.setOnShowing {
            contextMenu.items.removeAll(branchItems)
            branchItems.clear()
            val refs =
                selectionModel.selectedItems
                    .singleOrNull()
                    ?.refs
                    .orEmpty()
                    .filter { it.kind == GitRefKind.LOCAL_BRANCH || it.kind == GitRefKind.REMOTE_BRANCH }
            val menus = branchMenus(refs)
            if (menus.isNotEmpty()) {
                branchItems += menus
                branchItems += SeparatorMenuItem()
                contextMenu.items.addAll(0, branchItems)
            }
        }
        copyRevisionNumber.disableProperty().bind(Bindings.isEmpty(selectionModel.selectedItems))
        // One commit, and no operation running.
        val notOne = Bindings.size(selectionModel.selectedItems).isNotEqualTo(1).or(actions.running)
        listOf(checkoutRevisionItem, newBranchItem, newTagItem).forEach { it.disableProperty().bind(notOne) }
        // Only reads: also while an operation runs.
        compareWithLocalItem.disableProperty().bind(Bindings.size(selectionModel.selectedItems).isNotEqualTo(1))
        // Not while git is in the middle of something: the bar says what.
        listOf(cherryPickItem, revertItem).forEach { it.disableProperty().bind(notOne.or(busy)) }
        resetItem.disableProperty().bind(notOne.or(hasHead.not()))
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

    /** The refs of the [pages] were read again (or dropped): a reset is offered only with a `HEAD` commit. */
    fun refsChanged() {
        hasHead.set(
            pages.refs
                ?.refs
                .orEmpty()
                .any { it.kind == GitRefKind.HEAD },
        )
    }

    /** The commit of the one selected row; `null` for none or several. */
    private fun singleCommit() =
        selectionModel.selectedItems
            .singleOrNull()
            ?.commit

    /** Copies the full hashes of the selected commits, one per line in table order. */
    private fun copyRevisionNumbers() {
        val hashes =
            selectionModel.selectedIndices
                .sorted()
                .map { pages.rows[it].commit.hash }
        if (hashes.isEmpty()) return
        Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString(hashes.joinToString("\n")) })
    }
}
