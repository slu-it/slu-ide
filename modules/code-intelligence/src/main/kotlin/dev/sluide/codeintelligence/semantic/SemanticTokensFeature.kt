package dev.sluide.codeintelligence.semantic

import dev.sluide.codeintelligence.binding.BindingFeature
import dev.sluide.codeintelligence.binding.DocumentBinding
import dev.sluide.codeintelligence.ui.fxDelay
import dev.sluide.languageservers.RefreshKind
import dev.sluide.languageservers.RequestSlot
import dev.sluide.languageservers.ServerState
import org.eclipse.lsp4j.SemanticTokensParams
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** The overlay layer of the semantic highlighting. */
internal const val SEMANTIC_LAYER = "ci-semantic"

internal const val SEMANTIC_TOKENS_METHOD = "textDocument/semanticTokens/full"

private val SEMANTIC_CSS: String =
    checkNotNull(SemanticTokensFeature::class.java.getResource("semantic-tokens.css")).toExternalForm()

/**
 * Semantic highlighting (ADR 0148): when the server answers `textDocument/semanticTokens/full`, the tokens of the
 * whole document after open, [delay] after the last change (the latest request wins), on the server's refresh and
 * after a restart (with the new server's legend); an answer for an older text is dropped and asked again, and the
 * colours go when the server stops running. Parameters, `self`/`cls` and builtin constants get subtle colours over the
 * lexical ones ([semanticClasses]) as the overlay layer [SEMANTIC_LAYER], which follows edits. FX thread.
 */
internal class SemanticTokensFeature(
    private val delay: Duration = 500.milliseconds,
    private val schedule: (Duration, () -> Unit) -> AutoCloseable = ::fxDelay,
) : BindingFeature {
    override fun attach(binding: DocumentBinding): AutoCloseable = DocumentTokens(binding)

    private inner class DocumentTokens(
        private val binding: DocumentBinding,
    ) : AutoCloseable {
        private val session = binding.session
        private val slot = RequestSlot()
        private val handles = mutableListOf<AutoCloseable>()
        private var pending: AutoCloseable? = null
        private var supported = false
        private var closed = false

        init {
            handles += binding.editor.addStylesheet(SEMANTIC_CSS)
            handles += binding.editor.onTextChange { requestSoon() }
            handles += session.onConnected { supported = false }
            val stopped =
                session.state.subscribe { old, now ->
                    if (old == ServerState.Running && now != ServerState.Running) serverStopped()
                }
            handles += AutoCloseable { stopped.unsubscribe() }
            handles += session.onFeaturesChanged { featuresChanged() }
            handles += session.onRefresh(RefreshKind.SEMANTIC_TOKENS) { if (supported) request() }
            val moved = binding.editor.file.subscribe { _, _ -> if (supported) request() }
            handles += AutoCloseable { moved.unsubscribe() }
            featuresChanged()
        }

        private fun featuresChanged() {
            val now = session.features.supports(SEMANTIC_TOKENS_METHOD)
            val was = supported
            supported = now
            if (now && !was) {
                request()
            } else if (!now && was) {
                binding.editor.overlay(SEMANTIC_LAYER, emptyList(), true)
            }
        }

        /** The server stopped: its colours are no longer current; a new one is asked when it runs. */
        private fun serverStopped() {
            if (closed) return
            pending?.close()
            pending = null
            slot.cancel()
            binding.editor.overlay(SEMANTIC_LAYER, emptyList(), true)
        }

        private fun requestSoon() {
            if (!supported || closed) return
            pending?.close()
            pending = schedule(delay) { request() }
        }

        private fun request() {
            if (closed) return
            pending?.close()
            pending = null
            val legend =
                session.features.capabilities
                    ?.semanticTokensProvider
                    ?.legend ?: return
            val version = binding.document.version
            slot.send(
                session,
                { it.textDocumentService.semanticTokensFull(SemanticTokensParams(binding.documentId)) },
                { tokens ->
                    when {
                        closed || tokens == null -> {
                            Unit
                        }

                        binding.document.version != version -> {
                            requestSoon()
                        }

                        else -> {
                            val decoded = decodeTokens(tokens.data.orEmpty().map { it ?: 0 }, legend)
                            binding.editor.overlay(SEMANTIC_LAYER, semanticRanges(decoded, binding.document.text), true)
                        }
                    }
                },
            )
        }

        override fun close() {
            closed = true
            pending?.close()
            slot.cancel()
            handles.asReversed().forEach { it.close() }
            handles.clear()
            binding.editor.overlay(SEMANTIC_LAYER, emptyList(), true)
        }
    }
}
