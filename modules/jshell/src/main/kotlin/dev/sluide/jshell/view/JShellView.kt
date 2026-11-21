package dev.sluide.jshell.view

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.fileviewers.CodeEditor
import dev.sluide.fileviewers.EditableFileView
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.fileviewers.SourceSplit
import dev.sluide.fileviewers.SplitMode
import dev.sluide.fileviewers.TextEditableView
import dev.sluide.fileviewers.ViewActivity
import dev.sluide.fileviewers.WrappingToolbar
import dev.sluide.jshell.Completions
import dev.sluide.jshell.JShellFileView
import dev.sluide.jshell.JShellJdk
import dev.sluide.jshell.JShellWindow
import dev.sluide.jshell.ui.TranscriptPane
import javafx.application.Platform
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.beans.value.ObservableValue
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.MenuButton
import javafx.scene.control.MenuItem
import javafx.scene.control.RadioMenuItem
import javafx.scene.control.TextInputControl
import javafx.scene.control.ToggleGroup
import javafx.scene.control.Tooltip
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyEvent
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.text.Font
import org.kordamp.ikonli.Ikon
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignD
import org.kordamp.ikonli.materialdesign2.MaterialDesignP
import org.kordamp.ikonli.materialdesign2.MaterialDesignR
import org.kordamp.ikonli.materialdesign2.MaterialDesignS
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/**
 * The view of a `.jsh` file (ADR 0191): the source on the left and the transcript of a live JShell on the right, a
 * toolbar with Run (the snippet at the caret or the selection), Run All, Stop, Restart, Clear and the JDK it runs on.
 * The runs are [JShellRuns]'s; this class is the controls around them. Without a [JShellWindow] (outside an IDE
 * window) nothing runs.
 */
