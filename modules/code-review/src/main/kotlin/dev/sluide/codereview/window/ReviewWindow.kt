package dev.sluide.codereview.window

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.codereview.ResolvedReview
import dev.sluide.codereview.ReviewBase
import dev.sluide.codereview.ReviewScope
import dev.sluide.codereview.ReviewSource
import dev.sluide.codereview.list.ReviewDiffView
import dev.sluide.codereview.list.createDiffView
import dev.sluide.codereview.model.ReviewDocument
import dev.sluide.codereview.model.ReviewLayout
import dev.sluide.codereview.model.ReviewLineSource
import dev.sluide.codereview.model.ReviewRow
import dev.sluide.codereview.model.ReviewSelection
import dev.sluide.fileviewers.SyntaxHighlighter
import dev.sluide.fileviewers.WrappingToolbar
import dev.sluide.settings.FontArea
import dev.sluide.settings.FontSettings
import dev.sluide.settings.FontSpec
import javafx.application.Platform
import javafx.beans.property.ObjectProperty
import javafx.beans.property.ReadOnlyBooleanProperty
import javafx.beans.property.ReadOnlyObjectProperty
import javafx.collections.ObservableList
import javafx.geometry.Rectangle2D
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.SplitPane
import javafx.scene.control.ToggleButton
import javafx.scene.control.ToggleGroup
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.stage.Stage
import javafx.stage.Window
import java.nio.file.Path
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService

/**
 * One review in a top-level window of its own, like the working-tree window: a toolbar, a notice bar for a stale
 * review, a slot for the find bar, and the files pane beside the diff (ADR 0168). [show] opens it and starts loading.
 * The window only builds the shell and wires the [ReviewLoader]; the toolbar's leading nodes, the files pane and the
 * find bar's slot are filled by other parts. [bounds] is where the window starts, [onClosed] tells whoever keeps it.
 * Its executor runs the loads and ends with the window; the settings are written by [prefsWriter].
 */
