package dev.sluide.codeintelligence.binding

import dev.sluide.codeintelligence.CompletionSource
import dev.sluide.fileviewers.ExtensibleCodeEditor
import dev.sluide.languageservers.LanguageServers
import java.nio.file.Path

/**
 * The code intelligence of one code editor (ADR 0145), for its whole life: bound ([binding]) while a language server
 * serves its file, following renames and moves (bound, unbound, bound to another language, or the document moved
 * within its language), and the one context menu provider that shows the binding's items. While no server serves the
 * file, the completion of the [CompletionSource] that applies to it, if any (ADR 0145 amendment). [closed] is told once
 * on [close]. FX thread.
 */
internal class EditorAttachment(
    private val editor: ExtensibleCodeEditor,
    private val languageServers: LanguageServers,
    private val features: List<BindingFeature>,
    /** The window's bound editor of a file, for the bindings' [DocumentBinding.bindingOf]. */
    private val bindingOf: (Path) -> DocumentBinding?,
    /** The window's bound editors, for the bindings' [DocumentBinding.bindings]. */
    private val bindings: () -> List<DocumentBinding>,
    /** The window's completion source of a file, for an editor no server serves. */
    private val sourceFor: (Path) -> CompletionSource?,
    /** Gives [editor] the completion of a source until the handle is closed. */
    private val attachSource: (ExtensibleCodeEditor, CompletionSource) -> AutoCloseable,
    private val closed: () -> Unit,
) : AutoCloseable {
    /** The binding while the editor's file is served. */
    var binding: DocumentBinding? = null
        private set

    private var isClosed = false

    /** The source whose completion the editor has, and its handle. */
    private var source: CompletionSource? = null
    private var sourceCompletion: AutoCloseable? = null

    // A provider per editor, not per binding: the editor's menu items cannot be removed, and a file renamed back and
    // forth binds again.
    init {
        editor.addContextMenuItems { target -> binding?.menuItems(target).orEmpty() }
    }

    private val fileSubscription = editor.file.subscribe { file -> follow(file) }

    /** Binds, unbinds or moves the document for the editor's file now at [file]. */
    private fun follow(file: Path) {
        if (isClosed) return
        val spec = languageServers.specFor(file)
        val current = binding
        if (current != null && spec == current.spec) {
            current.moved(file)
            return
        }
        current?.close()
        binding = null
        followSource(file.takeIf { spec == null })
        if (spec != null) {
            binding = DocumentBinding(editor, spec, languageServers, bindingOf, bindings).also { it.attach(features) }
        }
    }

    /** The window's sources changed: the editor gets the completion of the one that applies now. */
    fun sourcesChanged() {
        if (isClosed) return
        if (binding == null) followSource(editor.file.value)
    }

    /** Gives the editor the completion of the source that applies to [file] (none for `null`), if not already. */
    private fun followSource(file: Path?) {
        val wanted = file?.let(sourceFor)
        if (wanted === source) return
        sourceCompletion?.close()
        sourceCompletion = null
        source = wanted
        if (wanted != null) sourceCompletion = attachSource(editor, wanted)
    }

    /** Unbinds for good: the editor was disposed or the window closed. */
    override fun close() {
        if (isClosed) return
        isClosed = true
        fileSubscription.unsubscribe()
        sourceCompletion?.close()
        binding?.close()
        binding = null
        closed()
    }
}
