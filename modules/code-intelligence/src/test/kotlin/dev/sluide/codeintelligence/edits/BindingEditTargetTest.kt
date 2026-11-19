package dev.sluide.codeintelligence.edits

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.sluide.codeintelligence.BoundEditorHarness
import dev.sluide.codeintelligence.CodeIntelligence
import dev.sluide.codeintelligence.FakeCodeEditor
import dev.sluide.codeintelligence.FxServers
import dev.sluide.codeintelligence.PySpec
import dev.sluide.codeintelligence.awaitTrue
import dev.sluide.codeintelligence.diagnostics.LspTestServer
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.fileviewers.OffsetEdit
import dev.sluide.languageservers.LspUris
import javafx.collections.FXCollections
import org.eclipse.lsp4j.FileChangeType
import org.eclipse.lsp4j.Registration
import org.eclipse.lsp4j.RegistrationParams
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The window as the target of workspace edits: a bound editor is found by its path, and by its real path when the
 * server names the file through a link (macOS names temp files `/private/var/…` for `/var/…`).
 */
class BindingEditTargetTest {
    @TempDir
    lateinit var folder: Path

    private lateinit var fx: FxServers

    private var harness: BoundEditorHarness<LspTestServer>? = null

    @AfterEach
    fun close() {
        if (::fx.isInitialized) fx.close()
        harness?.close()
    }

    /** What the window's host says about the files of its other open editors, and what it was asked to edit. */
    private class RecordingHost(
        override val projectFolder: Path?,
        val open: MutableMap<Path, String> = mutableMapOf(),
    ) : FileViewHost {
        val editsRefused = mutableSetOf<Path>()

        override fun openFile(file: Path) = false

        override fun openWebPage(uri: URI) {}

        override fun currentText(file: Path): String? = open[file]

        override fun editText(
            file: Path,
            edit: (String) -> String,
        ): Boolean {
            if (file in editsRefused) return false
            open[file] = edit(open[file] ?: return false)
            return true
        }
    }

    private fun bind(
        host: FileViewHost,
        text: String = "x = 1\n",
    ): BindingEditTarget {
        val bound = BoundEditorHarness(folder, text, { LspTestServer() }, host = host)
        harness = bound
        return BindingEditTarget(bound.binding)
    }

    @Test
    fun `the window's project folder, the text of other open editors and their editing come from the host`() {
        val other = folder.resolve("other.py")
        val host = RecordingHost(folder, mutableMapOf(other to "a\n"))
        val target = bind(host)
        assertEquals(folder, target.projectFolder)
        assertEquals("a\n", target.openText(other))
        assertNull(target.openText(folder.resolve("closed.py")))

        assertTrue(target.editOpenText(other) { it + "b\n" })
        assertEquals("a\nb\n", host.open[other])
        host.editsRefused.add(other)
        assertFalse(target.editOpenText(other) { "never" }, "the host's refusal is the answer")
        assertFalse(target.editOpenText(folder.resolve("closed.py")) { "never" })
    }

    @Test
    fun `a bound text tells its document's version and text, and whether the editor takes edits`() {
        val target = bind(RecordingHost(folder))
        val bound = checkNotNull(harness)
        val file = bound.editor.fileProperty.get()
        val text = checkNotNull(bound.onFx { target.bound(file) })
        val first = bound.onFx { text.version }
        assertEquals("x = 1\n", bound.onFx { text.text })
        assertTrue(bound.onFx { text.canApply })
        assertTrue(bound.onFx { text.apply(listOf(OffsetEdit(0, 1, "y"))) })
        assertEquals("y = 1\n", bound.onFx { text.text })
        val second = bound.onFx { text.version }
        assertTrue(second > first, "the version moved with the edit: $first -> $second")
        assertEquals(bound.onFx { bound.binding.document.version }, second)

        bound.editor.editable = false
        assertFalse(bound.onFx { text.canApply })
        assertFalse(bound.onFx { text.apply(listOf(OffsetEdit(0, 1, "z"))) }, "the editor's refusal is the answer")
    }

    @Test
    fun `a binding that closed is no target any more`() {
        val target = bind(RecordingHost(folder))
        val bound = checkNotNull(harness)
        val file = bound.editor.fileProperty.get()
        assertNotNull(bound.onFx { target.bound(file) })
        bound.onFx { bound.binding.close() }
        assertNull(bound.onFx { target.bound(file) })
    }

    @Test
    fun `files that changed on disk reach the servers that watch them`() {
        val target = bind(RecordingHost(folder))
        val bound = checkNotNull(harness)
        val watcher = Registration("w", "workspace/didChangeWatchedFiles", watchOptions("**/*.py"))
        bound.server.client
            .registerCapability(RegistrationParams(listOf(watcher)))
            .get()
        val changed = folder.resolve("b.py")
        awaitTrue("watcher known") {
            bound.onFx {
                bound.binding.session.features
                    .fileWatchers()
                    .isNotEmpty()
            }
        }
        bound.onFx { target.filesChanged(listOf(changed)) }
        awaitTrue("the server was told") { bound.server.watchedFileChanges.isNotEmpty() }
        val event =
            bound.server.watchedFileChanges
                .single()
                .changes
                .single()
        assertEquals(LspUris.of(changed), event.uri)
        assertEquals(FileChangeType.Changed, event.type)
    }

    private fun watchOptions(glob: String) =
        JsonObject().apply {
            add("watchers", JsonArray().apply { add(JsonObject().apply { addProperty("globPattern", glob) }) })
        }

    @Test
    fun `a bound file is found by its path and through a linked folder, another file not`() {
        val spec = PySpec()
        val real = folder.resolve("real").createDirectories()
        val link = Files.createSymbolicLink(folder.resolve("link"), real)
        val file = real.resolve("a.py").also { it.writeText("x\n") }
        real.resolve("b.py").writeText("y\n")
        fx = FxServers(folder, spec)
        fx.fake.awaitRunning(spec)
        val intelligence = fx.onFx { CodeIntelligence(fx.servers, FXCollections.observableSet()) { emptyList() } }
        fx.onFx { intelligence.extend(FakeCodeEditor(file, "x\n")) }

        val binding = checkNotNull(fx.onFx { intelligence.bindingFor(file) })
        assertSame(binding, fx.onFx { intelligence.bindingFor(link.resolve("a.py")) })
        assertNull(fx.onFx { intelligence.bindingFor(link.resolve("b.py")) })
        assertEquals("x\n", fx.onFx { BindingEditTarget(binding).bound(link.resolve("a.py"))?.text })
    }
}
