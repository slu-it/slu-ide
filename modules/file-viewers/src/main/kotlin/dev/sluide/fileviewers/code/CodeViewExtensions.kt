package dev.sluide.fileviewers.code

import dev.sluide.fileviewers.CodeEditorExtension
import dev.sluide.fileviewers.CodeEditorExtensions
import dev.sluide.fileviewers.ExtensibleCodeEditor
import dev.sluide.fileviewers.FileViewHost

private val log = System.getLogger(CodeViewExtensions::class.java.name)

/**
 * The [CodeEditorExtensions] of a code view, seen by them as [editor] (ADR 0108): [attach] extends it with those of the
 * first host that has the window service, later hosts change nothing; [dispose] closes what the extensions returned.
 * [attaching] gets that host before the extensions are called (the editor's [ExtensibleCodeEditor.host]). A
 * [confidential] editor (its content must stay in the view) gets only the extensions that
 * [attach to confidential content][CodeEditorExtension.attachesToConfidential]. FX thread.
 */
internal class CodeViewExtensions(
    private val editor: ExtensibleCodeEditor,
    private val confidential: Boolean = false,
    private val attaching: (FileViewHost) -> Unit = {},
) {
    private var attached = false
    private var disposed = false
    private val handles = mutableListOf<AutoCloseable>()

    /** Extends the view with the extensions of [host]'s window, unless it was extended before or has no such host. */
    fun attach(host: FileViewHost?) {
        if (attached || disposed || host == null) return
        val extensions = host.windowService(CodeEditorExtensions::class.java) ?: return
        attached = true
        attaching(host)
        for (extension in extensions.extensions) {
            if (confidential && !extension.attachesToConfidential) continue
            try {
                extension.extend(editor)?.let(handles::add)
            } catch (e: Exception) {
                log.log(System.Logger.Level.ERROR, "Could not extend the code editor of ${editor.file.value}", e)
            }
        }
    }

    /** Closes the handles of the extensions; the view is not extended any more. */
    fun dispose() {
        disposed = true
        for (handle in handles) {
            try {
                handle.close()
            } catch (e: Exception) {
                log.log(
                    System.Logger.Level.ERROR,
                    "Could not close an extension of the code editor of ${editor.file.value}",
                    e,
                )
            }
        }
        handles.clear()
    }
}
