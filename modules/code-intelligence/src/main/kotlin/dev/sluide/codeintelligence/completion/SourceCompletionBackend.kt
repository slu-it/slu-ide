package dev.sluide.codeintelligence.completion

import dev.sluide.codeintelligence.CompletionSource
import dev.sluide.codeintelligence.SourceCompletions
import dev.sluide.codeintelligence.binding.LineIndex
import dev.sluide.fileviewers.ExtensibleCodeEditor
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionTriggerKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** How long a [CompletionSource] may take to answer. */
internal val SOURCE_TIMEOUT = 2.seconds

/**
 * The items of a [CompletionSource] (ADR 0145 amendment) for an editor no language server serves: asked on Ctrl+Space,
 * a typed trigger character of the source and after the debounce in a word, as a server is; its answers are complete
 * (typing on filters them) and arrive on any thread. An answer that does not come within [timeout], a failed one and none at all (`null`) fail the
 * request; a newer request drops the one before. Each item replaces from the source's anchor to the caret of the
 * request, as a server's `textEdit` does, and keeps the source's order while it matches as well as the others.
 *
 * @param dispatch runs a task on the FX thread, for the source's answers.
 * @param timeout how long an answer may take before the request counts as failed.
 */
internal class SourceCompletionBackend(
    override val editor: ExtensibleCodeEditor,
    private val source: CompletionSource,
    private val schedule: (Duration, () -> Unit) -> AutoCloseable,
    private val dispatch: (Runnable) -> Unit,
    private val timeout: Duration,
) : CompletionBackend {
    /** The request on its way; its answer or failure counts only while it is this one. */
    private var pending: Any? = null
    private var timer: AutoCloseable? = null

    override fun supported() = true

    override fun triggerCharacters(): List<String> = source.triggerCharacters

    override fun request(
        kind: CompletionTriggerKind,
        character: String?,
        answer: (items: List<CompletionItem>, incomplete: Boolean) -> Unit,
        failed: (reason: String) -> Unit,
    ) {
        cancel()
        val caret = editor.caretOffset
        val text = editor.text
        val future = source.complete(editor, caret) ?: return failed("the source has no completion here")
        val request = Any()
        pending = request
        timer =
            schedule(timeout) {
                cancel()
                failed("no answer within $timeout")
            }
        future.whenComplete { result, error ->
            dispatch {
                if (pending !== request) return@dispatch
                cancel()
                if (error != null) failed(error.toString()) else answer(sourceItems(result, text, caret), false)
            }
        }
    }

    override fun cancel() {
        pending = null
        timer?.close()
        timer = null
    }

    override fun resolves() = false

    override fun resolve(
        item: CompletionItem,
        answer: (CompletionItem?) -> Unit,
        failed: () -> Unit,
    ) {}

    override fun cancelResolve() {}

    override fun offsetsOf(range: Range): IntRange {
        val index = LineIndex(editor.text)
        val start = index.offsetOf(range.start)
        return start until index.offsetOf(range.end).coerceAtLeast(start)
    }

    override fun positionOf(offset: Int): Position = positionIn(editor.text, offset)
}

/**
 * The items of a source's [completions] asked at [caret] of [text]: each replaces from the anchor (kept within the
 * caret's text) to [caret], sorted in the source's order. When the anchor lies before the word at the caret (the word
 * chars [rankCompletions] filters by), an item whose label starts with the fixed part between them (ignoring case;
 * from the caret's line start at most) filters by the rest of its label.
 */
internal fun sourceItems(
    completions: SourceCompletions,
    text: String,
    caret: Int,
): List<CompletionItem> {
    val anchor = completions.anchor.coerceIn(0, caret)
    val end = positionIn(text, caret)
    val range = Range(positionIn(text, anchor), end)
    val fixedStart = maxOf(anchor, caret - end.character)
    val word = wordStart(text, caret)
    val fixed = if (fixedStart < word) text.substring(fixedStart, word) else null
    return completions.items.mapIndexed { index, item ->
        CompletionItem(item.label).apply {
            filterText =
                fixed?.takeIf { item.label.startsWith(it, ignoreCase = true) }?.let { item.label.substring(it.length) }
            detail = item.detail
            sortText = "%08d".format(index)
            textEdit = Either.forLeft(TextEdit(range, item.insertText))
        }
    }
}

/** The LSP position of [offset] (clamped) in [text]. */
private fun positionIn(
    text: String,
    offset: Int,
): Position {
    val index = LineIndex(text)
    val at = offset.coerceIn(0, text.length)
    val line = index.lineOf(at)
    return Position(line, at - index.offsetOf(line, 0))
}
