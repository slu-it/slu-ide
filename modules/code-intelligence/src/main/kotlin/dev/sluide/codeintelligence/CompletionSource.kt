package dev.sluide.codeintelligence

import dev.sluide.fileviewers.ExtensibleCodeEditor
import java.util.concurrent.CompletableFuture

/**
 * Completion for editors no language server serves (ADR 0145 amendment), shown in the completion popup of the language
 * servers' completion (ADR 0147). Registered with [CodeIntelligence.addCompletionSource].
 */
fun interface CompletionSource {
    /** Typed alone, each asks this source at once, as a server's trigger characters do: a member access by default. */
    val triggerCharacters: List<String> get() = listOf(".")

    /**
     * The completions at [caret] (whole-document offset) of [editor]'s current text, `null` when this source has none
     * for the editor now. Called on the FX thread; the future may complete on any thread; a failed future, or none
     * within the model's timeout (2 s), shows no items.
     */
    fun complete(
        editor: ExtensibleCodeEditor,
        caret: Int,
    ): CompletableFuture<SourceCompletions>?
}

/** The answer of a [CompletionSource]: each item replaces the document from [anchor] (an offset) to the caret. */
data class SourceCompletions(
    val anchor: Int,
    /** In the order the popup shows them while nothing more is typed. */
    val items: List<SourceCompletion>,
)

/** One completion of a [CompletionSource]: [label] shows in the list, [insertText] is inserted, [detail] beside it. */
data class SourceCompletion(
    val label: String,
    val insertText: String = label,
    val detail: String? = null,
)
