package dev.sluide.codeintelligence.completion

import dev.sluide.codeintelligence.BoundEditor
import dev.sluide.codeintelligence.CompletionSource
import dev.sluide.codeintelligence.FeatureServer
import dev.sluide.codeintelligence.SourceCompletions
import dev.sluide.codeintelligence.awaitClientHandled
import dev.sluide.codeintelligence.awaitTrue
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * An editor bound over a fake server and a [CompletionModel] on a recording popup, for the model's tests; or, with
 * [setUpSource], the model of a fake completion source whose answers the test gives ([sourceCalls]).
 */
internal abstract class CompletionModelFixture {
    @TempDir
    lateinit var folder: Path

    protected lateinit var bound: BoundEditor
    protected lateinit var model: CompletionModel
    protected val view = RecordingCompletionView()

    @AfterEach
    fun close() {
        if (::bound.isInitialized) bound.close()
    }

    protected fun setUp(
        text: String,
        resolves: Boolean = false,
        newServer: (() -> FeatureServer)? = null,
    ) {
        bound = BoundEditor(folder, text, resolves, newServer ?: { FeatureServer(resolves) })
        model = bound.fx.onFx { CompletionModel(bound.binding, view, 100.milliseconds, bound.timers::schedule) }
        view.model = model
        bound.fx.onFx { bound.editor.onTextChange(model::textChanged) }
    }

    /** The source's requests, by caret, with the future the test completes. */
    protected val sourceCalls: MutableList<Pair<Int, CompletableFuture<SourceCompletions>>> =
        Collections.synchronizedList(mutableListOf())

    /**
     * A model of the editor's [text] that asks a source; one that has nothing to ask ([answers] false) gives `null`.
     * The source names [triggers] as its trigger characters, or none (the default's) when `null`.
     */
    protected fun setUpSource(
        text: String,
        answers: Boolean = true,
        triggers: List<String>? = null,
    ) {
        bound = BoundEditor(folder, text)
        val complete =
            CompletionSource { _, caret ->
                if (answers) CompletableFuture<SourceCompletions>().also { sourceCalls += caret to it } else null
            }
        val source =
            if (triggers == null) {
                complete
            } else {
                object : CompletionSource by complete {
                    override val triggerCharacters: List<String> = triggers
                }
            }
        model =
            bound.fx.onFx {
                val backend =
                    SourceCompletionBackend(bound.editor, source, bound.timers::schedule, bound.fx::dispatch, 2.seconds)
                CompletionModel(backend, view, 100.milliseconds, bound.timers::schedule)
            }
        view.model = model
        bound.fx.onFx { bound.editor.onTextChange(model::textChanged) }
    }

    protected fun type(character: String) = bound.type(character, model.keys::keyTyped)

    protected fun asked(count: Int) =
        bound.server.completions.also { awaitTrue("$count requests") { it.size == count } }

    protected fun list(
        vararg labels: String,
        incomplete: Boolean = true,
    ): Either<List<CompletionItem>, CompletionList> =
        Either.forRight(CompletionList(incomplete, labels.map { CompletionItem(it) }))

    protected fun lastShown(): List<String>? = bound.fx.onFx { view.shown.lastOrNull() }

    protected val isOpen get() = bound.fx.onFx { model.isOpen }

    /**
     * Waits until what the server sent before has reached the model: the server's own request is answered after
     * the responses sent before it were handed to the FX thread (the same stream, in order), which then runs them.
     */
    protected fun settle() = awaitClientHandled(bound.server.client) { bound.fx.onFx(it) }
}
