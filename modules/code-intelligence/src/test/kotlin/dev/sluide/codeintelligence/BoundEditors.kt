package dev.sluide.codeintelligence

import dev.sluide.codeintelligence.binding.BindingFeature
import dev.sluide.codeintelligence.binding.DocumentBinding
import dev.sluide.fileviewers.OffsetEdit
import dev.sluide.fileviewers.StyledRange
import dev.sluide.languageservers.FakeLanguageServers
import dev.sluide.languageservers.FakeServer
import dev.sluide.languageservers.ServerState
import javafx.collections.FXCollections
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/** Delays the test runs by hand ([BoundEditorHarness.run]): those scheduled and not cancelled. */
internal class ManualDelays {
    private val pending = mutableListOf<() -> Unit>()

    val count: Int get() = synchronized(pending) { pending.size }

    /** How many were scheduled in all, cancelled ones included. */
    @Volatile
    var scheduled = 0
        private set

    fun schedule(
        @Suppress("UNUSED_PARAMETER") delay: Duration,
        action: () -> Unit,
    ): AutoCloseable {
        synchronized(pending) {
            pending += action
            scheduled++
        }
        return AutoCloseable { synchronized(pending) { pending -= action } }
    }

    /** The scheduled actions, taken out; run them on the FX thread. */
    fun takeAll(): List<() -> Unit> = synchronized(pending) { pending.toList().also { pending.clear() } }
}

/** A fake editor that records the overlay layers and applies [applyEdits] as one batch. */
internal class OverlayEditor(
    file: Path,
    text: String,
) : FakeCodeEditor(file, text) {
    val layers = mutableMapOf<String, List<StyledRange>>()
    val appliedBatches = mutableListOf<List<OffsetEdit>>()

    /** How often each layer was set. */
    val overlayCalls = mutableMapOf<String, Int>()

    override fun overlay(
        layer: String,
        ranges: List<StyledRange>,
        followEdits: Boolean,
    ) {
        check(followEdits) { "$layer should follow edits" }
        layers[layer] = ranges
        overlayCalls.merge(layer, 1, Int::plus)
    }

    override fun applyEdits(edits: List<OffsetEdit>): Boolean {
        appliedBatches += edits
        batch(edits.sortedByDescending { it.start }.map { Triple(it.start, it.end, it.text) })
        return true
    }
}

/**
 * One Python editor bound over a running fake server [S], with [features] made for its binding and [extra] features
 * after them; the results of the fake server arrive on a single thread that plays the FX thread ([onFx]).
 */
internal class BoundEditorHarness<S : FakeServer>(
    folder: Path,
    text: String,
    newServer: () -> S,
    features: (DocumentBinding) -> List<AutoCloseable> = { emptyList() },
    extra: List<BindingFeature> = emptyList(),
) : AutoCloseable {
    private val fx = Executors.newSingleThreadExecutor { Thread(it, "test-fx").apply { isDaemon = true } }
    val spec = PySpec()
    val fake = FakeLanguageServers(listOf(spec), folder, dispatch = fx::execute) { newServer() }

    /** The latest server started. */
    @Suppress("UNCHECKED_CAST")
    val server: S get() = fake.started.last() as S
    val editor = OverlayEditor(folder.resolve("a.py"), text)
    lateinit var binding: DocumentBinding
    val handles = mutableListOf<AutoCloseable>()
    private val intelligence: CodeIntelligence

    init {
        fake.awaitRunning(spec)
        val capture =
            BindingFeature { bound ->
                binding = bound
                handles += features(bound)
                AutoCloseable { handles.asReversed().forEach { it.close() } }
            }
        intelligence =
            onFx { CodeIntelligence(fake.servers, FXCollections.observableSet()) { listOf(capture) + extra } }
        onFx { intelligence.extend(editor) }
    }

    fun <T> onFx(action: () -> T): T = fx.submit(action).get(5, TimeUnit.SECONDS)

    /** Runs the delays scheduled so far on the FX thread. */
    fun run(delays: ManualDelays) = onFx { delays.takeAll().forEach { it() } }

    fun awaitState(state: ServerState) = awaitTrue("state $state") { onFx { binding.session.state.value } == state }

    override fun close() {
        onFx { intelligence.close() }
        fake.close()
        fx.shutdownNow()
    }
}
