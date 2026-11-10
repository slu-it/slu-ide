package dev.sluide.languageservers

import java.nio.file.Path

/**
 * Whether a spec's server may see one document at all ([LanguageServerSpec.gate], ADR 0160): a document that is not
 * served is never sent to the server (no `didOpen`, `didChange`, `didSave`, `didClose`), and a server whose documents
 * are all not served is never started.
 *
 * @property served whether the server gets the document.
 * @property watched folders whose entries decide the answer and that the project watch does not cover (excluded ones
 *   such as `node_modules`, folders outside the window folder). The session watches each one, not its subfolders,
 *   while an open document's answer names it: a change there asks the gate of the documents that named it again, after
 *   a second without further changes, and reaches the server as a file event (`workspace/didChangeWatchedFiles`, if
 *   one of its watchers wants it). A folder that does not exist (yet) reports nothing: name its parent too if its
 *   appearing matters.
 * @property reason why the document is not served, for the editor to show; `null` for none.
 */
data class DocumentGate(
    val served: Boolean,
    val watched: List<Path> = emptyList(),
    val reason: EnvironmentProblem? = null,
) {
    companion object {
        /** Served, nothing watched: the answer of a spec that does not gate documents. */
        val SERVED = DocumentGate(true)
    }
}