internal class ReviewWindow(
    val root: Path,
    private val scope: ReviewScope,
    ideWindow: Window?,
    bounds: Rectangle2D,
    source: ReviewSource,
    fonts: FontSettings,
    private val prefs: ReviewPrefs,
    highlighterFor: (Path, String) -> SyntaxHighlighter?,
    val actions: ReviewActions,
    private val onClosed: (ReviewWindow) -> Unit = {},
    diffViewFactory: (ObservableList<ReviewRow>, ReviewLineSource) -> ReviewDiffView = ::createDiffView,
    private val executor: ExecutorService = NamedThreads.single("sluide-review"),
    private val prefsWriter: Executor = NamedThreads.single("sluide-review-prefs"),
    dispatch: (() -> Unit) -> Unit = { Platform.runLater(it) },
) {
    val document = ReviewDocument()
    val diffView: ReviewDiffView = diffViewFactory(document.rows, document)

    private val loader: ReviewLoader
    internal val files = FilesPane(document, diffView)
    internal val pickers: ReviewPickers

    /** The resolved scope; `null` until it is there. */
    val resolved: ReadOnlyObjectProperty<ResolvedReview?>

    /** The bases a branch review can pick, filled for branch scopes. */
    val bases: ObservableList<ReviewBase>

    /** The picked commits; setting it loads that range, and each resolve resets it to all. */
    val selection: ObjectProperty<ReviewSelection>

    private val folderName = root.fileName?.toString() ?: root.toString()

    private val toolbar = WrappingToolbar().apply { styleClass += "review-toolbar" }

    /** The leading nodes of the toolbar, for the pickers. */
    val toolbarLeading: ObservableList<Node> = toolbar.leading

    /** The left side of the split pane, for the files pane. */
    val filesPane = StackPane().apply { styleClass += "review-files-pane" }

    /** Under the notice bar, for the find bar. */
    val findBarSlot = VBox()

    internal val hideWhitespace =
        ToggleButton("Hide whitespace").apply {
            isFocusTraversable = false
            styleClass += "review-toggle"
        }
    private val layoutToggles = ToggleGroup()
    internal val layoutButtons: Map<ReviewLayout, ToggleButton> =
        linkedMapOf(ReviewLayout.UNIFIED to "Unified", ReviewLayout.SPLIT to "Split").mapValues { (layout, label) ->
            ToggleButton(label).apply {
                toggleGroup = layoutToggles
                userData = layout
                isFocusTraversable = false
                styleClass += "review-toggle"
            }
        }

    internal val noticeBar =
        HBox(Label("The reviewed changes have changed.")).apply {
            styleClass += "review-notice-bar"
        }
    internal val reloadButton = Button("Reload").apply { isFocusTraversable = false }
    internal val overlay = Label().apply { styleClass += "review-overlay" }

    val stage: Stage

    init {
        val saved = prefs.read()
        document.layout.set(saved.layout)
        document.ignoreWhitespace.set(saved.hideWhitespace)

        loader =
            ReviewLoader(
                source,
                root,
                document,
                object : ReviewViewport {
                    override fun firstVisible() = diffView.firstVisible()

                    override fun scrollToRow(
                        index: Int,
                        offset: Double,
                    ) = diffView.scrollToRow(index, offset)
                },
                highlighterFor,
                executor,
                dispatch,
            )
        resolved = loader.resolved
        bases = loader.bases
        selection = loader.selection

        wireDiffView(fonts)
        wireToolbar()
        pickers = ReviewPickers(scope, resolved, bases, selection, ::changeBase)
        toolbarLeading.setAll(pickers.nodes)
        filesPane.children += files.node
        stage = createStage(ideWindow, bounds)
    }

    /** Shows the window and starts loading the review. */
    fun show() {
        stage.show()
        loader.open(scope)
    }

    /** Whether a load is running. */
    val loading: ReadOnlyBooleanProperty get() = loader.loading

    /** Resolves `Branch(head, ref)` again; only for a branch scope. */
    fun changeBase(ref: String) = loader.changeBase(ref)

    /** Resolves the review again and keeps the place in the diff. */
    fun reload() = loader.reload()

    /** Checks whether the reviewed changes changed; the notice bar shows when they did. */
    fun checkStale() = loader.checkStale()

    fun close() = stage.close()

    private fun wireDiffView(fonts: FontSettings) {
        diffView.onExpand = document::expand
        diffView.onViewedChanged = document::setViewed
        diffView.onCollapsedChanged = document::setCollapsed
        diffView.onLoadDiff = document::loadDiff
        diffView.node.styleProperty().bind(fonts.font(FontArea.EDITOR).map(FontSpec::toCss))
        diffView.node.visibleProperty().bind(loader.message.isNull)
        overlay.textProperty().bind(loader.message)
        overlay.visibleProperty().bind(loader.message.isNotNull)
    }

    private fun wireToolbar() {
        hideWhitespace.selectedProperty().bindBidirectional(document.ignoreWhitespace)

        layoutToggles.selectToggle(layoutButtons.getValue(document.layout.get()))
        layoutToggles.selectedToggleProperty().addListener { _, old, new ->
            // A layout is always selected: clicking the selected button keeps it.
            if (new == null) {
                layoutToggles.selectToggle(old)
            } else {
                val layout = new.userData as ReviewLayout
                if (document.layout.get() != layout) loader.keepingPlace { document.layout.set(layout) }
            }
        }
        document.layout.addListener { _, _, layout ->
            layoutToggles.selectToggle(layoutButtons.getValue(layout))
            savePrefs { prefs.write(layout = layout) }
        }
        document.ignoreWhitespace.addListener { _, _, hide -> savePrefs { prefs.write(hideWhitespace = hide) } }
        toolbar.trailing.setAll(hideWhitespace, *layoutButtons.values.toTypedArray())

        reloadButton.setOnAction { loader.reload() }
        noticeBar.children += reloadButton
        noticeBar.visibleProperty().bind(loader.stale)
        noticeBar.managedProperty().bind(noticeBar.visibleProperty())
    }

    /** Writes a changed setting off the FX thread, on the writer of all windows, not behind a running load. */
    private fun savePrefs(write: () -> Unit) {
        prefsWriter.execute(write)
    }

    private fun createStage(
        ideWindow: Window?,
        bounds: Rectangle2D,
    ): Stage {
        val diffArea = StackPane(diffView.node, overlay)
        val split = SplitPane(filesPane, diffArea).apply { setDividerPositions(FILES_SHARE) }
        VBox.setVgrow(split, Priority.ALWAYS)
        val content = VBox(toolbar, noticeBar, findBarSlot, split).apply { styleClass += "review-window" }
        content.stylesheets += REVIEW_WINDOW_CSS

        return Stage().apply {
            (ideWindow as? Stage)?.let { icons.setAll(it.icons) }
            title = "Review – $folderName"
            scene = Scene(content, bounds.width, bounds.height)
            x = bounds.minX
            y = bounds.minY
            width = bounds.width
            height = bounds.height
            scene.accelerators[CLOSE] = Runnable { close() }
            scene.accelerators[FILTER] = Runnable { files.focusFilter() }
            resolved.addListener { _, _, review ->
                if (review != null) title = "Review – ${review.title} – $folderName"
            }
            focusedProperty().addListener { _, _, focused -> if (focused) loader.checkStale() }
            setOnHidden {
                loader.close()
                executor.shutdown()
                onClosed(this@ReviewWindow)
            }
        }
    }

    private companion object {
        const val FILES_SHARE = 0.25
        val CLOSE = KeyCodeCombination(KeyCode.W, KeyCombination.SHORTCUT_DOWN)
        val FILTER = KeyCodeCombination(KeyCode.P, KeyCombination.SHORTCUT_DOWN)
    }
}

internal val REVIEW_WINDOW_CSS: String =
    checkNotNull(ReviewWindow::class.java.getResource("/dev/sluide/codereview/review-window.css")).toExternalForm()
