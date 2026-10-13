package dev.sluide.terminal

import com.jediterm.core.compatibility.Point
import com.jediterm.core.util.TermSize
import com.jediterm.terminal.CursorShape
import com.jediterm.terminal.RequestOrigin
import com.jediterm.terminal.StyledTextConsumer
import com.jediterm.terminal.TerminalDisplay
import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.emulator.mouse.MouseFormat
import com.jediterm.terminal.emulator.mouse.MouseMode
import com.jediterm.terminal.model.CharBuffer
import com.jediterm.terminal.model.SelectionUtil
import com.jediterm.terminal.model.TerminalSelection
import com.jediterm.terminal.util.CharUtils
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
import javafx.scene.text.Font
import javafx.scene.text.FontWeight
import javafx.scene.text.Text
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.floor
import kotlin.math.round
import com.jediterm.core.Color as TermColor

private val COPY = KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN)
private val PASTE = KeyCodeCombination(KeyCode.V, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN)
private const val SCROLL_LINES = 3

/**
 * Draws a terminal's screen and scrollback on a canvas and sends keyboard input and pastes to its shell. The session
 * is created by [startSession] with this view as its [TerminalDisplay]; the emulator calls the display methods from
 * its own thread, so they only record state and schedule a repaint on the FX thread.
 */
