package dev.sluide.terminal.view

import com.jediterm.core.compatibility.Point
import com.jediterm.core.util.TermSize
import com.jediterm.terminal.CursorShape
import com.jediterm.terminal.TerminalDisplay
import com.jediterm.terminal.emulator.mouse.MouseButtonCodes
import com.jediterm.terminal.emulator.mouse.MouseFormat
import com.jediterm.terminal.emulator.mouse.MouseMode
import com.jediterm.terminal.model.SelectionUtil
import com.jediterm.terminal.model.TerminalSelection
import dev.sluide.settings.FontSpec
import dev.sluide.terminal.TerminalSession
import javafx.application.Platform
import javafx.beans.property.ReadOnlyBooleanProperty
import javafx.beans.property.ReadOnlyBooleanWrapper
import javafx.scene.canvas.Canvas
import javafx.scene.control.ContextMenu
import javafx.scene.control.MenuItem
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.input.ScrollEvent
import javafx.scene.layout.Region
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.floor
import kotlin.math.round
import com.jediterm.core.Color as TermColor
import com.jediterm.core.input.MouseEvent as TermMouseEvent

private val COPY = KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN)
private val PASTE = KeyCodeCombination(KeyCode.V, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN)

/**
 * Draws a terminal's screen and scrollback on a canvas and sends keyboard input and pastes to its shell. The session
 * is created by [startSession] with this view as its [TerminalDisplay]; the emulator calls the display methods from
 * its own thread, so they only record state and schedule a repaint on the FX thread.
 */
