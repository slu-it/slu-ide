package dev.sluide.ide.codeintelligence

import dev.sluide.fileviewers.CodeEditorExtension
import dev.sluide.fileviewers.ExtensibleCodeEditor
import dev.sluide.java.JavaWorkspaces
import dev.sluide.java.isJdtlsLog
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.layout.HBox
import javafx.scene.layout.Region
import javafx.util.Subscription
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * Opens jdtls's log read-only with the caret at its end (ADR 0179, ADR 0108): the code editor of
 * `<root>/<workspace>/data/.metadata/.log` is locked ([ExtensibleCodeEditor.readOnlyLock], always, with no way to edit
 * anyway) and shows a banner saying so. When the editor first shows the file's text, the caret goes to its end, so the
 * view shows the last lines; later reloads keep the editor's behaviour. A second Show Log on an open tab only selects
 * the tab. The file is classified again when it moves. FX thread.
 */
@Component
class JdtlsLogExtension internal constructor(
    private val root: Path,
    private val banner: () -> Node,
) : CodeEditorExtension {
    @Autowired
    constructor() : this(JavaWorkspaces().root, ::readOnlyBanner)

    override fun extend(editor: ExtensibleCodeEditor): AutoCloseable = Binding(editor, root, banner)

    /** One editor's lock, banner and wait for its text. */
    private class Binding(
        private val editor: ExtensibleCodeEditor,
        private val root: Path,
        private val banner: () -> Node,
    ) : AutoCloseable {
        private var lock: AutoCloseable? = null
        private var bannerHandle: AutoCloseable? = null
        private var textWait: Subscription? = null
        private var caretMoved = false

        // Subscribing already calls update once, which takes the first state.
        private val subscription = editor.file.subscribe { _ -> update() }

        private fun update() {
            if (isJdtlsLog(editor.file.value, root)) {
                if (lock == null) lock = editor.readOnlyLock()
                if (bannerHandle == null) bannerHandle = editor.addBanner(banner())
                moveCaretToEnd()
            } else {
                release()
            }
        }

        /**
         * Once per editor. A code view reads its text when it is created, before a host attaches the extensions, so
         * the text is there at [extend] and the caret moves at once; waiting for [ExtensibleCodeEditor.onTextChanged]
         * only serves an empty log, whose first text arrives later.
         */
        private fun moveCaretToEnd() {
            if (caretMoved || textWait != null) return
            if (!isEmpty()) {
                moveToEnd()
                return
            }
            textWait =
                editor.onTextChanged {
                    if (!isEmpty()) {
                        stopWaiting()
                        moveToEnd()
                    }
                }
        }

        // Not `editor.text.isEmpty()`: that builds the whole text of a log of megabytes on every change.
        private fun isEmpty() = editor.lineCount == 1 && editor.lineText(0).isEmpty()

        private fun moveToEnd() {
            caretMoved = true
            editor.moveCaret(editor.text.length)
        }

        private fun stopWaiting() {
            textWait?.unsubscribe()
            textWait = null
        }

        private fun release() {
            stopWaiting()
            lock?.close()
            lock = null
            bannerHandle?.close()
            bannerHandle = null
        }

        override fun close() {
            subscription.unsubscribe()
            release()
        }
    }
}

internal fun readOnlyBanner(): Node =
    HBox(
        Label("jdtls's log: read-only. jdtls writes to it while it runs.").apply {
            isWrapText = true
            minWidth = 0.0
            minHeight = Region.USE_PREF_SIZE
        },
    ).apply {
        styleClass += listOf("editor-banner", "generated-banner")
        alignment = Pos.CENTER_LEFT
    }