internal class JShellView(
    path: Path,
    private val editor: CodeEditor,
) : EditableFileView by editor,
    TextEditableView,
    JShellFileView {
    /** The file; it changes when the file is [moved]. */
    var file: Path = path.toAbsolutePath().normalize()
        private set

    val transcript = TranscriptPane { line -> showLine(line.coerceIn(0, lastLine())) }

    val split = SourceSplit(editor.node, transcript.node, "Output", "jshell-mode", SplitMode.SPLIT)

    val runButton =
        toolbarButton(
            "Run",
            MaterialDesignP.PLAY,
            "jshell-run",
            "Run the snippet at the caret or the selection (${RUN_KEY.displayText})",
        )
    val runAllButton =
        toolbarButton(
            "Run All",
            MaterialDesignP.PLAY_BOX_MULTIPLE,
            "jshell-run-all",
            "Run All: restart and run the whole file (${RUN_ALL_KEY.displayText})",
        )
    val stopButton = toolbarButton("Stop", MaterialDesignS.STOP, "jshell-stop", "Stop the running snippet")
    val restartButton =
        toolbarButton(
            "Restart",
            MaterialDesignR.RESTART,
            "jshell-restart",
            "End the session; the next run starts a new one",
        )
    val clearButton = toolbarButton("Clear", MaterialDesignD.DELETE_SWEEP, "jshell-clear", "Clear the transcript")

    /** The JDK menu: its items are built on showing. */
    val jdkButton =
        MenuButton(JdkChoices.buttonText(null)).apply {
            styleClass += "jshell-jdk"
            isFocusTraversable = false
            minWidth = Region.USE_PREF_SIZE
            tooltip = Tooltip(JdkChoices.tooltip(null))
            setOnShowing { loadJdkItems() }
        }

    val toolbar =
        WrappingToolbar().apply {
            styleClass += "jshell-toolbar"
            leading.setAll(editor.softWrapButton, runButton, runAllButton, stopButton, restartButton, clearButton)
            trailing.setAll(listOf<Node>(jdkButton) + split.modeButtons)
        }

    private val root =
        BorderPane(split.node).apply {
            styleClass += "jshell-view"
            top = toolbar
            stylesheets += checkNotNull(JShellView::class.java.getResource(CSS)).toExternalForm()
            addEventFilter(KeyEvent.KEY_PRESSED) { event ->
                if (!inSource(event.target)) return@addEventFilter
                when {
                    RUN_ALL_KEY.match(event) -> {
                        event.consume()
                        runAll()
                    }

                    RUN_KEY.match(event) -> {
                        event.consume()
                        run()
                    }
                }
            }
        }

    override val node: Node get() = root

    private val activityWrapper = ReadOnlyObjectWrapper(ViewActivity.NONE)
    private val jdkWorker = NamedThreads.single("jshell-jdk")
    private var window: JShellWindow? = null
    private var runs: JShellRuns? = null
    private var jdkSubscription: AutoCloseable? = null
    private var resolved: JShellJdk? = null
    private var banner: AutoCloseable? = null
    private var dismissed: String? = null

    /** A Run or Run All that came before the JDK was known. */
    private var pending: ((JShellRuns) -> Unit)? = null
    private var disposed = false

    init {
        editor.softWrapButton.visibleProperty().bind(split.mode.map { it != SplitMode.OTHER })
        editor.softWrapButton.managedProperty().bind(editor.softWrapButton.visibleProperty())
        // The editor font the tab gives its content is for the source; the toolbar and the transcript's menus use the UI font.
        Font.getDefault().let { font ->
            toolbar.style = "-fx-font-family: \"${font.family}\"; -fx-font-size: ${font.size}px;"
        }
        runButton.onClick { run() }
        runAllButton.onClick { runAll() }
        stopButton.onClick { runs?.stop() }
        restartButton.onClick { runs?.restart() }
        clearButton.onClick { transcript.clear() }
        updateStates()
    }

    override val text: String get() = editor.text

    override val canReplaceText: Boolean get() = editor.canReplaceText

    override fun replaceText(text: String): Boolean = editor.replaceText(text)

    override val activity: ObservableValue<ViewActivity> get() = activityWrapper.readOnlyProperty

    override val closeWarning: String? get() =
        if (runs?.running ==
            true
        ) {
            "${file.fileName} is still running a JShell snippet."
        } else {
            null
        }

    override var host: FileViewHost?
        get() = editor.host
        set(value) {
            editor.host = value
            val found = value?.windowService(JShellWindow::class.java)
            if (found != null && window == null && !disposed) attach(found)
        }

    private fun attach(found: JShellWindow) {
        window = found
        runs =
            JShellRuns(
                transcript,
                { resolved },
                found.startSession,
                NamedThreads.single("jshell-session"),
                { Platform.runLater(it) },
            ) { updateStates() }
        jdkSubscription = found.onProjectJdkChanged { resolveJdk() }
        resolveJdk()
    }

    /** Looks the JDK up on a worker and shows it. */
    private fun resolveJdk() {
        val found = window ?: return
        val folder = host?.projectFolder
        val target = file
        jdkWorker.execute {
            val jdk = found.jdks.resolve(target, folder)
            Platform.runLater {
                if (!disposed) {
                    resolved = jdk
                    showJdk()
                    runs?.jdkChanged()
                    runPending()
                }
            }
        }
    }

    private fun showJdk() {
        jdkButton.text = JdkChoices.buttonText(resolved)
        jdkButton.tooltip = Tooltip(JdkChoices.tooltip(resolved))
        banner?.close()
        banner = null
        val text = JdkChoices.bannerText(resolved)
        if (text != null && text != dismissed) {
            banner = editor.addBanner(banner(text, resolved?.jdk == null))
        }
        updateStates()
    }

    private fun banner(
        text: String,
        noJdk: Boolean,
    ): Node {
        val action =
            when {
                noJdk && window?.canChooseProjectJdk?.invoke() != true -> {
                    null
                }

                noJdk -> {
                    Button("Choose JDK…").apply { setOnAction { window?.chooseProjectJdk?.invoke() } }
                }

                else -> {
                    Button("Dismiss").apply {
                        setOnAction {
                            dismissed = text
                            showJdk()
                        }
                    }
                }
            }?.apply {
                styleClass += "jshell-banner-action"
                minWidth = Region.USE_PREF_SIZE
            }
        return HBox(
            Label(text).apply {
                isWrapText = true
                minWidth = 0.0
                minHeight = Region.USE_PREF_SIZE
            },
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
        ).apply {
            action?.let { children += it }
            styleClass += listOf("editor-banner", "jshell-banner")
            alignment = Pos.CENTER_LEFT
        }
    }

    /** Builds the menu's items once the installed JDKs and the file's choice are read on a worker. */
    private fun loadJdkItems() {
        val found = window
        jdkButton.items.setAll(MenuItem("Looking for JDKs…").apply { isDisable = true })
        if (found == null) return
        val folder = host?.projectFolder
        val target = file
        jdkWorker.execute {
            val installed = found.installedJdks()
            val remembered = found.jdks.remembered(target, folder)
            val project = found.jdks.resolveProject()
            Platform.runLater {
                if (!disposed) {
                    jdkButton.items.setAll(
                        jdkItems(JdkChoices.items(remembered, installed, project)),
                    )
                }
            }
        }
    }

    private fun jdkItems(choices: List<JdkChoice>): List<MenuItem> {
        val group = ToggleGroup()
        return choices.map { choice ->
            RadioMenuItem(choice.label).apply {
                toggleGroup = group
                isSelected = choice.selected
                setOnAction { choose(choice.home) }
            }
        }
    }

    /** Remembers [home] for the file (null: the project's JDK), and uses it from the next run on. */
    private fun choose(home: Path?) {
        val found = window ?: return
        val folder = host?.projectFolder
        val target = file
        jdkWorker.execute {
            found.jdks.remember(target, folder, home)
            // a lookup for a closed view ends without showing anything (resolveJdk)
            Platform.runLater { resolveJdk() }
        }
    }

    /** A click does [action]; the focus stays in the source (or goes back to it). */
    private fun Button.onClick(action: () -> Unit) =
        setOnAction {
            action()
            if (split.mode.get() != SplitMode.OTHER) editor.focus()
        }

    private fun updateStates() {
        val current = runs
        runButton.isDisable = current?.canRun != true
        runAllButton.isDisable = current?.canRun != true
        stopButton.isDisable = current?.canStop != true
        // a JDK chosen under a running snippet would only wait for its end
        jdkButton.isDisable = current?.running == true
        activityWrapper.set(if (current?.running == true) ViewActivity.RUNNING else ViewActivity.NONE)
    }

    /** Runs the snippet at the caret, or the selected ones; shows the transcript beside the source. */
    private fun run() {
        val selection = editor.selection
        val text = editor.text
        request { it.run(text, selection.start, selection.end) }
    }

    /** Restarts the session and runs the whole text. */
    override fun runAll() {
        val text = editor.text
        request { it.runAll(text) }
    }

    /** Does [action] now, or once the JDK is known if it is not yet (a view that was just opened). */
    private fun request(action: (JShellRuns) -> Unit) {
        if (resolved == null) {
            pending = action
            return
        }
        perform(action)
    }

    private fun perform(action: (JShellRuns) -> Unit) {
        val current = runs ?: return
        if (!current.canRun) return
        showSplit()
        action(current)
    }

    private fun runPending() {
        val action = pending ?: return
        pending = null
        perform(action)
    }

    override fun complete(caret: Int): CompletableFuture<Completions> =
        runs?.complete(editor.text, caret)
            ?: CompletableFuture.failedFuture(IllegalStateException("JShell runs in an IDE window"))

    private fun showSplit() {
        if (split.mode.get() == SplitMode.SOURCE) split.mode.set(SplitMode.SPLIT)
    }

    private fun lastLine(): Int = editor.text.count { it == '\n' }

    /** Whether [target] is the source's text, not e.g. a field of its find bar. */
    private fun inSource(target: Any?): Boolean {
        if (target !is Node || target is TextInputControl) return false
        return generateSequence(target) { it.parent }.any { it === editor.node }
    }

    private fun toolbarButton(
        label: String,
        icon: Ikon,
        styleClass: String,
        tip: String,
    ) = Button(label, FontIcon(icon)).apply {
        this.styleClass += styleClass
        isFocusTraversable = false
        minWidth = Region.USE_PREF_SIZE
        tooltip = Tooltip(tip)
    }

    /** Focuses the source, or the transcript while it is shown alone. */
    override fun focus() {
        if (split.mode.get() != SplitMode.OTHER) return editor.focus()
        transcript.area.requestFocus()
    }

    override fun showLine(line: Int) = showPosition(line, 0)

    override fun showPosition(
        line: Int,
        column: Int,
    ) {
        if (split.mode.get() == SplitMode.OTHER) split.mode.set(SplitMode.SPLIT)
        editor.showPosition(line, column)
    }

    override fun focusText() {
        if (split.mode.get() == SplitMode.OTHER) transcript.area.requestFocus() else editor.focusText()
    }

    /** Follows the file to [to], keeping its session and unsaved changes. */
    override fun moved(to: Path): Boolean {
        if (!editor.moved(to)) return false
        file = to.toAbsolutePath().normalize()
        return true
    }

    override fun dispose() {
        disposed = true
        jdkSubscription?.close()
        banner?.close()
        runs?.dispose()
        editor.dispose()
    }

    private companion object {
        const val CSS = "/dev/sluide/jshell/jshell-view.css"
        val RUN_KEY = KeyCodeCombination(KeyCode.ENTER, KeyCombination.SHORTCUT_DOWN)
        val RUN_ALL_KEY = KeyCodeCombination(KeyCode.ENTER, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN)
    }
}
