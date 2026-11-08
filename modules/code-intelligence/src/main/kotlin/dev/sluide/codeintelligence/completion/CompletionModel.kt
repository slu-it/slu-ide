package dev.sluide.codeintelligence.completion

import dev.sluide.codeintelligence.binding.DocumentBinding
import dev.sluide.codeintelligence.binding.TypedKeys
import dev.sluide.fileviewers.TextChange
import dev.sluide.languageservers.RequestSlot
import org.eclipse.lsp4j.CompletionContext
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.CompletionTriggerKind
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.util.IdentityHashMap
import kotlin.time.Duration

private val log = System.getLogger(CompletionModel::class.java.name)

/** The LSP methods of the completion. */
internal const val COMPLETION = "textDocument/completion"
internal const val COMPLETION_RESOLVE = "completionItem/resolve"

/** What a [CompletionModel] shows on: the completion popup. */
internal interface CompletionView {
    /**
     * Shows [items] for the word starting at [wordStart], keeping the selected item if it is still there; with no items
     * the "Searching…" placeholder. `false` if it cannot show (not on screen, the editor not focused).
     */
    fun show(
        wordStart: Int,
        items: List<CompletionItem>,
    ): Boolean

    /** Hides the popup; the model hears it through [CompletionModel.viewHidden]. */
    fun hide()

    /** An item's documentation arrived (resolved): show the selected item's detail again. */
    fun refreshDetail()

    /** Whether the popup shows (it takes Escape itself then). */
    val isShowing: Boolean
}

/**
 * When the completion asks the server, what it shows and what taking an item does (ADR 0147), without the popup. FX
 * thread.
 *
 * Opens after a typed server trigger character (at once), after a typed word char at a word's end (after [debounce]),
 * and on Ctrl+Space ([invoke], showing "Searching…" until the answer). While open, each change filters and ranks the
 * items of the last answer at once ([rankCompletions]) and asks again (latest wins) if that answer was incomplete;
 * the list only changes when something matches, until a fresh answer has nothing, which closes it. Closes when the
 * caret leaves the word, on Escape and focus loss (the popup hides: [viewHidden]) and when an item is taken
 * ([accept]).
 *
 * @param schedule runs the action after the delay unless the handle is closed first.
 */
