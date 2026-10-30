package dev.sluide.httpclient.ui

import dev.sluide.fileviewers.CodeEditor
import dev.sluide.fileviewers.EditableFileView
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.httpclient.HttpClientSession
import dev.sluide.httpclient.curl.PrivateValuesAnswer
import dev.sluide.httpclient.env.Environment
import dev.sluide.httpclient.env.EnvironmentFiles
import dev.sluide.httpclient.env.Environments
import dev.sluide.httpclient.parse.HttpFileParser
import dev.sluide.httpclient.problemSummary
import dev.sluide.httpclient.send.Cancellable
import dev.sluide.httpclient.send.HttpFailure
import javafx.animation.PauseTransition
import javafx.beans.InvalidationListener
import javafx.beans.property.SimpleBooleanProperty
import javafx.geometry.Orientation
import javafx.geometry.Pos
import javafx.scene.Cursor
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.Menu
import javafx.scene.control.MenuButton
import javafx.scene.control.MenuItem
import javafx.scene.control.SeparatorMenuItem
import javafx.scene.control.SplitPane
import javafx.scene.control.TextInputControl
import javafx.scene.control.Tooltip
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.stage.Window
import javafx.util.Duration
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignC
import org.kordamp.ikonli.materialdesign2.MaterialDesignK
import org.kordamp.ikonli.materialdesign2.MaterialDesignP
import org.kordamp.ikonli.materialdesign2.MaterialDesignS
import java.nio.file.Path
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.Duration as JavaDuration

private val log = System.getLogger(HttpFileView::class.java.name)

/**
 * The view of a `.http` file: a toolbar above the file's [editor] and, once a request ran, its [response] below it.
 * Editing, saving, change markers and changes on disk are the editor's.
 *
 * The request at the caret runs from the toolbar or with Shortcut+Enter, any request from the run icon beside its
 * request line. Runs use the editor's current text, unsaved changes included, and the window's [session]; without one
 * (e.g. in tests of other modules) nothing can run.
 */
