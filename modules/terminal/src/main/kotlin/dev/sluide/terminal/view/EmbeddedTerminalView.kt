package dev.sluide.terminal.view

import dev.sluide.terminal.EmbeddedTerminal
import javafx.beans.property.ReadOnlyBooleanProperty
import javafx.beans.property.ReadOnlyBooleanWrapper
import javafx.scene.Node

/** What Ctrl+C sends: the pseudo terminal turns it into SIGINT for the foreground job. */
private const val CTRL_C: Byte = 0x03

/**
 * A terminal [view] running one command for another module's view. [close] ends the command, releases the view and
 * then calls [onClosed]; the output stays visible after the command has ended.
 */
internal class EmbeddedTerminalView(
    private val view: TerminalView,
    private val onClosed: (EmbeddedTerminalView) -> Unit = {},
) : EmbeddedTerminal {
    private val _exited = ReadOnlyBooleanWrapper()
    private var closed = false
    private var closedBeforeExit = false

    init {
        // The view sets its flag on the FX thread after the process has ended, so the exit code is known by then.
        view.exited.subscribe { ended -> if (ended) _exited.set(true) }
    }

    override val node: Node get() = view

    override val exited: ReadOnlyBooleanProperty = _exited.readOnlyProperty

    override val exitCode: Int?
        get() = if (_exited.get() && !closedBeforeExit) view.session.exitCode else null

    override val isAlive: Boolean get() = view.session.isAlive

    override fun interrupt() {
        if (closed || _exited.get() || !isAlive) return
        view.session.send(byteArrayOf(CTRL_C))
    }

    override fun close() {
        if (closed) return
        closed = true
        if (!_exited.get()) {
            // The process may have ended already, with the view's notice still queued: then its exit code counts.
            closedBeforeExit = view.session.isAlive
            _exited.set(true)
        }
        view.dispose()
        onClosed(this)
    }
}