internal class CompletionModel(
    private val binding: DocumentBinding,
    private val view: CompletionView,
    private val debounce: Duration,
    private val schedule: (Duration, () -> Unit) -> AutoCloseable,
) {
    private val editor = binding.editor
    private val slot = RequestSlot()
    private val resolveSlot = RequestSlot()
    private var timer: AutoCloseable? = null

    /** What the user typed, fed by the editor's keys. */
    val keys = TypedKeys()

    /** Whether a completion is going on: waiting for its first answer, or shown. */
    var isOpen = false
        private set

    /** Where the completed word starts. */
    private var anchor = 0

    private var answered: List<CompletionItem> = emptyList()
    private var incomplete = false

    /** The changes since the answered items were asked for, and since the request on its way was sent. */
    private var answeredChanges = ChangeTracker(null)
    private var pendingChanges: ChangeTracker? = null

    private val resolved = IdentityHashMap<CompletionItem, CompletionItem>()

    /** The item whose resolve is on its way: a newer one cancels it. */
    private var resolving: CompletionItem? = null

    /** The items shown, best first; empty while closed or waiting. */
    var shown: List<CompletionItem> = emptyList()
        private set

    /** The editor's text changed (the caret already moved with it). */
    fun textChanged(change: TextChange) {
        pendingChanges?.changed(change.start)
        answeredChanges.changed(change.start)
        val typedNow = keys.take(change)
        if (isOpen && !caretInWord()) close()
        if (isOpen) {
            // Still waiting for a first answer: ask again for the text now; an incomplete answer: ask for more. Asked
            // before filtering, so that an incomplete list matching nothing for now waits for the answer.
            if (answered.isEmpty()) {
                requestLater(CompletionTriggerKind.Invoked)
            } else if (incomplete) {
                requestLater(CompletionTriggerKind.TriggerForIncompleteCompletions)
            }
            refilter(fresh = false)
            return
        }
        val caret = editor.caretOffset
        val (line, column) = lineAt(caret)
        val trigger =
            autoTrigger(
                change = change,
                typed = typedNow,
                caret = caret,
                selectionEmpty = editor.selection.length == 0,
                triggerCharacters = triggerCharacters(),
                lineBeforeCaret = line.substring(0, column),
                nextCodePoint = if (column < line.length) line.codePointAt(column) else null,
            )
        if (trigger == AutoTrigger.NONE || !supported()) return
        open(caret, column, line)
        when (trigger) {
            AutoTrigger.TRIGGER_CHARACTER -> request(CompletionTriggerKind.TriggerCharacter, typedNow)
            else -> requestLater(CompletionTriggerKind.Invoked)
        }
    }

    /** The caret moved: the completion closes when it left the word, else it filters for the new prefix. */
    fun caretMoved() {
        if (!isOpen) return
        if (caretInWord()) refilter(fresh = false) else close()
    }

    /** Ctrl+Space: asks at the caret now, showing "Searching…"; `false` (key not taken) without a server or with a selection. */
    fun invoke(): Boolean {
        if (!supported() || editor.selection.length != 0) return false
        val caret = editor.caretOffset
        val (line, column) = lineAt(caret)
        open(caret, column, line)
        if (!view.show(anchor, shown)) {
            close()
            return true
        }
        request(CompletionTriggerKind.Invoked, null)
        return true
    }

    /**
     * Escape while the completion waits for its first answer and shows nothing yet: it ends, so that no answer opens
     * the popup after Escape. The key stays the editor's (nothing showed); a shown popup takes Escape itself.
     */
    fun escape() {
        if (isOpen && !view.isShowing) close()
    }

    /** The popup hid (Escape, focus loss, a click outside, after a choice): the completion ends. */
    fun viewHidden() {
        if (isOpen) end()
    }

    /** The item whose detail shows: resolved if it was; asks the server for its documentation if missing. */
    fun detailOf(item: CompletionItem): CompletionItem {
        resolved[item]?.let { return it }
        if (item.documentation == null && item !== resolving && binding.session.features.supports(COMPLETION_RESOLVE)) {
            // Sending cancels the resolve of the item selected before, which is asked again when selected again.
            resolving = item
            resolveSlot.send(
                binding.session,
                { server -> server.textDocumentService.resolveCompletionItem(item) },
                { answer ->
                    if (resolving === item) resolving = null
                    if (answer != null && !binding.isClosed) {
                        resolved[item] = answer
                        if (isOpen) view.refreshDetail()
                    }
                },
                { if (resolving === item) resolving = null },
            )
        }
        return item
    }

    /**
     * Takes [item] (Enter, or Tab: [replace]) with one `applyEdits`, so one undo step ([acceptEdits]); additional edits
     * that no longer fit the text are left out and logged.
     */
    fun accept(
        item: CompletionItem,
        replace: Boolean,
    ) {
        val chosen = resolved[item] ?: item
        val changes = answeredChanges
        close()
        val caret = editor.caretOffset
        val (line, column) = lineAt(caret)
        val lineStart = caret - column
        val place =
            AcceptPlace(
                wordStart = lineStart + wordStart(line, column),
                caret = caret,
                wordEnd = lineStart + wordEnd(line, column),
                firstChange = changes.firstChange,
                requestCaret = changes.caret,
                offsetsOf = binding::offsetsOf,
                positionOf = binding::positionOf,
            )
        val edits = acceptEdits(chosen, replace, place)
        if (edits.droppedAdditional > 0) {
            log.log(
                System.Logger.Level.INFO,
                "completion '${chosen.label}': left out ${edits.droppedAdditional} additional edit(s) " +
                    "the text changed under",
            )
        }
        val applied = editor.applyEdits(edits.edits)
        if (!applied) log.log(System.Logger.Level.INFO, "completion: the editor refused the edit")
    }

    /** Ends the completion and hides the popup. */
    fun close() {
        if (!isOpen) return
        end()
        view.hide()
    }

    private fun end() {
        isOpen = false
        timer?.close()
        timer = null
        slot.cancel()
        resolveSlot.cancel()
        resolving = null
        pendingChanges = null
        shown = emptyList()
    }

    private fun open(
        caret: Int,
        column: Int,
        line: String,
    ) {
        if (isOpen) end()
        isOpen = true
        anchor = caret - column + wordStart(line, column)
        answered = emptyList()
        incomplete = false
        answeredChanges = ChangeTracker(caret)
        resolved.clear()
        resolving = null
    }

    private fun requestLater(kind: CompletionTriggerKind) {
        timer?.close()
        timer =
            schedule(debounce) {
                timer = null
                if (isOpen) request(kind, null)
            }
    }

    private fun request(
        kind: CompletionTriggerKind,
        character: String?,
    ) {
        timer?.close()
        timer = null
        val context = CompletionContext(kind).apply { triggerCharacter = character }
        val params = CompletionParams(binding.documentId, binding.positionOf(editor.caretOffset), context)
        val changes = ChangeTracker(editor.caretOffset)
        pendingChanges = changes
        slot.send(
            binding.session,
            { server -> server.textDocumentService.completion(params) },
            { result -> answer(result, changes) },
            { error ->
                log.log(System.Logger.Level.INFO, "completion request failed: $error")
                if (isOpen && answered.isEmpty()) close()
            },
        )
    }

    private fun answer(
        result: Either<List<CompletionItem>, CompletionList>?,
        changes: ChangeTracker,
    ) {
        if (!isOpen || binding.isClosed || changes !== pendingChanges) return
        pendingChanges = null
        answered =
            when {
                result == null -> {
                    emptyList()
                }

                result.isLeft -> {
                    result.left.orEmpty().filterNotNull()
                }

                else -> {
                    result.right
                        ?.items
                        .orEmpty()
                        .filterNotNull()
                }
            }
        incomplete = result?.isRight == true && result.right?.isIncomplete == true
        answeredChanges = changes
        resolved.clear()
        resolving = null
        refilter(fresh = true)
    }

    /**
     * Shows the answered items that match the prefix now. Without a match a [fresh] answer closes the completion;
     * otherwise the list stays as it is while a newer answer may come, and closes if none will.
     */
    private fun refilter(fresh: Boolean) {
        val caret = editor.caretOffset
        val prefix = textBetween(anchor, caret)
        val ranked = rankCompletions(answered, prefix)
        if (ranked.isEmpty()) {
            if (fresh || (pendingChanges == null && timer == null && answered.isNotEmpty())) close()
            return
        }
        shown = ranked
        if (!view.show(anchor, ranked)) close()
    }

    /** Whether the caret is still in the word the completion is for: at or after its start, which did not move. */
    private fun caretInWord(): Boolean {
        val caret = editor.caretOffset
        if (caret < anchor) return false
        val (line, column) = lineAt(caret)
        return caret - column + wordStart(line, column) == anchor
    }

    /** The caret's line and the column of [offset] in it. */
    private fun lineAt(offset: Int): Pair<String, Int> {
        val position = editor.positionOf(offset)
        val line = if (position.line < editor.lineCount) editor.lineText(position.line) else ""
        return line to position.column.coerceIn(0, line.length)
    }

    /** The text [start, end) within the caret's line. */
    private fun textBetween(
        start: Int,
        end: Int,
    ): String {
        val (line, column) = lineAt(end)
        val from = (column - (end - start)).coerceIn(0, column)
        return line.substring(from, column)
    }

    private fun supported() = binding.session.features.supports(COMPLETION)

    private fun triggerCharacters(): List<String> =
        binding.session.features.capabilities
            ?.completionProvider
            ?.triggerCharacters
            .orEmpty()
}

/**
 * The lowest offset the text changed at since a request was sent at [caret] (`null`: none sent); the text before it is
 * the request's.
 */
internal class ChangeTracker(
    val caret: Int?,
) {
    var firstChange: Int? = null
        private set

    fun changed(start: Int) {
        firstChange = minOf(firstChange ?: start, start)
    }
}
