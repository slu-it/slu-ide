package dev.sluide.codeintelligence.completion

import dev.sluide.codeintelligence.binding.DocumentBinding
import dev.sluide.fileviewers.ExtensibleCodeEditor
import dev.sluide.languageservers.RequestSlot
import org.eclipse.lsp4j.CompletionContext
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.CompletionTriggerKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

/**
 * Where a [CompletionModel] gets its items for [editor]: the language server of a binding ([LspCompletionBackend]) or
 * a completion source of an editor no server serves. FX thread.
 */
internal interface CompletionBackend {
    val editor: ExtensibleCodeEditor

    /** Whether it can be asked now. */
    fun supported(): Boolean

    /** The characters that ask at once when typed. */
    fun triggerCharacters(): List<String>

    /**
     * Asks for the items at the caret: [answer] gets them and whether the list is incomplete, [failed] why there are
     * none; on the FX thread, at most one of them, and neither once [cancel] was called or a newer request was sent.
     */
    fun request(
        kind: CompletionTriggerKind,
        character: String?,
        answer: (items: List<CompletionItem>, incomplete: Boolean) -> Unit,
        failed: (reason: String) -> Unit,
    )

    /** Drops the request on its way, if any. */
    fun cancel()

    /** Whether items without documentation can be [resolve]d. */
    fun resolves(): Boolean

    /**
     * Asks for [item]'s documentation: [answer] gets the resolved item (`null`: none), [failed] is told when it fails;
     * a newer resolve or [cancelResolve] drops it.
     */
    fun resolve(
        item: CompletionItem,
        answer: (CompletionItem?) -> Unit,
        failed: () -> Unit,
    )

    /** Drops the resolve on its way, if any. */
    fun cancelResolve()

    /** The editor offsets of [range] in the current text, as [DocumentBinding.offsetsOf]. */
    fun offsetsOf(range: Range): IntRange

    /** The LSP position of [offset] in the current text. */
    fun positionOf(offset: Int): Position
}

/** The items of [binding]'s language server (ADR 0147): `textDocument/completion` and `completionItem/resolve`. */
internal class LspCompletionBackend(
    private val binding: DocumentBinding,
) : CompletionBackend {
    private val slot = RequestSlot()
    private val resolveSlot = RequestSlot()

    override val editor get() = binding.editor

    override fun supported() = binding.session.features.supports(COMPLETION)

    override fun triggerCharacters(): List<String> =
        binding.session.features.capabilities
            ?.completionProvider
            ?.triggerCharacters
            .orEmpty()

    override fun request(
        kind: CompletionTriggerKind,
        character: String?,
        answer: (items: List<CompletionItem>, incomplete: Boolean) -> Unit,
        failed: (reason: String) -> Unit,
    ) {
        val context = CompletionContext(kind).apply { triggerCharacter = character }
        val params = CompletionParams(binding.documentId, binding.positionOf(editor.caretOffset), context)
        slot.send(
            binding.session,
            { server -> server.textDocumentService.completion(params) },
            { result ->
                if (!binding.isClosed) {
                    // A list of items (left) is complete.
                    answer(itemsOf(result), result?.right?.isIncomplete == true)
                }
            },
            { error -> failed(error.toString()) },
        )
    }

    override fun cancel() = slot.cancel()

    override fun resolves() = binding.session.features.supports(COMPLETION_RESOLVE)

    override fun resolve(
        item: CompletionItem,
        answer: (CompletionItem?) -> Unit,
        failed: () -> Unit,
    ) {
        resolveSlot.send(
            binding.session,
            { server -> server.textDocumentService.resolveCompletionItem(item) },
            { resolved -> answer(resolved.takeUnless { binding.isClosed }) },
            { failed() },
        )
    }

    override fun cancelResolve() = resolveSlot.cancel()

    override fun offsetsOf(range: Range) = binding.offsetsOf(range)

    override fun positionOf(offset: Int) = binding.positionOf(offset)
}
