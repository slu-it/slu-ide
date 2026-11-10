package dev.sluide.fileviewers.code

import dev.sluide.fileviewers.DiskContent
import dev.sluide.fileviewers.EditableFileView
import dev.sluide.fileviewers.FileContent
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.fileviewers.FocusWhenShown
import dev.sluide.fileviewers.GutterColumns
import dev.sluide.fileviewers.SyntaxHighlighter
import dev.sluide.fileviewers.TextDiff
import dev.sluide.fileviewers.TextEditableView
import dev.sluide.fileviewers.ViewActivity
import dev.sluide.textediting.CommentSyntax
import dev.sluide.textediting.DiskChange
import dev.sluide.textediting.IndentStyle
import dev.sluide.textediting.LineEnding
import dev.sluide.textediting.TextEncoding
import dev.sluide.textediting.TextReplacement
import javafx.application.Platform
import javafx.beans.binding.Bindings
import javafx.beans.property.ReadOnlyStringProperty
import javafx.beans.property.ReadOnlyStringWrapper
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleStringProperty
import javafx.beans.value.ObservableBooleanValue
import javafx.beans.value.ObservableValue
import javafx.collections.ObservableList
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import org.fxmisc.flowless.VirtualizedScrollPane
import org.fxmisc.richtext.CodeArea
import org.fxmisc.richtext.util.UndoUtils
import org.fxmisc.wellbehaved.event.Nodes
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.Duration

/**
 * Editable view of [file] as code highlighted by [highlighter] (plain text without one), with line numbers and
 * markers of the lines changed against an original ([showChanges]) in its [Gutter]. Used by the code viewers, for
 * plain text and as the source side of other views, e.g. the Markdown preview.
 *
 * The area holds the text with `\n` line breaks; [save] writes it with the file's [LineEnding]. The view is [dirty]
 * while its undo history is away from where the file was last read or saved; undo only covers the text, not the
 * highlighting. A file that is not valid UTF-8 is read and saved as Windows-1252 ([FileEncoding], ADR 0098), with a
 * banner offering to convert it to UTF-8; saving fails rather than replace a character Windows-1252 cannot store. A
 * file that is neither is shown read-only, with a banner, and never saved: its invalid bytes show as replacement
 * characters, which saving would write back. The encoding follows the file on disk when it is read again, and stays
 * when the file is [moved].
 *
 * The file's bytes are read and saved only through [content] (the file on disk by default); content from elsewhere
 * (e.g. decrypted) makes [file] a virtual path, used for its name and folder only. Extensions skip content that is
 * [FileContent.confidential] unless they opt in.
 *
 * Edited text is highlighted again once typing pauses, on a background thread; the result is handed to the FX thread
 * through [dispatch] (`Platform.runLater`, replaced in tests), and dropped if the text changed meanwhile. Only the
 * range whose styles changed is restyled.
 *
 * When the file changes on disk ([reload]), a view without unsaved changes shows the new text, changing only what
 * differs, so caret, scroll position and undo history stay. A view with unsaved changes keeps them and shows a banner
 * offering to reload the file or to keep its own text, which the next save then writes over the file's; until then the
 * view stays dirty, even when undone back to the text saved last. When the file is deleted, a banner says so; saving
 * creates it again.
 *
 * A file that a tool generates, like `uv.lock` ([GeneratedFiles]), gets a banner naming the file to change instead; it
 * can be dismissed for the view, and is decided again when the file is [moved] to a name of another generated file or
 * none. The file stays editable.
 *
 * Keys for editing ([editorKeys]) indent in the file's [indentStyle] (from `.editorconfig`, else detected, else
 * [defaultIndent]) and comment out lines with [comment]. [highlightPause] and [followPause] (find bar) are how long
 * typing pauses before the text is highlighted and the matches are found again; tests shorten them.
 *
 * Extensions (ADR 0108, [extensible]) add [toolbarItems], banners below the view's own, locks that make the text
 * read-only, overlay layers over all other styles and activities shown as the view's [activity], and get what language
 * features need (ADR 0141): text changes, edits, positions, key bindings and saves. A view with
 * [ownToolbar] (one of its own, not embedded) always shows a row at the very top: the Soft Wrap toggle ([softWrap],
 * ADR 0130), then the toolbar items. An embedding view places [softWrapButton] in its own toolbar.
 */