internal class TerminalView(
    startSession: (TerminalDisplay, TermSize) -> TerminalSession,
    private val colors: TerminalColors = TerminalColors(),
    font: Font = Font.font("Monospaced", 13.0),
) : Region(),
    TerminalDisplay {
    private val canvas = Canvas()
    private val regularFont = font
    private val boldFont = Font.font(font.family, FontWeight.BOLD, font.size)
    private val cell = CellSize.of(font)
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

    @Volatile private var windowTitle = ""

    private val repaintPending = AtomicBoolean()
    private val _exited = ReadOnlyBooleanWrapper()
    private var size = TermSize(80, 24)

    /** Lines scrolled back into the history: 0 shows the screen, -n shows n history lines above it. */
    internal var scrollOrigin = 0
        private set

    val session: TerminalSession = startSession(this, size)

    /** Becomes `true` when the shell has ended. */
    val exited: ReadOnlyBooleanProperty = _exited.readOnlyProperty

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
        addEventHandler(MouseEvent.MOUSE_PRESSED, ::onMousePressed)
        addEventHandler(MouseEvent.MOUSE_DRAGGED, ::onMouseDragged)
        val contextMenu =
            ContextMenu(
                MenuItem("Copy").apply { setOnAction { copySelection() } },
                MenuItem("Paste").apply { setOnAction { paste() } },
            )
        setOnContextMenuRequested { event ->
            contextMenu.show(this, event.screenX, event.screenY)
            event.consume()
        }
        // Focus the terminal when its tab is shown, so typing goes to the shell right away.
        sceneProperty().subscribe { scene -> if (scene != null) Platform.runLater(::requestFocus) }
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
        val normalized = text.replace("\r\n", "\r").replace('\n', '\r')
        val payload = if (bracketedPaste) "\u001b[200~$normalized\u001b[201~" else normalized
        scrollToBottom()
        session.send(payload.toByteArray(Charsets.UTF_8))
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

    private fun onScroll(event: ScrollEvent) {
        if (event.deltaY == 0.0 || session.buffer.isUsingAlternateBuffer) return
        val lines = if (event.deltaY > 0) -SCROLL_LINES else SCROLL_LINES
        scrollOrigin = (scrollOrigin + lines).coerceIn(-session.buffer.historyLinesCount, 0)
        paint()
        event.consume()
    }

    private fun onMousePressed(event: MouseEvent) {
        requestFocus()
        if (event.button != MouseButton.PRIMARY) return
        selection = null
        selectionAnchor = cellAt(event)
        paint()
    }

    private var selectionAnchor: Point? = null

    private fun onMouseDragged(event: MouseEvent) {
        val anchor = selectionAnchor ?: return
        if (!event.isPrimaryButtonDown) return
        selection = TerminalSelection(anchor, cellAt(event))
        paint()
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
        if (scrollOrigin != 0) {
            scrollOrigin = 0
            paint()
        }
    }

    private fun requestRepaint() {
        if (repaintPending.compareAndSet(false, true)) {
            Platform.runLater {
                repaintPending.set(false)
                paint()
            }
        }
    }

    private fun paint() {
        val gc = canvas.graphicsContext2D
        gc.fill = colors.defaultBackground
        gc.fillRect(0.0, 0.0, canvas.width, canvas.height)
        val buffer = session.buffer
        val range = selection?.let { normalized(it.start, it.end) }
        buffer.lock()
        try {
            scrollOrigin = scrollOrigin.coerceIn(-buffer.historyLinesCount, 0)
            buffer.processHistoryAndScreenLines(
                scrollOrigin,
                size.rows,
                object : StyledTextConsumer {
                    override fun consume(
                        x: Int,
                        y: Int,
                        style: TextStyle,
                        characters: CharBuffer,
                        startRow: Int,
                    ) = drawRun(x, y - startRow, style, characters, range)

                    override fun consumeNul(
                        x: Int,
                        y: Int,
                        nulIndex: Int,
                        style: TextStyle,
                        characters: CharBuffer,
                        startRow: Int,
                    ) = drawRun(x, y - startRow, style, characters, range)

                    override fun consumeQueue(
                        x: Int,
                        y: Int,
                        nulIndex: Int,
                        startRow: Int,
                    ) {}
                },
            )
        } finally {
            buffer.unlock()
        }
        drawCursor()
    }

    private fun drawRun(
        x: Int,
        row: Int,
        style: TextStyle,
        characters: CharBuffer,
        selected: Pair<Point, Point>?,
    ) {
        val gc = canvas.graphicsContext2D
        val styleColors = colors.of(style)
        val top = row * cell.height
        gc.font = if (style.hasOption(TextStyle.Option.BOLD)) boldFont else regularFont
        for (i in 0 until characters.length) {
            val column = x + i
            val left = column * cell.width
            val isSelected = selected != null && Point(column, row + scrollOrigin).isIn(selected)
            val foreground = if (isSelected) styleColors.background else styleColors.foreground
            gc.fill = if (isSelected) styleColors.foreground else styleColors.background
            gc.fillRect(left, top, cell.width + 0.5, cell.height + 0.5)
            val char = characters[i]
            if (char == CharUtils.DWC || char == CharUtils.NUL_CHAR || char == ' ') continue
            gc.fill = foreground
            gc.fillText(char.toString(), left, top + cell.baseline)
            if (style.hasOption(TextStyle.Option.UNDERLINED)) {
                gc.fillRect(left, top + cell.baseline + 1, cell.width, 1.0)
            }
        }
    }

    private fun drawCursor() {
        if (!cursorVisible || _exited.get()) return
        val row = cursorY - 1 - scrollOrigin
        if (row !in 0 until size.rows) return
        val gc = canvas.graphicsContext2D
        gc.fill = colors.defaultForeground.deriveColor(0.0, 1.0, 1.0, if (isFocused) 0.7 else 0.0)
        gc.stroke = colors.defaultForeground
        val left = cursorX * cell.width
        val top = row * cell.height
        if (isFocused) {
            gc.fillRect(
                left,
                top,
                cell.width,
                cell.height,
            )
        } else {
            gc.strokeRect(left, top, cell.width, cell.height)
        }
    }

    private fun normalized(
        a: Point,
        b: Point,
    ): Pair<Point, Point> = if (a.y < b.y || (a.y == b.y && a.x <= b.x)) a to b else b to a

    /** Whether this cell lies in the selection from the first point up to, but excluding, the second, like the copied text. */
    private fun Point.isIn(range: Pair<Point, Point>): Boolean {
        val (start, end) = range
        val afterStart = y > start.y || (y == start.y && x >= start.x)
        val beforeEnd = y < end.y || (y == end.y && x < end.x)
        return afterStart && beforeEnd
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
        Platform.runLater { scrollOrigin = 0 }
        requestRepaint()
    }

    override fun getWindowTitle(): String = windowTitle

    override fun setWindowTitle(windowTitle: String) {
        this.windowTitle = windowTitle
    }

    override fun getSelection(): TerminalSelection? = selection

    override fun terminalMouseModeSet(mouseMode: MouseMode) {}

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

/** The size of one character cell of a monospaced font, and the grid of cells that fits into an area. */
internal data class CellSize(
    val width: Double,
    val height: Double,
    val baseline: Double,
) {
    fun gridSize(
        areaWidth: Double,
        areaHeight: Double,
    ) = TermSize(
        floor(areaWidth / width).toInt().coerceAtLeast(MIN_COLUMNS),
        floor(areaHeight / height).toInt().coerceAtLeast(MIN_ROWS),
    )

    companion object {
        private const val MIN_COLUMNS = 5
        private const val MIN_ROWS = 2

        fun of(font: Font): CellSize {
            val text = Text("M").apply { this.font = font }
            return CellSize(text.layoutBounds.width, text.layoutBounds.height, text.baselineOffset)
        }
    }
}
