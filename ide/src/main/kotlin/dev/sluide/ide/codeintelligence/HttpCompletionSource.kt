package dev.sluide.ide.codeintelligence

import dev.sluide.codeintelligence.CompletionSource
import dev.sluide.codeintelligence.SourceCompletion
import dev.sluide.codeintelligence.SourceCompletions
import dev.sluide.fileviewers.ExtensibleCodeEditor
import dev.sluide.httpclient.HttpCompletion
import dev.sluide.httpclient.HttpCompletions
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/**
 * Completion in `.http` editors from http-client's own engine (ADR 0195): reads the editor's text and caret on the FX
 * thread, where it is called; the answer comes from a virtual thread. [projectFolder] bounds the search for
 * environment files.
 */
internal class HttpCompletionSource(
    private val projectFolder: () -> Path?,
) : CompletionSource {
    override val triggerCharacters: List<String> get() = HttpCompletion.triggerCharacters

    override fun complete(
        editor: ExtensibleCodeEditor,
        caret: Int,
    ): CompletableFuture<SourceCompletions> =
        HttpCompletion.complete(editor.file.value, editor.text, caret, projectFolder()).thenApply(::sourceCompletions)
}

/** http-client's [completions] for the popup, one to one: the same anchor and items in the same order. */
internal fun sourceCompletions(completions: HttpCompletions): SourceCompletions =
    SourceCompletions(
        completions.anchor,
        completions.items.map { SourceCompletion(it.label, it.insertText, it.detail) },
    )
