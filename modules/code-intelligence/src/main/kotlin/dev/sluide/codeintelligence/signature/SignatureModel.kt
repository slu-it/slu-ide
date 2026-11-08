package dev.sluide.codeintelligence.signature

import dev.sluide.codeintelligence.binding.DocumentBinding
import dev.sluide.codeintelligence.binding.TypedKeys
import dev.sluide.fileviewers.TextChange
import dev.sluide.languageservers.RequestSlot
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.SignatureHelpContext
import org.eclipse.lsp4j.SignatureHelpParams
import org.eclipse.lsp4j.SignatureHelpTriggerKind
import kotlin.time.Duration

private val log = System.getLogger(SignatureModel::class.java.name)

/** The LSP method of signature help. */
internal const val SIGNATURE_HELP = "textDocument/signatureHelp"

/** What a [SignatureModel] shows on: the signature popup. */
internal interface SignatureView {
    /** Shows [shown] above the line of [anchor], or replaces what shows; `false` if it cannot (not on screen, no focus). */
    fun show(
        anchor: Int,
        shown: SignatureShown,
    ): Boolean

    /** Hides the popup; the model hears it through [SignatureModel.viewHidden]. */
    fun hide()
}

/**
 * When signature help asks the server and what it shows (ADR 0147), without the popup. FX thread.
 *
 * Asks on Shortcut+P ([invoke]) and after a typed server trigger character (`(`, `,`), or a retrigger character while
 * it shows, at once; while it shows, every other change and caret move asks again after [debounce]. It shows where it
 * was first asked, above that line, and closes when an answer has no signature (the caret left the call), on Escape
 * and focus loss (the popup hides: [viewHidden]).
 *
 * @param schedule runs the action after the delay unless the handle is closed first.
 */
internal class SignatureModel(
    private val binding: DocumentBinding,
    private val view: SignatureView,
    private val debounce: Duration,
    private val schedule: (Duration, () -> Unit) -> AutoCloseable,
) {
    private val editor = binding.editor
    private val slot = RequestSlot()
    private var timer: AutoCloseable? = null

    /** What the user typed, fed by the editor's keys. */
    val keys = TypedKeys()

    /** Whether the popup shows. */
    var isOpen = false
        private set

    /** Where the popup is placed: the caret when it was first asked for, moved with the edits before it. */
    private var anchor: Int? = null

    /** The last answer shown, sent back with a retrigger. */
    private var last: SignatureHelp? = null

    /** The caret offset of the last request: a caret move to there needs no new one. */
    private var requestedAt: Int? = null

    /** The editor's text changed: a typed trigger character asks at once, other changes while open after the debounce. */
    fun textChanged(change: TextChange) {
        anchor = anchor?.let { shiftedAnchor(it, change) }
        val typedNow = keys.take(change)
        val options =
            binding.session.features.capabilities
                ?.signatureHelpProvider
        when {
            typedNow != null && typedNow in options?.triggerCharacters.orEmpty() && supported() -> {
                request(SignatureHelpTriggerKind.TriggerCharacter, typedNow)
            }

            typedNow != null && isOpen && typedNow in options?.retriggerCharacters.orEmpty() -> {
                request(SignatureHelpTriggerKind.TriggerCharacter, typedNow)
            }

            isOpen -> {
                requestLater()
            }
        }
    }

    /** The caret moved: while open, asks again after the debounce (the server says whether it is still in the call). */
    fun caretMoved() {
        if (isOpen && editor.caretOffset != requestedAt) requestLater()
    }

    /** Shortcut+P: asks at the caret now; `false` (key not taken) without a server that offers signature help. */
    fun invoke(): Boolean {
        if (!supported()) return false
        request(SignatureHelpTriggerKind.Invoked, null)
        return true
    }

    /** The popup hid by itself (Escape, focus loss, a click outside). */
    fun viewHidden() {
        if (isOpen) end()
    }

    /** Closes the popup and drops the questions on their way. */
    fun close() {
        val wasOpen = isOpen
        end()
        if (wasOpen) view.hide()
    }

    private fun end() {
        isOpen = false
        anchor = null
        last = null
        requestedAt = null
        timer?.close()
        timer = null
        slot.cancel()
    }

    private fun requestLater() {
        timer?.close()
        timer =
            schedule(debounce) {
                timer = null
                if (isOpen) request(SignatureHelpTriggerKind.ContentChange, null)
            }
    }

    private fun request(
        kind: SignatureHelpTriggerKind,
        character: String?,
    ) {
        timer?.close()
        timer = null
        val caret = editor.caretOffset
        requestedAt = caret
        val context =
            SignatureHelpContext(kind, isOpen).apply {
                triggerCharacter = character
                activeSignatureHelp = last.takeIf { isOpen }
            }
        val params = SignatureHelpParams(binding.documentId, binding.positionOf(caret), context)
        slot.send(
            binding.session,
            { server -> server.textDocumentService.signatureHelp(params) },
            { help -> answer(help, caret) },
            { error ->
                log.log(System.Logger.Level.INFO, "signature help request failed: $error")
                close()
            },
        )
    }

    private fun answer(
        help: SignatureHelp?,
        caret: Int,
    ) {
        if (binding.isClosed) return
        val shown = signatureShown(help)
        if (shown == null) {
            close()
            return
        }
        val at = anchor ?: caret
        if (!view.show(at, shown)) {
            close()
            return
        }
        isOpen = true
        anchor = at
        last = help
    }

    private fun supported() = binding.session.features.supports(SIGNATURE_HELP)
}

/**
 * Where the signature popup's [anchor] is after [change]: moved with an edit before it, at the start of an edit that
 * removed it, and kept for an edit at or after it (typing the arguments).
 */
internal fun shiftedAnchor(
    anchor: Int,
    change: TextChange,
): Int =
    when {
        change.start >= anchor -> anchor
        change.start + change.removed.length <= anchor -> anchor + change.inserted.length - change.removed.length
        else -> change.start
    }
