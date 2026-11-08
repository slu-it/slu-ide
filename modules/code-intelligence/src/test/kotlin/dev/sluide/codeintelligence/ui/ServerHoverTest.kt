package dev.sluide.codeintelligence.ui

import dev.sluide.codeintelligence.BoundEditor
import dev.sluide.codeintelligence.awaitTrue
import javafx.scene.Group
import javafx.scene.Node
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals

/**
 * The server's hover as a hover section: asked at the offset, its Markdown as one section, nothing for an empty
 * answer, and a question closed before its answer never answers.
 */
class ServerHoverTest {
    @TempDir
    lateinit var folder: Path

    private lateinit var bound: BoundEditor

    @AfterEach
    fun close() = bound.close()

    /** The answers of the provider: the blocks of each section, empty for nothing. */
    private val answers = CopyOnWriteArrayList<Any>()

    private fun ask(offset: Int): AutoCloseable =
        bound.fx.onFx {
            bound.binding.hovers.providers
                .single()
                .hover(offset) { sections: List<Node>? -> answers.add(sections?.map { it.userData }.orEmpty()) }
        }

    @Test
    fun `the hover's Markdown is one section, an empty answer none, a closed question none`() {
        bound = BoundEditor(folder, "import os\n")
        bound.fx.onFx { ServerHoverFeature { blocks -> Group().apply { userData = blocks } }.attach(bound.binding) }

        ask(7)
        awaitTrue("asked") { bound.server.hovers.size == 1 }
        assertEquals(
            Position(0, 7),
            bound.server.hovers[0]
                .params.position,
        )
        bound.server.hovers[0].answer(Hover(MarkupContent(MarkupKind.MARKDOWN, "```python\nos\n```")))
        awaitTrue("answered") { answers.size == 1 }
        assertEquals(listOf(listOf(DocBlock.Code("os"))), answers[0])

        val closed = ask(1)
        awaitTrue("asked again") { bound.server.hovers.size == 2 }
        bound.fx.onFx { closed.close() }
        bound.server.hovers[1].answer(Hover(MarkupContent(MarkupKind.MARKDOWN, "late")))

        ask(2)
        awaitTrue("asked a third time") { bound.server.hovers.size == 3 }
        bound.server.hovers[2].answer(Hover(MarkupContent(MarkupKind.PLAINTEXT, " ")))
        awaitTrue("answered") { answers.size == 2 }
        // The closed question's answer came before the third's, so it would have been second.
        assertEquals(emptyList<Any>(), answers[1], "nothing for blank text, the closed question never answered")
    }
}
