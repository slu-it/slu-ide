package dev.sluide.languageservers

import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import kotlin.test.fail

/**
 * Fails unless every `didClose`, `didChange` and `didSave` [this] server got is for a document it got a `didOpen` for
 * and no `didClose` since (ADR 0160: the TypeScript server crashes on a `didClose` of a document it never saw).
 */
internal fun FakeServer.assertClosesFollowOpens() {
    val open = HashSet<String>()
    for (notification in documentNotifications) {
        when (notification) {
            is DidOpenTextDocumentParams -> open.add(notification.textDocument.uri)
            is DidCloseTextDocumentParams -> expectOpen(open, notification.textDocument.uri, "didClose", remove = true)
            is DidChangeTextDocumentParams -> expectOpen(open, notification.textDocument.uri, "didChange")
            is DidSaveTextDocumentParams -> expectOpen(open, notification.textDocument.uri, "didSave")
        }
    }
}

private fun FakeServer.expectOpen(
    open: MutableSet<String>,
    uri: String,
    what: String,
    remove: Boolean = false,
) {
    val known = if (remove) open.remove(uri) else uri in open
    if (!known) fail("$what of $uri, not open on this server: $calls")
}
