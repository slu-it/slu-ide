package dev.sluide.ide.codeintelligence

import dev.sluide.codeintelligence.CompletionSource
import dev.sluide.codeintelligence.SourceCompletion
import dev.sluide.codeintelligence.SourceCompletions
import dev.sluide.fileviewers.ExtensibleCodeEditor
import dev.sluide.fileviewers.FileView
import dev.sluide.jshell.Completions
import dev.sluide.jshell.JShellFileView
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.io.path.extension

/**
 * Completion in `.jsh` editors from JShell's own analysis (ADR 0145 amendment, ADR 0191): asks the view that shows the
 * editor's file in the window ([viewOf]); none while no JShell view shows it.
 */
internal class JShellCompletion(
    private val viewOf: (Path) -> FileView?,
) : CompletionSource {
    override fun complete(
        editor: ExtensibleCodeEditor,
        caret: Int,
    ): CompletableFuture<SourceCompletions>? =
        (viewOf(editor.file.value) as? JShellFileView)?.let { it.complete(caret).thenApply(::sourceCompletions) }
}

/** Whether JShell completes [file]: a `.jsh` file, as the JShell viewer opens. */
internal fun isJShellFile(file: Path): Boolean = file.extension.lowercase() == "jsh"

/** JShell's [completions] for the popup: the smart ones first, then the others, each in JShell's order. */
internal fun sourceCompletions(completions: Completions): SourceCompletions =
    SourceCompletions(
        completions.anchor,
        // A stable sort: each group keeps JShell's order.
        completions.items.sortedByDescending { it.smart }.map { SourceCompletion(it.text) },
    )