internal class TerminalView(
    startSession: (TerminalDisplay, TermSize) -> TerminalSession,
    private val colors: TerminalColors = TerminalColors(),
    font: FontSpec = FontSpec("Monospaced", 13.0),
    scrollSpeeds: ScrollSpeeds = ScrollSpeeds(),
    /** Whether it takes the focus when it enters a scene (a tab being shown); otherwise only when clicked. */
    focusWhenShown: Boolean = true,
) : Region(),
    TerminalDisplay {
    private val canvas = Canvas()
    private var fonts = Fonts(font)

    /** The font of normal text; bold and italic text use the bolder or italic variant of it. */
    var font: FontSpec
        get() = fonts.spec
        set(value) {
            if (value == fonts.spec) return
            fonts = Fonts(value)
            // Lays out with the new cell size: resizes the grid, tells the shell and repaints.
            requestLayout()
        }

    /** The cell size of the current font. */
    internal val cell: CellSize get() = fonts.cell
    private val keys =
        TerminalKeys(encode = {
            code,
            modifiers,
            ->
            session.terminal.getCodeForKey(code, modifiers)
        }, altSendsEscape = !isMac)

    @Volatile private var cursorX = 0

    @Volatile private var cursorY = 1

    @Volatile private var cursorVisible = true

    @Volatile private var bracketedPaste = false

    @Volatile private var selection: TerminalSelection? = null

    @Volatile private var mouseMode = MouseMode.MOUSE_REPORTING_NONE

    @Volatile private var windowTitle = ""

    private val repaintPending = AtomicBoolean()
    private val _exited = ReadOnlyBooleanWrapper()
    private var size = TermSize(80, 24)

    /** Lines scrolled back into the history: 0 shows the screen, -n shows n history lines above it. */
    internal var scrollOrigin = 0
        private set

    private val scrolling = ScrollRouting(scrollSpeeds)

    /** How fast trackpads and mouse wheels scroll; set on the FX thread. */
    var scrollSpeeds: ScrollSpeeds
        get() = scrolling.speeds
        set(value) {
            scrolling.speeds = value
        }

    val session: TerminalSession = startSession(this, size)

    private val painter =
        TerminalPainter(
            canvas,
            colors,
            session.buffer,
            PaintState(
                fonts = { fonts },
                size = { size },
                selection = { selection },
                cursorX = { cursorX },
                cursorY = { cursorY },
                cursorVisible = { cursorVisible },
                exited = { _exited.get() },
                focused = { isFocused },
                scrollOrigin = { scrollOrigin },
                setScrollOrigin = { scrollOrigin = it },
            ),
        )

    /** Becomes `true` when the shell has ended. */
    val exited: ReadOnlyBooleanProperty = _exited.readOnlyProperty

    /** Shown on a right click: Copy and Paste. */
    internal val contextMenu =
        ContextMenu(
            MenuItem("Copy").apply { setOnAction { copySelection() } },
            MenuItem("Paste").apply { setOnAction { paste() } },
        )

    init {
        styleClass += "terminal-view"
        isFocusTraversable = true
        children += canvas
        session.buffer.addModelListener { requestRepaint() }
        session.process.onExit().thenRun {
            Platform.runLater {
                _exited.set(true)
                requestRepaint()
            }
        }
        addEventHandler(KeyEvent.KEY_PRESSED, ::onKeyPressed)
        addEventHandler(KeyEvent.KEY_TYPED, ::onKeyTyped)
        addEventHandler(ScrollEvent.SCROLL, ::onScroll)
        addEventHandler(ScrollEvent.SCROLL_STARTED) { scrolling.gesture = true }
        addEventHandler(ScrollEvent.SCROLL_FINISHED) { scrolling.gesture = false }
        addEventHandler(MouseEvent.MOUSE_PRESSED, ::onMousePressed)
        addEventHandler(MouseEvent.MOUSE_DRAGGED, ::onMouseDragged)
        addEventHandler(MouseEvent.MOUSE_RELEASED, ::onMouseReleased)
        addEventHandler(MouseEvent.MOUSE_MOVED, ::onMouseMoved)
        setOnContextMenuRequested { event ->
            contextMenu.show(this, event.screenX, event.screenY)
            event.consume()
        }
        // Focus the terminal when its tab is shown, so typing goes to the shell right away.
        if (focusWhenShown) sceneProperty().subscribe { scene -> if (scene != null) Platform.runLater(::requestFocus) }
        // The cursor is filled while focused and outlined otherwise.
        focusedProperty().subscribe(::paint)
    }

    /** The visible text of the screen, one line per row with trailing blanks removed, for tests. */
    internal fun screenText(): String =
        session.buffer.let { buffer ->
            buffer.lock()
            try {
                buffer
                    .getScreenLines()
                    .lines()
                    .joinToString("\n") { it.trimEnd() }
                    .trimEnd()
            } finally {
                buffer.unlock()
            }
        }

    /** Sends [text] to the shell as if pasted. */
    fun paste(text: String) {
        scrollToBottom()
        session.send(pastePayload(text, bracketedPaste).toByteArray(Charsets.UTF_8))
    }

    fun dispose() = session.close()

    override fun layoutChildren() {
        canvas.width = width
        canvas.height = height
        val newSize = cell.gridSize(width, height)
        if (newSize != size) {
            size = newSize
            session.resize(newSize)
        }
        paint()
    }

    override fun computePrefWidth(height: Double) = cell.width * 80

    override fun computePrefHeight(width: Double) = cell.height * 24

    override fun computeMinWidth(height: Double) = 0.0

    override fun computeMinHeight(width: Double) = 0.0

    private fun onKeyPressed(event: KeyEvent) {
        when {
            COPY.match(event) -> {
                copySelection()
            }

            PASTE.match(event) -> {
                paste()
            }

            else -> {
                val bytes = keys.pressed(event.code, event.modifiers()) ?: return
                scrollToBottom()
                session.send(bytes)
            }
        }
        event.consume()
    }

    private fun onKeyTyped(event: KeyEvent) {
        val text = keys.typed(event.character, event.modifiers()) ?: return
        scrollToBottom()
        session.send(text.toByteArray(Charsets.UTF_8))
        event.consume()
    }

    private fun KeyEvent.modifiers() =
        Modifiers(shift = isShiftDown, control = isControlDown, alt = isAltDown, meta = isMetaDown)

    private fun MouseEvent.modifiers() =
        Modifiers(shift = isShiftDown, control = isControlDown, alt = isAltDown, meta = isMetaDown)

    private fun ScrollEvent.modifiers() =
        Modifiers(shift = isShiftDown, control = isControlDown, alt = isAltDown, meta = isMetaDown)

    private fun onScroll(event: ScrollEvent) {
        val action =
            scrolling.route(
                event.deltaY,
                event.isInertia,
                event.touchCount,
                event.isShiftDown,
                mouseMode,
                session.buffer.isUsingAlternateBuffer,
            )
        when (action) {
            ScrollAction.None -> {
                return
            }

            is ScrollAction.Report -> {
                val wheel = TerminalMouse.wheel(action.up, event.modifiers(), SCROLL_LINES)
                repeat(action.notches) { sendMouse(event.x, event.y, wheel) }
            }

            is ScrollAction.ArrowKeys -> {
                // Full-screen programs scroll themselves: without mouse reports, JediTerm sends an arrow key per line.
                if (action.lines != 0) {
                    sendMouse(event.x, event.y, TerminalMouse.wheel(action.up, event.modifiers(), action.lines))
                }
            }

            is ScrollAction.Scrollback -> {
                if (action.lines != 0) {
                    scrollOrigin = (scrollOrigin + action.lines).coerceIn(-session.buffer.historyLinesCount, 0)
                    paint()
                }
            }
        }
        event.consume()
    }

    private fun onMousePressed(event: MouseEvent) {
        requestFocus()
        if (reportMouse(event, TermMouseEvent.Type.PRESSED)) {
            selectionAnchor = null
            return
        }
        if (event.button != MouseButton.PRIMARY) return
        selection = null
        selectionAnchor = cellAt(event)
        paint()
    }

    private var selectionAnchor: Point? = null

    private fun onMouseDragged(event: MouseEvent) {
        if (reportMouse(event, TermMouseEvent.Type.DRAGGED)) return
        val anchor = selectionAnchor ?: return
        if (!event.isPrimaryButtonDown) return
        selection = TerminalSelection(anchor, cellAt(event))
        paint()
    }

    private fun onMouseReleased(event: MouseEvent) {
        reportMouse(event, TermMouseEvent.Type.RELEASED)
    }

    private fun onMouseMoved(event: MouseEvent) {
        reportMouse(event, TermMouseEvent.Type.MOVED)
    }

    /**
     * Reports a click, drag or move to the running program if it asked for mouse reports, unless Shift is held, which
     * selects text as usual. Returns whether the event was meant for the program.
     */
    private fun reportMouse(
        event: MouseEvent,
        type: TermMouseEvent.Type,
    ): Boolean {
        if (mouseMode == MouseMode.MOUSE_REPORTING_NONE || event.isShiftDown) return false
        val button =
            when (type) {
                TermMouseEvent.Type.MOVED -> {
                    MouseButtonCodes.NONE
                }

                // Drag events do not reliably name the button on all platforms, the held buttons do.
                TermMouseEvent.Type.DRAGGED -> {
                    TerminalMouse.heldButton(
                        event.isPrimaryButtonDown,
                        event.isMiddleButtonDown,
                    )
                }

                else -> {
                    TerminalMouse.button(event.button)
                }
            } ?: return false
        sendMouse(event.x, event.y, TerminalMouse.event(type, button, event.modifiers()))
        event.consume()
        return true
    }

    /** Lets the emulator encode [event] at the screen cell under ([x], [y]) for the program; `true` if it sent bytes. */
    private fun sendMouse(
        x: Double,
        y: Double,
        event: TermMouseEvent,
    ): Boolean {
        val column = floor(x / cell.width).toInt().coerceIn(0, size.columns - 1)
        val row = floor(y / cell.height).toInt().coerceIn(0, size.rows - 1)
        return session.sendMouse(column, row, event)
    }

    /**
     * The position under the mouse for selecting: the column boundary nearest to it (so dragging across half a
     * character selects it) and the buffer row, where negative rows are history lines.
     */
    private fun cellAt(event: MouseEvent) =
        Point(
            round(event.x / cell.width).toInt().coerceIn(0, size.columns),
            floor(event.y / cell.height).toInt().coerceIn(0, size.rows - 1) + scrollOrigin,
        )

    private fun copySelection() {
        val selected = selection ?: return
        val text =
            session.buffer.let { buffer ->
                buffer.lock()
                try {
                    SelectionUtil.getSelectionText(selected, buffer)
                } finally {
                    buffer.unlock()
                }
            }
        if (text.isNotEmpty()) Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString(text) })
    }

    private fun paste() {
        Clipboard
            .getSystemClipboard()
            .string
            ?.takeIf { it.isNotEmpty() }
            ?.let(::paste)
    }

    private fun scrollToBottom() {
        resetScrollFractions()
        if (scrollOrigin != 0) {
            scrollOrigin = 0
            paint()
        }
    }

    private fun resetScrollFractions() {
        scrolling.reset()
    }

    private fun paint() = painter.paint()

    private fun requestRepaint() {
        if (repaintPending.compareAndSet(false, true)) {
            Platform.runLater {
                repaintPending.set(false)
                paint()
            }
        }
    }

    // TerminalDisplay, called by the emulator thread.

    override fun setCursor(
        x: Int,
        y: Int,
    ) {
        cursorX = x
        cursorY = y
        requestRepaint()
    }

    override fun setCursorShape(cursorShape: CursorShape?) {}

    override fun beep() {}

    override fun scrollArea(
        scrollRegionTop: Int,
        scrollRegionSize: Int,
        dy: Int,
    ) = requestRepaint()

    override fun setCursorVisible(isCursorVisible: Boolean) {
        cursorVisible = isCursorVisible
        requestRepaint()
    }

    override fun useAlternateScreenBuffer(useAlternateScreenBuffer: Boolean) {
        Platform.runLater {
            scrollOrigin = 0
            resetScrollFractions()
        }
        requestRepaint()
    }

    override fun getWindowTitle(): String = windowTitle

    override fun setWindowTitle(windowTitle: String) {
        this.windowTitle = windowTitle
    }

    override fun getSelection(): TerminalSelection? = selection

    override fun terminalMouseModeSet(mouseMode: MouseMode) {
        this.mouseMode = mouseMode
    }

    override fun setMouseFormat(mouseFormat: MouseFormat) {}

    override fun ambiguousCharsAreDoubleWidth() = false

    override fun setBracketedPasteMode(bracketedPasteModeEnabled: Boolean) {
        bracketedPaste = bracketedPasteModeEnabled
    }

    override fun getWindowForeground(): TermColor = colors.defaultForeground.toTermColor()

    override fun getWindowBackground(): TermColor = colors.defaultBackground.toTermColor()

    private fun javafx.scene.paint.Color.toTermColor() =
        TermColor((red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())

    private companion object {
        val isMac = System.getProperty("os.name").lowercase().contains("mac")
    }
}

/** What a paste of [text] sends to the shell: line breaks as Enter (`\r`), wrapped in bracket markers if [bracketed]. */
internal fun pastePayload(
    text: String,
    bracketed: Boolean,
): String {
    val normalized = text.replace("\r\n", "\r").replace('\n', '\r')
    return if (bracketed) "\u001b[200~$normalized\u001b[201~" else normalized
}
