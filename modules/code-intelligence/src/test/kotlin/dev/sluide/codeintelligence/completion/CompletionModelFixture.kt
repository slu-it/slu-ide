package dev.sluide.codeintelligence.completion

import dev.sluide.codeintelligence.BoundEditor
import dev.sluide.codeintelligence.FeatureServer
import dev.sluide.codeintelligence.awaitTrue
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

/** An editor bound over a fake server and a [CompletionModel] on a recording popup, for the model's tests. */
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
        bound =
            if (newServer ==
                null
            ) {
                BoundEditor(folder, text, resolves)
            } else {
                BoundEditor(folder, text, resolves, newServer)
            }
        model = bound.fx.onFx { CompletionModel(bound.binding, view, 100.milliseconds, bound.timers::schedule) }
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
    protected fun settle() {
        bound.server.client
            .refreshDiagnostics()
            .get(5, TimeUnit.SECONDS)
        bound.fx.idle()
    }
}
