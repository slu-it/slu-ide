package dev.sluide.codeintelligence.ui

import dev.sluide.codeintelligence.binding.BindingFeature
import dev.sluide.codeintelligence.binding.DocumentBinding
import dev.sluide.languageservers.RequestSlot
import dev.sluide.languageservers.ServerState
import javafx.scene.Node
import org.eclipse.lsp4j.HoverParams

/** The LSP method of [ServerHoverFeature]. */
internal const val HOVER = "textDocument/hover"

/**
 * The language server's hover as one section of the hover popup (ADR 0147): `textDocument/hover` at the offset, its
 * Markdown or plain text shown by [render]. Nothing while the server does not run or does not answer hovers; a newer
 * question cancels the one before, and so does closing its handle. FX thread.
 *
 * @param render the section for the answer's blocks; [MarkupView] in the app.
 */
internal class ServerHoverFeature(
    private val render: (List<DocBlock>) -> Node = ::MarkupView,
) : BindingFeature {
    override fun attach(binding: DocumentBinding): AutoCloseable {
        val slot = RequestSlot()
        val provider =
            HoverProvider { offset, done ->
                val session = binding.session
                if (session.state.value != ServerState.Running || !session.features.supports(HOVER)) {
                    done(null)
                    return@HoverProvider AutoCloseable {}
                }
                val params = HoverParams(binding.documentId, binding.positionOf(offset))
                val pending =
                    slot.send(
                        session,
                        { server -> server.textDocumentService.hover(params) },
                        { hover ->
                            val blocks = if (binding.isClosed) emptyList() else hoverBlocks(hover)
                            done(if (blocks.isEmpty()) null else listOf(render(blocks)))
                        },
                        { done(null) },
                    )
                AutoCloseable { pending.cancel() }
            }
        val added = binding.hovers.add(provider)
        return AutoCloseable {
            added.close()
            slot.cancel()
        }
    }
}
