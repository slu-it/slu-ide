package dev.sluide.codeintelligence.binding

import dev.sluide.codeintelligence.CodeIntelligence
import dev.sluide.codeintelligence.FakeCodeEditor
import dev.sluide.codeintelligence.FxServers
import dev.sluide.codeintelligence.PySpec
import dev.sluide.codeintelligence.awaitTrue
import dev.sluide.languageservers.DocumentGate
import javafx.collections.FXCollections
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals

/**
 * The request features' gate: attached while the document is served, closed (last attached first) when it is not,
 * whatever a feature does when it attaches or closes. Over a fake server whose gate answer the test switches.
 */
class ServedFeaturesTest {
    @TempDir
    lateinit var folder: Path

    private val answer = AtomicReference(DocumentGate(false))
    private lateinit var fx: FxServers
    private lateinit var editor: FakeCodeEditor
    private lateinit var binding: DocumentBinding
    private var moves = 0

    @AfterEach
    fun close() = fx.close()

    /** A binding of a gating spec, with no features of its own yet; [answer] decides whether it is served. */
    private fun bind() {
        val spec = PySpec(gateAnswer = answer)
        fx = FxServers(folder, spec)
        val intelligence = fx.onFx { CodeIntelligence(fx.servers, FXCollections.observableSet()) { emptyList() } }
        editor = FakeCodeEditor(folder.resolve("a.py"), "x")
        fx.onFx { intelligence.extend(editor) }
        binding = checkNotNull(fx.onFx { intelligence.attached.single().binding })
        awaitTrue("answered") { fx.onFx { fx.servers.session(spec).gateOf(binding.file) } != null }
    }

    /** The server's answer changes to [served], and the document moves so that the gate is asked again. */
    private fun serve(served: Boolean) {
        answer.set(if (served) DocumentGate.SERVED else DocumentGate(false))
        fx.onFx { editor.fileProperty.set(folder.resolve("m${moves++}.py")) }
        awaitTrue("served $served") { fx.onFx { binding.served.value } == served }
    }

    private fun feature(
        events: MutableList<String>,
        name: String,
        attachFails: Boolean = false,
        closeFails: Boolean = false,
    ) = BindingFeature {
        events += "$name attached"
        if (attachFails) error("$name broken")
        AutoCloseable {
            events += "$name closed"
            if (closeFails) error("$name close broken")
        }
    }

    @Test
    fun `every handle closes once, however often the document is served and not served again`() {
        bind()
        val events = mutableListOf<String>()
        val handle = fx.onFx { ServedFeatures(listOf(feature(events, "a"))).attach(binding) }

        serve(true)
        serve(false)
        serve(true)
        fx.onFx { handle?.close() }

        assertEquals(listOf("a attached", "a closed", "a attached", "a closed"), fx.onFx { events.toList() })
    }

    @Test
    fun `a feature that fails to attach or to close does not stop the others`() {
        bind()
        val events = mutableListOf<String>()
        val features =
            ServedFeatures(
                listOf(
                    feature(events, "a", closeFails = true),
                    feature(events, "b", attachFails = true),
                    feature(events, "c"),
                ),
            )
        fx.onFx { features.attach(binding) }

        serve(true)
        serve(false)

        assertEquals(
            listOf("a attached", "b attached", "c attached", "c closed", "a closed"),
            fx.onFx { events.toList() },
        )
        serve(true)
        assertEquals(listOf("a attached", "b attached", "c attached"), fx.onFx { events.takeLast(3) })
    }

    @Test
    fun `a closed binding attaches nothing`() {
        answer.set(DocumentGate.SERVED)
        bind()
        awaitTrue("served") { fx.onFx { binding.served.value } }
        fx.onFx { binding.close() }
        val events = mutableListOf<String>()

        fx.onFx { ServedFeatures(listOf(feature(events, "a"))).attach(binding) }

        assertEquals(emptyList(), fx.onFx { events.toList() })
    }

    @Test
    fun `a handle that was closed no longer follows the document`() {
        bind()
        val events = mutableListOf<String>()
        val handle = fx.onFx { ServedFeatures(listOf(feature(events, "a"))).attach(binding) }
        fx.onFx { handle?.close() }

        serve(true)

        assertEquals(emptyList(), fx.onFx { events.toList() })
    }
}
