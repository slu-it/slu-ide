package dev.sluide.codeintelligence.diagnostics

import dev.sluide.codeintelligence.BoundEditorHarness
import dev.sluide.codeintelligence.ManualDelays
import dev.sluide.codeintelligence.awaitTrue
import dev.sluide.codeintelligence.quickfix.QUICK_FIX_KEYS
import dev.sluide.languageservers.LspUris
import javafx.scene.Node
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * What the diagnostics feature adds to a bound editor and takes away again: stylesheet, one gutter column for the
 * editor's life that follows the diagnostics, the quick fix key and menu item, the hover section source. What is
 * shown and asked is `DocumentDiagnosticsTest`'s, `DiagnosticsGutterTest`'s and `QuickFixesTest`'s.
 */
class DiagnosticsFeatureTest {
    @TempDir
    lateinit var folder: Path

    private lateinit var harness: BoundEditorHarness<LspTestServer>
    private val delays = ManualDelays()
    private val feature = DiagnosticsFeature({ false }, 300.milliseconds, delays::schedule)

    @AfterEach
    fun close() = harness.close()

    private fun bind(text: String) {
        harness = BoundEditorHarness(folder, text, { LspTestServer() }, { binding -> listOf(feature.attach(binding)) })
    }

    private val editor get() = harness.editor

    private fun detach() = harness.onFx { harness.handles.removeAt(0).close() }

    private fun attach() = harness.onFx { harness.handles += feature.attach(harness.binding) }

    private fun error(
        line: Int,
        start: Int,
        end: Int,
    ) = Diagnostic(Range(Position(line, start), Position(line, end)), "m").apply {
        severity = DiagnosticSeverity.Error
    }

    private fun push(vararg diagnostics: Diagnostic) {
        val uri = LspUris.of(harness.onFx { harness.binding.file })
        harness.server.client.publishDiagnostics(PublishDiagnosticsParams(uri, diagnostics.toList()))
    }

    @Test
    fun `attaching adds the stylesheet, the quick fix key and menu item and the hover, closing removes them`() {
        bind("x\n")

        assertEquals(listOf(DIAGNOSTICS_CSS), harness.onFx { editor.stylesheets.toList() })
        assertEquals(listOf(QUICK_FIX_KEYS), harness.onFx { editor.keyBindings.toList() })
        assertTrue(
            harness.onFx {
                editor
                    .menuItems()
                    .single()
                    .text
                    .startsWith("Show Quick Fixes")
            },
        )
        assertEquals(1, harness.onFx { harness.binding.hovers.providers.size })

        detach()

        assertEquals(emptyList(), harness.onFx { editor.stylesheets.toList() })
        assertEquals(emptyList(), harness.onFx { editor.keyBindings.toList() })
        assertEquals(emptyList(), harness.onFx { editor.menuItems() })
        assertEquals(0, harness.onFx { harness.binding.hovers.providers.size })
    }

    @Test
    fun `the gutter has one column for the editor's life, attached again it is the same one`() {
        bind("x\n")
        assertEquals(listOf(12.0), harness.onFx { editor.gutterColumns.map { it.first } })

        detach()
        attach()

        assertEquals(1, harness.onFx { editor.gutterColumns.size })
    }

    @Test
    fun `the gutter marks a line when a diagnostic arrives and drops the mark when the feature closes`() {
        bind("a\nb\nc\n")
        harness.onFx { editor.shownLines = 1..1 }
        assertEquals(0, harness.onFx { editor.gutterRefreshes })

        push(error(1, 0, 1))
        awaitTrue("the shown line 1 is marked") { harness.onFx { editor.gutterRefreshes } == 1 }

        detach()
        assertEquals(2, harness.onFx { editor.gutterRefreshes }, "unbound: the mark goes")
    }

    @Test
    fun `the hover source answers at once, with nothing where no diagnostic is`() {
        // A section is a Label, which needs a toolkit: the section over a diagnostic is DiagnosticsUiTest's.
        bind("ab cd\n")
        push(error(0, 3, 5))
        awaitTrue("shown") { harness.onFx { editor.layers[DIAGNOSTICS_LAYER] }?.size == 1 }
        val provider =
            harness.onFx {
                harness.binding.hovers.providers
                    .single()
            }
        val answers = mutableListOf<List<Node>?>()

        harness.onFx { provider.hover(0) { answers += it } }

        assertEquals(listOf(emptyList<Node>()), harness.onFx { answers.toList() })
    }
}
