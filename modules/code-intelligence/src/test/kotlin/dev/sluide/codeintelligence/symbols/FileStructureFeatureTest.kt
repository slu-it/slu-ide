package dev.sluide.codeintelligence.symbols

import dev.sluide.codeintelligence.CodeIntelligence
import dev.sluide.codeintelligence.FILE_STRUCTURE_KEYS
import dev.sluide.codeintelligence.FakeCodeEditor
import dev.sluide.codeintelligence.FxServers
import dev.sluide.codeintelligence.PySpec
import dev.sluide.codeintelligence.binding.BindingFeature
import javafx.collections.FXCollections
import javafx.scene.input.KeyCombination
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The file structure as a feature of a bound editor: what it registers (Shortcut+F12, the Navigate menu entry), that
 * both show the popup, and that closing the editor takes both away and closes the popup. The popup is a fake view.
 */
class FileStructureFeatureTest {
    @TempDir
    lateinit var folder: Path

    private val spec = PySpec()
    private lateinit var fx: FxServers
    private lateinit var server: SymbolServer
    private lateinit var intelligence: CodeIntelligence
    private lateinit var editor: FakeCodeEditor
    private val view = FakeSearchView<ShownSymbol>()

    @AfterEach
    fun close() {
        if (!::fx.isInitialized) return
        fx.onFx { intelligence.close() }
        fx.close()
    }

    private fun setUp(
        newServer: () -> SymbolServer = { SymbolServer() },
        feature: () -> FileStructureFeature = { FileStructureFeature(intelligence.navigation) { view } },
    ) {
        fx = FxServers(folder, spec) { newServer() }
        server = fx.fake.awaitRunning(spec) as SymbolServer
        intelligence =
            fx.onFx {
                CodeIntelligence(fx.servers, FXCollections.observableSet()) {
                    // The navigation exists only once the window does: the feature is made when an editor binds.
                    listOf(BindingFeature { binding -> feature().attach(binding) })
                }.apply {
                    navigation.isFocused = { true }
                }
            }
        val file = folder.resolve("a.py")
        file.writeText("def main():\n    pass\n")
        editor = FakeCodeEditor(file, "def main():\n    pass\n")
        fx.onFx { intelligence.extend(editor) }
    }

    private fun binding() = intelligence.attached.single().binding

    @Test
    fun `the editor gets Shortcut+F12 and the Navigate entry, both show the structure popup`() {
        setUp()
        assertEquals(listOf<KeyCombination>(FILE_STRUCTURE_KEYS), editor.keyBindings)
        assertTrue(fx.onFx { editor.keyActions.getValue(FILE_STRUCTURE_KEYS)() }, "the key is taken")
        assertTrue(fx.onFx { view.isShowing })

        fx.onFx { view.hide() }
        val show = fx.onFx { intelligence.navigation.structureOf(binding()!!) }
        assertNotNull(show)
        fx.onFx { show() }
        assertTrue(fx.onFx { view.isShowing })
    }

    @Test
    fun `closing the editor takes the key and the menu entry away and closes the popup`() {
        setUp()
        val bound = binding()!!
        fx.onFx { editor.keyActions.getValue(FILE_STRUCTURE_KEYS)() }
        assertTrue(fx.onFx { view.isShowing })

        fx.onFx { intelligence.attached.single().close() }

        assertEquals(emptyList<KeyCombination>(), editor.keyBindings)
        assertNull(fx.onFx { intelligence.navigation.structureOf(bound) })
        assertFalse(fx.onFx { view.isShowing })
    }

    @Test
    fun `the popup has no place in an editor without a text area`() {
        assertNull(editorAnchor(FakeCodeEditor(Path.of("/a.py"), "")))
    }
}
