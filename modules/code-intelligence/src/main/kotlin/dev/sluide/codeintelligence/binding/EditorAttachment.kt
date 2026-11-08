package dev.sluide.codeintelligence.binding

import dev.sluide.fileviewers.ExtensibleCodeEditor
import dev.sluide.languageservers.LanguageServers
import java.nio.file.Path

/**
 * The code intelligence of one code editor (ADR 0145), for its whole life: bound ([binding]) while a language server
 * serves its file, following renames and moves (bound, unbound, bound to another language, or the document moved
 * within its language), and the one context menu provider that shows the binding's items. [closed] is told once on
 * [close]. FX thread.
 */
internal class EditorAttachment(
    private val editor: ExtensibleCodeEditor,
    private val languageServers: LanguageServers,
    private val features: List<BindingFeature>,
    private val closed: () -> Unit,
) : AutoCloseable {
    /** The binding while the editor's file is served. */
    var binding: DocumentBinding? = null
        private set

    private var isClosed = false

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
        if (spec != null) {
            binding = DocumentBinding(editor, spec, languageServers).also { it.attach(features) }
        }
    }

    /** Unbinds for good: the editor was disposed or the window closed. */
    override fun close() {
        if (isClosed) return
        isClosed = true
        fileSubscription.unsubscribe()
        binding?.close()
        binding = null
        closed()
    }
}