internal class HttpFileView(
    file: Path,
    private val editor: CodeEditor,
) : EditableFileView by editor {
    /** The file shown; it changes when the file is [moved]. */
    var file: Path = file.toAbsolutePath().normalize()
        private set

    val response = ResponsePane()

    val runButton =
        Button("Run", FontIcon(MaterialDesignP.PLAY)).apply {
            styleClass += "http-run"
            setOnAction { run(editor.caretLine) }
        }

    val cancelButton =
        Button("Cancel", FontIcon(MaterialDesignS.STOP)).apply {
            styleClass += "http-cancel"
            tooltip = Tooltip("Cancel the running request")
            isDisable = true
            setOnAction { running?.cancel() }
        }

    /** Whether [refreshEnvironments] fills the combo box, which is no choice of the user's. */
    private var refreshing = false

    /** "No environment" and the environments of the environment files; the choice is the window's, per file. */
    val environments =
        ComboBox<String>().apply {
            styleClass += "http-environment"
            tooltip = Tooltip("The environment whose variables the requests use")
            // Picks up environments added to the files since.
            setOnShowing { refreshEnvironments() }
            valueProperty().addListener { _, _, name ->
                if (!refreshing) session?.chooseEnvironment(file, name.takeIf { it != NO_ENVIRONMENT })
            }
        }

    /** Problems of the environment files, e.g. invalid JSON, or a private file that git could commit. */
    val warning =
        Label().apply {
            styleClass += "http-warning"
            isWrapText = true
            minWidth = 0.0
            maxWidth = Double.MAX_VALUE
            isVisible = false
            isManaged = false
        }

    /** Acquires or clears the OAuth2 tokens of the chosen environment's `Security.Auth` configurations. */
    val auth =
        MenuButton("Auth", FontIcon(MaterialDesignK.KEY_OUTLINE)).apply {
            styleClass += "http-auth"
            tooltip = Tooltip("OAuth2 tokens of the environment")
            setOnShowing {
                items.setAll(authItems())
                refreshEnvironments()
            }
        }

    val clearCookies =
        Button("Clear Cookies", FontIcon(MaterialDesignC.COOKIE_REMOVE_OUTLINE)).apply {
            styleClass += "http-clear-cookies"
            tooltip = Tooltip("Forget the cookies the window's requests received")
            setOnAction {
                session?.clients?.clearCookies()
                showMessage("Cookies cleared")
            }
        }

    /** What the last action of the toolbar did, e.g. that a token was acquired. */
    val message =
        Label().apply {
            styleClass += "http-message"
            isWrapText = false
        }

    val toolbar =
        HBox(runButton, cancelButton, environments, auth, clearCookies, message).apply {
            styleClass += "http-toolbar"
            alignment = Pos.CENTER_LEFT
            // The controls keep their size; the message after them is cut short when the view is narrow.
            for (control in listOf(runButton, cancelButton, environments, auth, clearCookies)) {
                control.minWidth = Region.USE_PREF_SIZE
            }
            message.minWidth = 0.0
        }

    /** The editor and, once a request ran, the response below it. */
    val split = SplitPane(editor.node).apply { orientation = Orientation.VERTICAL }

    private val root =
        BorderPane(split).apply {
            styleClass += "http-view"
            // Warnings get a row of their own below the toolbar, so they are never cut off.
            top = VBox(toolbar, warning)
            stylesheets += checkNotNull(HttpFileView::class.java.getResource("http-view.css")).toExternalForm()
            addEventFilter(KeyEvent.KEY_PRESSED) { event ->
                if (RUN_KEY.match(event) && inText(event.target)) {
                    event.consume()
                    run(editor.caretLine)
                }
            }
        }

    override val node: Node get() = root

    /** Whether [target] is the editor's text, not e.g. a field of its find bar. */
    private fun inText(target: Any?): Boolean {
        if (target !is Node || target is TextInputControl) return false
        return generateSequence(target) { it.parent }.any { it === editor.node }
    }

    /** The running request, `null` when none runs. */
    private var running: Cancellable? = null
    private val isRunning = SimpleBooleanProperty(false)

    /** The request lines of the text as last parsed, which get a run icon. */
    private var requestLines: Set<Int> = requestLines(editor.text)

    /** Parses the text again once typing pauses, to move the run icons. */
    private val reparse =
        PauseTransition(Duration.millis(REPARSE_PAUSE.toMillis().toDouble())).apply {
            setOnFinished {
                val lines = requestLines(editor.text)
                if (lines != requestLines) {
                    requestLines = lines
                    editor.refreshGutter()
                }
            }
        }

    /** Asks whether Copy as cURL puts private values in; replaced in tests. */
    internal var askPrivateValues: (Window?, List<String>) -> PrivateValuesAnswer = ::askToIncludePrivateValues

    /** Copy as cURL and Paste cURL as Request in the editor's context menu. */
    private val curl =
        CurlActions(editor, { file }, { session }, { host?.projectFolder }, ::showMessage) { owner, names ->
            askPrivateValues(owner, names)
        }

    init {
        // Invalidations only: the text is built once typing pauses, not on every key.
        editor.textProperty.addListener(InvalidationListener { reparse.playFromStart() })
        editor.addGutterColumn(RUN_ICON_COLUMN, ::runColumn)
        editor.addContextMenuItems(curl::items)
        isRunning.addListener { _, _, running -> cancelButton.isDisable = !running }
        updateRunnable()
        refreshEnvironments()
    }

    /** Gives the editor the host too, which asks it for diffs when a change marker is clicked. */
    override var host: FileViewHost?
        get() = editor.host
        set(value) {
            editor.host = value
            updateRunnable()
            gitSubscription?.close()
            gitSubscription = session?.onGitStatusChanged(::updateWarning)
            refreshEnvironments()
        }

    /** Asks again whether git would commit the private environment file when the window's git status changes. */
    private var gitSubscription: AutoCloseable? = null

    /** The window's session, through the [host]; `null` without one (e.g. in tests). */
    val session: HttpClientSession? get() = host?.windowService(HttpClientSession::class.java)

    /**
     * Follows the file to [to] with the editor, if it can: requests then run from there (body files, environment
     * files), and the environment chosen for the file in the window moves along.
     */
    override fun moved(to: Path): Boolean {
        if (!editor.moved(to)) return false
        val from = file
        file = to.toAbsolutePath().normalize()
        session?.moveEnvironment(from, file)
        return true
    }

    /**
     * Runs the request whose part of the file holds [line] with the current text; a request still running is
     * cancelled first.
     */
    fun run(line: Int) {
        val session = session ?: return
        val text = editor.text
        showResponsePane()
        // A new run replaces the one still running, also when there is nothing to run.
        running?.cancel()
        running = null
        val run = ++runs
        val request = HttpFileParser.parse(text).requestAt(line)
        if (request == null) {
            isRunning.set(false)
            val failure = HttpFailure("There is no request at line ${line + 1}", false, JavaDuration.ZERO, emptyList())
            response.show(ResponseContent.of(failure))
            return
        }
        // The list follows the files as they are now.
        refreshEnvironments()
        response.running()
        isRunning.set(true)
        val environment = environmentOf(session)
        running =
            session.runner.run(file, text, request, environment) { result ->
                // The result of a run replaced by a newer one is not shown.
                if (run != runs) return@run
                running = null
                isRunning.set(false)
                // Decoding and formatting a body can take a while: off the FX thread.
                session.inBackground({ ResponseContent.of(result) }) { content ->
                    if (run == runs) {
                        content.fold(response::show) { failure ->
                            log.log(System.Logger.Level.ERROR, "Could not format the response of $file", failure)
                            // Shown like a failed request, so that the pane does not stay on "Running…".
                            val reason = failure.message ?: failure::class.simpleName
                            val message = "Could not display the response: $reason"
                            response.show(ResponseContent.of(HttpFailure(message, false, result.elapsed, emptyList())))
                        }
                    }
                }
            }
    }

    /** Counts the runs, so that only the result of the last one is shown. */
    private var runs = 0

    /**
     * The environment chosen for this file in the window, to be read anew from the environment files on the worker
     * that prepares the request.
     */
    private fun environmentOf(session: HttpClientSession): () -> Environment? {
        val name = session.environment(file) ?: return { null }
        val file = file
        val folder = host?.projectFolder
        return { Environments.load(EnvironmentFiles.find(file, folder))[name] }
    }

    /** The environment files of the file and their environments as last read ([refreshEnvironments]). */
    private var files: EnvironmentFiles? = null
    private var loaded = Environments(emptyList(), emptyList())

    /** Counts the reads of the environment files, so that only the last one is shown. */
    private var refreshes = 0

    /**
     * Reads the environment files that apply to the file again, off the FX thread; then offers their environments, with
     * the one chosen for the file in the window selected, and shows their problems. Until then the previous list stays.
     */
    private fun refreshEnvironments() {
        val session = session ?: return
        val file = file
        val folder = host?.projectFolder
        val refresh = ++refreshes
        val read = {
            val files = EnvironmentFiles.find(file, folder)
            files to Environments.load(files)
        }
        session.inBackground(read) { result ->
            if (refresh == refreshes) {
                result
                    .onSuccess { (files, loaded) -> showEnvironments(files, loaded) }
                    .onFailure {
                        log.log(
                            System.Logger.Level.WARNING,
                            "Could not read the environment files of $file",
                            it,
                        )
                    }
            }
        }
    }

    private fun showEnvironments(
        files: EnvironmentFiles,
        loaded: Environments,
    ) {
        this.files = files
        this.loaded = loaded
        if (auth.isShowing) auth.items.setAll(authItems())
        val chosen = session?.environment(file)
        refreshing = true
        try {
            environments.items.setAll(listOf(NO_ENVIRONMENT) + loaded.names)
            environments.value = chosen?.takeIf { it in loaded.names } ?: NO_ENVIRONMENT
        } finally {
            refreshing = false
        }
        updateWarning()
    }

    /**
     * Shows the problems of the environment files as last read; first of all, a private file that git could commit,
     * which is asked again whenever the window's git status changes.
     */
    private fun updateWarning() {
        val committable = files?.private?.takeIf { session?.wouldCommit?.invoke(it) == true }
        showProblems(
            listOfNotNull(committable?.let { "${it.fileName} is not ignored by git: its secrets could be committed" }) +
                loaded.problems +
                loaded.environments.flatMap { env -> env.problems.map { "${env.name}: $it" } },
        )
    }

    private fun showProblems(problems: List<String>) {
        val (summary, all) = problemSummary(problems)
        warning.text = summary
        warning.tooltip = all?.let(::Tooltip)
        warning.isVisible = problems.isNotEmpty()
        warning.isManaged = problems.isNotEmpty()
    }

    /**
     * One submenu per auth configuration of the chosen environment, with Acquire and Clear; then Clear All. Built when
     * the menu opens, from the environment files as last read, and again once they were read anew.
     */
    private fun authItems(): List<MenuItem> {
        val session = session ?: return emptyList()
        val environment = session.environment(file)?.let { loaded[it] }
        val configs =
            environment?.auth?.keys?.map { id ->
                Menu(
                    id,
                    null,
                    MenuItem("Acquire Token").apply { setOnAction { acquire(session, id) } },
                    MenuItem("Clear Token").apply {
                        setOnAction {
                            session.tokens.clear(id)
                            showMessage("Token '$id' cleared")
                        }
                    },
                )
            }
        val noneText = environment?.let { "No Security.Auth in ${it.name}" } ?: "Choose an environment first"
        val none = MenuItem(noneText).apply { isDisable = true }
        val clearAll =
            MenuItem("Clear All Tokens").apply {
                setOnAction {
                    session.tokens.clearAll()
                    showMessage("All tokens cleared")
                }
            }
        return (configs?.takeIf { it.isNotEmpty() } ?: listOf(none)) + SeparatorMenuItem() + clearAll
    }

    /**
     * Requests a new token for auth [id] of the chosen environment in the background, with the environment files read
     * anew there, and tells how that went.
     */
    private fun acquire(
        session: HttpClientSession,
        id: String,
    ) {
        showMessage("Acquiring token '$id'…")
        val environment = environmentOf(session)
        session.inBackground({ session.tokens.acquire(environment(), id) }) { result ->
            result
                .onSuccess { info ->
                    val expiry = info.expiresAt?.let { " (expires ${TIME.format(it)})" }.orEmpty()
                    showMessage("Token '$id' acquired$expiry")
                }.onFailure {
                    // Logged by the token request's background task (ADR 0061).
                    showMessage(
                        "Token '$id' not acquired: ${it.message ?: it::class.simpleName}",
                        error = true,
                    )
                }
        }
    }

    private fun showMessage(
        text: String,
        error: Boolean = false,
    ) {
        // Long texts (e.g. errors) are cut short, so the toolbar keeps its controls; the tooltip has all of it.
        message.text = truncated(text, MESSAGE_LENGTH)
        message.styleClass.remove("http-message-error")
        if (error) message.styleClass += "http-message-error"
        message.tooltip = Tooltip(text)
    }

    private fun showResponsePane() {
        if (response.node in split.items) return
        // Neither side may insist on its content's height, which would push the divider to the edge.
        for (item in listOf(editor.node, response.node)) if (item is Region) item.minHeight = 0.0
        split.items += response.node
        split.setDividerPositions(0.5)
    }

    private fun updateRunnable() {
        val runnable = session != null
        runButton.isDisable = !runnable
        environments.isDisable = !runnable
        auth.isDisable = !runnable
        clearCookies.isDisable = !runnable
        runButton.tooltip =
            Tooltip(if (runnable) "Run the request at the caret ($RUN_KEY_TEXT)" else "Requests run in an IDE window")
    }

    private fun runColumn(paragraph: Int): Node? = if (paragraph in requestLines) runIcon(paragraph) else null

    private fun runIcon(paragraph: Int): Node =
        FontIcon(MaterialDesignP.PLAY).apply {
            styleClass += "http-run-icon"
            cursor = Cursor.HAND
            Tooltip.install(this, Tooltip("Run this request"))
            // Not pressed into the text: the caret stays where it is.
            addEventHandler(MouseEvent.MOUSE_PRESSED) { it.consume() }
            addEventHandler(MouseEvent.MOUSE_CLICKED) { event ->
                if (event.button == MouseButton.PRIMARY) run(paragraph)
                event.consume()
            }
        }

    override fun dispose() {
        running?.cancel()
        gitSubscription?.close()
        reparse.stop()
        editor.dispose()
    }

    private companion object {
        const val NO_ENVIRONMENT = "No environment"
        const val MESSAGE_LENGTH = 80
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
        val RUN_KEY = KeyCodeCombination(KeyCode.ENTER, KeyCombination.SHORTCUT_DOWN)
        val RUN_KEY_TEXT: String = RUN_KEY.displayText
        val REPARSE_PAUSE: JavaDuration = JavaDuration.ofMillis(300)

        /** The width of the gutter column of the run icons. */
        const val RUN_ICON_COLUMN = 16.0

        fun requestLines(text: String): Set<Int> =
            HttpFileParser
                .parse(text)
                .requests
                .map { it.requestLine }
                .toSet()
    }
}
