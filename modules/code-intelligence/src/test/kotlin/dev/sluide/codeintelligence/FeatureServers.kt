package dev.sluide.codeintelligence

import dev.sluide.codeintelligence.binding.DocumentBinding
import dev.sluide.languageservers.FakeServer
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionOptions
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.SignatureHelpOptions
import org.eclipse.lsp4j.SignatureHelpParams
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration

/** A request the [FeatureServer] got, answered by the test with [answer]. */
internal class Asked<P, R>(
    val params: P,
    val future: CompletableFuture<R>,
) {
    fun answer(result: R) {
        future.complete(result)
    }
}

/**
 * A fake server that syncs like ty and offers completion (trigger `.`, resolve if [resolves]), signature help
 * (triggers `(` `,`, retrigger `)`) and hover; it keeps every request for the test to answer.
 */
internal class FeatureServer(
    resolves: Boolean = false,
) : FakeServer() {
    val completions = CopyOnWriteArrayList<Asked<CompletionParams, Either<List<CompletionItem>, CompletionList>>>()
    val resolved = CopyOnWriteArrayList<Asked<CompletionItem, CompletionItem>>()
    val signatures = CopyOnWriteArrayList<Asked<SignatureHelpParams, SignatureHelp?>>()
    val hovers = CopyOnWriteArrayList<Asked<HoverParams, Hover?>>()

    init {
        capabilities =
            syncingServer().capabilities.apply {
                completionProvider = CompletionOptions(resolves, listOf("."))
                signatureHelpProvider = SignatureHelpOptions(listOf("(", ","), listOf(")"))
                hoverProvider = Either.forLeft(true)
            }
    }

    override fun completion(params: CompletionParams) =
        CompletableFuture<Either<List<CompletionItem>, CompletionList>>().also { completions += Asked(params, it) }

    override fun resolveCompletionItem(unresolved: CompletionItem) =
        CompletableFuture<CompletionItem>().also { resolved += Asked(unresolved, it) }

    override fun signatureHelp(params: SignatureHelpParams) =
        CompletableFuture<SignatureHelp?>().also { signatures += Asked(params, it) }

    override fun hover(params: HoverParams) = CompletableFuture<Hover?>().also { hovers += Asked(params, it) }
}

/** The delays scheduled by a feature under test; the test runs them. */
internal class TestTimers {
    val due = mutableListOf<Pair<Duration, () -> Unit>>()

    fun schedule(
        delay: Duration,
        action: () -> Unit,
    ): AutoCloseable {
        val entry = delay to action
        due += entry
        return AutoCloseable { due.remove(entry) }
    }

    /** Runs the one delay due. */
    fun runDue() = due.single().also { due.remove(it) }.second()
}

/**
 * An editor of `a.py` in [folder] with [text] and the caret at its end, bound to a running [FeatureServer] whose
 * results arrive on a thread that plays the FX thread ([fx]).
 */
internal class BoundEditor(
    folder: Path,
    text: String,
    resolves: Boolean = false,
) : AutoCloseable {
    private val spec = PySpec()
    val fx = FxServers(folder, spec) { FeatureServer(resolves) }
    val server = fx.fake.awaitRunning(spec) as FeatureServer
    val editor = FakeCodeEditor(folder.resolve("a.py"), text).apply { moveCaret(text.length) }
    val binding = fx.onFx { DocumentBinding(editor, spec, fx.servers) }
    val timers = TestTimers()

    /** Types [character] at the caret as a key does (the key typed, then the change). */
    fun type(
        character: String,
        keyTyped: (String) -> Unit,
    ) = fx.onFx {
        keyTyped(character)
        editor.typeAtCaret(character)
    }

    override fun close() {
        fx.onFx { binding.close() }
        fx.close()
    }
}