internal class CodeView(
    initialFile: Path,
    private val highlighter: SyntaxHighlighter?,
    comment: CommentSyntax = CommentSyntax.NONE,
    private val defaultIndent: IndentStyle = IndentStyle.DEFAULT,
    highlightPause: Duration = HIGHLIGHT_PAUSE,
    followPause: Duration = FOLLOW_PAUSE,
    ownToolbar: Boolean = false,
    macKeys: Boolean = onMac,
    content: FileContent = DiskContent(initialFile),
    private val dispatch: (Runnable) -> Unit = Platform::runLater,
) : EditableFileView,
    TextEditableView,
    GutterColumns {
    /** The file shown; it changes when the file is [moved]. Read and written only through [content]. */
    private var file = initialFile

    /** Where the file's bytes are read from and saved to; follows the file when it is [moved] if it is on disk. */
    private var content = content

    /** The name of [file], for the banners. */
    private val fileName = SimpleStringProperty(file.fileName.toString())

    /** The RichTextFX area showing the text, for views that embed this one. */
    val area = CodeArea()

    /** Line numbers, change markers ([showChanges]) and further columns ([addGutterColumn]). */
    private val gutter = Gutter(area) { line -> host?.showChanges(file, line) }

    /** Whether long lines wrap, following the window's switch from the first [host] that has it (ADR 0130). */
    val softWrap = SoftWrapToggle(area)

    /** Toolbar items, banners, locks, overlay layers and activities of the extensions. */
    private val additions = CodeViewAdditions(area, ownToolbar, listOf(softWrap.button), ::showEditable)

    /** The scroll of every jump in the view: Go to Line, opening at a line, the extensions' jumps. */
    private val jumpScroll = JumpScroll(area)

    /** What features of the window see of the view and add to it (ADR 0108), from the first [host] that has them. */
    val extensible = ExtensibleCodeView(this, area, initialFile, additions, jumpScroll, highlighter == null)
    private val extensions = CodeViewExtensions(extensible, content.confidential, extensible::attached)
    private val contextMenu = EditorContextMenu(area, extensible::contextMenuItems, listOf(softWrap.menuItem))

    /**
     * Shows the changes when a change marker is clicked; the first one with [extensions] extends the view, the first
     * one with the soft wrap switch sets whether it wraps.
     */
    override var host: FileViewHost? = null
        set(value) {
            field = value
            softWrap.attach(value)
            extensions.attach(value)
        }

    // The default undo manager would also undo highlighting.
    private val undoManager = UndoUtils.plainTextUndoManager(area).also { area.undoManager = it }
    private val pane =
        VirtualizedScrollPane(area).apply {
            styleClass += "code-view"
            stylesheets +=
                checkNotNull(CodeView::class.java.getResource("/dev/sluide/fileviewers/syntax.css")).toExternalForm()
        }

    /** Banners above the text, e.g. [conflictBanner]. */
    private val banners = VBox()
    private val root =
        BorderPane(pane).apply {
            // Unlike `code-view`, which the scroll panes of diff views have too.
            styleClass += "code-editor"
            bottom = additions.statusBar
            top = VBox(*listOfNotNull(additions.toolbar.row, banners, additions.banners).toTypedArray())
            stylesheets +=
                checkNotNull(CodeView::class.java.getResource("/dev/sluide/fileviewers/code-view.css")).toExternalForm()
        }

    /** Finds in the text; shown above the banners. */
    val findBar = FindBar(area, banners, followPause)

    /** Moves the caret to a line the user enters. */
    val goToLine = GoToLine(area, jumpScroll)

    /** The styles of the text: tokens, masks, find matches and the extensions' overlay layers. */
    private val styles = CodeViewStyles(area, highlighter, findBar, additions.layers)

    /**
     * The ranges of a text that are styled `masked` over the token styles, below the find bar's matches, e.g. the
     * values of `.env` files ([dev.sluide.fileviewers.env.EnvFileView]); applied with every highlighting, and by
     * [restyleMasks].
     */
    var maskedRanges: ((text: String) -> List<IntRange>)? by styles::maskedRanges

    private val savedTextWrapper = ReadOnlyStringWrapper()

    /** The file's text as last read or saved, with `\n` line breaks; also what the user kept over a change on disk. */
    val savedTextProperty: ReadOnlyStringProperty = savedTextWrapper.readOnlyProperty

    /** The current value of [savedTextProperty]. */
    var savedText: String
        get() = savedTextWrapper.get()
        private set(value) = savedTextWrapper.set(value)

    /** The line ending the file is saved with. */
    private var lineEnding: LineEnding

    /** The file's content on disk when it last changed while the view had unsaved changes. */
    private var conflict: DecodedContent? = null

    /** Whether the user kept the unsaved text over a change on disk ([keepMine]), which only a save writes. */
    private val keptMine = SimpleBooleanProperty(false)

    /** Shown while the file changed on disk and the view has unsaved changes. */
    private val conflictBanner =
        banner(
            fileName.map { "$it changed on disk." },
            Button("Reload").apply { setOnAction { answered { conflict?.let(::load) } } },
            Button("Keep mine").apply { setOnAction { answered(::keepMine) } },
        )

    /** Shown while the file is not valid UTF-8 nor Windows-1252, and so read-only. */
    private val invalidBanner = banner(fileName.map { "$it is not valid UTF-8; it is shown read-only." })

    /** The encoding the file is saved in. */
    private val encoding = FileEncoding(TextEncoding.UTF_8)

    /** Shown while the file is Windows-1252, until converted to UTF-8; there is no dismissing it, saving needs it. */
    private val encodingBanner =
        banner(
            fileName.map { "$it is not valid UTF-8: it is opened and saved as Windows-1252." },
            Button("Convert to UTF-8").apply {
                // Converting makes the view dirty, which a read-only lock then cannot save.
                disableProperty().bind(additions.locks.readOnlyMarked)
                setOnAction {
                    encoding.convert()
                    showEncoding()
                    area.requestFocus()
                }
            },
        ).apply { styleClass += "encoding-banner" }

    /** Shown while the file is a generated one like `uv.lock`, until dismissed; the file stays editable. */
    private val generatedBanner = GeneratedFileBanner(banners, fileName, { findBar.isOpen }, area::requestFocus)

    /** Runs a banner's [action]; the focus goes back to the text, rather than wherever the vanished button sends it. */
    private fun answered(action: () -> Unit) {
        action()
        // The file's text is the saved text now, which a diff given meanwhile is of.
        changeMarkers.showPending()
        area.requestFocus()
    }

    private var disposed = false
    private val pendingFocus = FocusWhenShown()
    private val highlighting: BackgroundHighlighting

    /** The change markers shown in the [gutter], following unsaved edits. */
    private val changeMarkers: ChangeMarkerFollower

    /** Shown while the file does not exist, e.g. after it was deleted while the view had unsaved changes. */
    private val deletedBanner = banner(fileName.map { "$it was deleted on disk; saving recreates it." })

    /** How the editing keys indent, from `.editorconfig` or the text as opened; again when the file is [moved]. */
    var indentStyle: IndentStyle
        private set

    init {
        val read = decodeContent(content.read())
        lineEnding = LineEnding.of(read.raw)
        savedText = LineEnding.normalize(read.raw)
        show(savedText)
        showReadOnly(!read.valid)
        encoding.read(read.encoding)
        showEncoding()
        generatedBanner.show(file)
        undoManager.forgetHistory()
        undoManager.mark()
        indentStyle = IndentStyle.forFile(file, savedText, defaultIndent)
        installMacTextKeys(area, macKeys)
        Nodes.addInputMap(area, editorKeys(area, { indentStyle }, comment))
        Nodes.addInputMap(area, findBar.keys)
        Nodes.addInputMap(area, goToLine.keys)
        Nodes.addInputMap(area, softWrap.keys)
        // Following the caret keeps the area at the top when it is first laid out; without it, the source side of the
        // Markdown view (in a SplitPane) opened scrolled down.
        area.moveTo(0)
        area.requestFollowCaret()
        val pause = if (savedText.length > LARGE_TEXT) highlightPause.multipliedBy(4) else highlightPause
        highlighting =
            BackgroundHighlighting(area, highlighter, pause, dispatch, styles::restyle)
        changeMarkers = ChangeMarkerFollower(area, gutter, undoManager, savedTextProperty) { conflictShown }
    }

    override val node: Node get() = root

    override val text: String get() = area.text

    override val canReplaceText: Boolean get() = !readOnly && !additions.locks.held

    /** The extensions' toolbar items, read-only, in the order added; a view with its own toolbar shows them on top. */
    val toolbarItems: ObservableList<Node> get() = additions.toolbar.items

    /** The Soft Wrap toggle; a view with its own toolbar shows it first, an embedding view places it. */
    val softWrapButton: Node get() = softWrap.button

    override val activity: ObservableValue<ViewActivity> get() = additions.activities.activity

    override val readOnlyMarked: ObservableBooleanValue get() = additions.locks.readOnlyMarked

    override val dirty: ObservableBooleanValue =
        Bindings.not(undoManager.atMarkedPositionProperty()).or(keptMine).or(encoding.converting)

    /** Whether the banner about a change on disk that meets unsaved changes is shown. */
    val conflictShown: Boolean get() = conflictBanner in banners.children

    /** Whether the banner about the file being deleted is shown. */
    val deletedShown: Boolean get() = deletedBanner in banners.children

    /** Whether the file is not valid UTF-8 and therefore shown read-only. */
    val readOnly: Boolean get() = invalidBanner in banners.children

    /** Whether the banner saying that the file is generated (e.g. `uv.lock`) is shown. */
    val generatedShown: Boolean get() = generatedBanner.shown

    /**
     * Writes the text to the file in its encoding; does nothing while the view is [readOnly] or locked read-only by an
     * extension ([ExtensibleCodeEditor.readOnlyLock]). Fails, leaving the file
     * as it is, if the encoding cannot store a character of the text.
     */
    override fun save() {
        if (readOnly || additions.locks.readOnlyHeld) return
        val text = area.text
        val bytes = encoding.encode(lineEnding.restore(text))
        content.write(bytes)
        savedText = text
        keptMine.set(false)
        encoding.saved()
        undoManager.mark()
        // Typing on would otherwise extend the change before the mark.
        undoManager.preventMerge()
        hideConflict()
        banners.children -= deletedBanner
        extensible.saved()
    }

    /**
     * The diff's markers apply to the saved text; unsaved edits move them along ([ChangeMarkerFollower]). While the file
     * changed on disk and the view keeps its unsaved changes, the diff (of the file's new text) waits until the banner
     * is answered, which makes that text the saved one.
     */
    override fun showChanges(diff: TextDiff?) = changeMarkers.show(diff)

    override fun addGutterColumn(
        width: Double,
        column: (paragraph: Int) -> Node?,
    ) = gutter.addColumn(width, column)

    override fun refreshGutter() = gutter.refresh()

    override fun reload() {
        val read =
            try {
                decodeContent(content.read())
            } catch (_: NoSuchFileException) {
                hideConflict()
                if (!deletedShown) banners.children += deletedBanner
                return
            }
        banners.children -= deletedBanner
        when (DiskChange.decide(read.raw, savedText, area.text, dirty.get())) {
            DiskChange.IGNORE -> {
                adoptLineEnding(read.raw)
                // Kept mine: the next save writes the view's text in the view's encoding over the file.
                if (!keptMine.get()) encoding.unchanged(read.encoding)
                showEncoding()
                hideConflict()
            }

            DiskChange.RELOAD -> {
                load(read)
            }

            DiskChange.CONFLICT -> {
                conflict = read
                if (!conflictShown) banners.children += conflictBanner
            }
        }
    }

    /** Shows [content] as the saved text, keeping caret, scroll position and undo history. */
    private fun load(read: DecodedContent) {
        val text = LineEnding.normalize(read.raw)
        hideConflict()
        adoptLineEnding(read.raw)
        showReadOnly(!read.valid)
        encoding.read(read.encoding)
        showEncoding()
        // The reload is an undo step of its own.
        replaceMinimally(text)
        savedText = text
        keptMine.set(false)
        undoManager.mark()
        undoManager.preventMerge()
    }

    /**
     * Replaces the text with [text] (`\n` line breaks; others are turned into them) as one undoable change, e.g. from
     * a form beside the editor (ADR 0057), keeping caret and scroll position where possible. Unlike a reload, the
     * saved text stays, so the view becomes [dirty] (or clean, if [text] is the saved one). Does nothing if [text]
     * equals the current text. Returns `false` and changes nothing while the view is [readOnly], whose text cannot be
     * saved, or locked by an extension.
     */
    override fun replaceText(text: String): Boolean {
        if (!canReplaceText) return false
        if (replaceMinimally(LineEnding.normalize(text))) {
            // Typing on would otherwise extend this change.
            undoManager.preventMerge()
        }
        return true
    }

    /**
     * Turns the area's text into [text] by replacing only what differs, as an undo step of its own; caret and scroll
     * position stay where possible. Returns whether the text changed.
     */
    private fun replaceMinimally(text: String): Boolean {
        val replacement = TextReplacement.between(area.text, text)
        if (replacement.isEmpty) return false
        val caret = replacement.map(area.caretPosition)
        val scrollX = pane.estimatedScrollX
        val scrollY = pane.estimatedScrollY
        undoManager.preventMerge()
        area.replaceText(replacement.start, replacement.end, replacement.replacement)
        styles.highlight(text)
        area.moveTo(caret)
        // Moving the caret makes the area follow it during the next layout; lay out now, then scroll back.
        pane.layout()
        pane.scrollXToPixel(scrollX)
        pane.scrollYToPixel(scrollY)
        return true
    }

    /** Keeps the unsaved text over the file's new one, which only counts as saved, so the next save overwrites it. */
    private fun keepMine() {
        conflict?.let { savedText = LineEnding.normalize(it.raw) }
        keptMine.set(true)
        hideConflict()
    }

    private fun hideConflict() {
        conflict = null
        banners.children -= conflictBanner
    }

    private fun showReadOnly(readOnly: Boolean) {
        showBanner(invalidBanner, readOnly)
        showEditable()
    }

    /** The text is editable unless shown [readOnly] or locked by an extension. */
    private fun showEditable() {
        area.isEditable = !readOnly && !additions.locks.held
    }

    private fun showEncoding() {
        showBanner(encodingBanner, encoding.convertible)
    }

    /** Shows [banner] below the others if [shown], else hides it. */
    private fun showBanner(
        banner: HBox,
        shown: Boolean,
    ) {
        if (!shown) {
            banners.children -= banner
        } else if (banner !in banners.children) {
            banners.children += banner
        }
    }

    /** Takes the line ending of [raw], the file's content, unless it has no line break to tell. */
    private fun adoptLineEnding(raw: String) {
        if (raw.any { it == '\n' || it == '\r' }) lineEnding = LineEnding.of(raw)
    }

    /**
     * Reads and saves the file at [to] from now on; text, unsaved changes, caret and undo history stay. The indentation
     * is taken again for the new path, whose `.editorconfig` sections may differ.
     */
    override fun moved(to: Path): Boolean {
        // Content from elsewhere stays: its owner follows its own file.
        if ((content as? DiskContent)?.file == file) content = DiskContent(to)
        file = to
        fileName.set(to.fileName.toString())
        indentStyle = IndentStyle.forFile(to, savedText, defaultIndent)
        generatedBanner.show(file)
        extensible.moved(to)
        return true
    }

    override fun dispose() {
        disposed = true
        jumpScroll.cancel()
        pendingFocus.cancel()
        highlighting.dispose()
        findBar.dispose()
        extensions.dispose()
        additions.dispose()
        contextMenu.dispose()
        softWrap.dispose()
    }

    override fun focusText() = area.requestFocus()

    override fun showLine(line: Int) = goToLine.goTo(line)

    override fun showPosition(
        line: Int,
        column: Int,
    ) = goToLine.goTo(line, column)

    /** Focuses the text, keeping caret and scroll position; the text of a view in a split pane once it is shown. */
    override fun focus() = pendingFocus.request(area) { if (!disposed) area.requestFocus() }

    private fun show(text: String) {
        area.replaceText(text)
        styles.highlight(text)
    }

    /** Styles the [maskedRanges] of the text anew, e.g. when what they reveal changed; restyles only what differs. */
    fun restyleMasks() = styles.restyleMasks()
}

/** Texts above this length (about 1 MiB) are highlighted after a longer pause in typing. */
private const val LARGE_TEXT = 1 shl 20

/** How long typing pauses before the text is highlighted again (four times as long in large texts). */
private val HIGHLIGHT_PAUSE = Duration.ofMillis(150)
