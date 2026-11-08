package dev.sluide.codeintelligence.rename

import dev.sluide.codeintelligence.CodeIntelligence
import dev.sluide.codeintelligence.FakeCodeEditor
import dev.sluide.codeintelligence.FxServers
import dev.sluide.codeintelligence.PySpec
import dev.sluide.codeintelligence.binding.BindingFeature
import dev.sluide.codeintelligence.binding.DocumentBinding
import dev.sluide.fileviewers.FileViewHost
import javafx.beans.property.SimpleBooleanProperty
import javafx.collections.FXCollections
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How the rename preview finds the files of a window: bound editors by their unsaved state, other open editors by
 * their text against the disk, closed files inside or outside the project.
 */
class BindingRenameFileStatesTest {
    @TempDir
    lateinit var folder: Path

    @TempDir
    lateinit var elsewhere: Path

    private val spec = PySpec()
    private lateinit var fx: FxServers
    private lateinit var intelligence: CodeIntelligence
    private val bindings = mutableMapOf<Path, DocumentBinding>()

    /** The texts of the editors without code intelligence, by file. */
    private val openTexts = mutableMapOf<Path, String>()

    private val host =
        object : FileViewHost {
            override fun openFile(file: Path) = false

            override fun openWebPage(uri: URI) {}

            override val projectFolder: Path get() = folder

            override fun currentText(file: Path): String? = openTexts[file]
        }

    @AfterEach
    fun close() {
        fx.onFx { intelligence.close() }
        fx.close()
    }

    /** A bound editor of [name], with unsaved changes if [dirty]. */
    private fun bound(
        name: String,
        dirty: Boolean,
    ): Path {
        val file = folder.resolve(name).also { it.writeText("x = 1\n") }
        val editor =
            object : FakeCodeEditor(file, "x = 1\n", host) {
                override val dirty = SimpleBooleanProperty(dirty)
            }
        fx.onFx { intelligence.extend(editor) }
        return file
    }

    @Test
    fun `bound editors by unsaved state, open ones against the disk, closed ones inside or outside the project`() {
        fx = FxServers(folder, spec)
        fx.fake.awaitRunning(spec)
        val capture =
            BindingFeature { binding ->
                bindings[binding.file] = binding
                null
            }
        intelligence = fx.onFx { CodeIntelligence(fx.servers, FXCollections.observableSet()) { listOf(capture) } }
        val clean = bound("a.py", dirty = false)
        val dirty = bound("b.py", dirty = true)
        val saved = folder.resolve("notes.txt").also { it.writeText("run\n") }
        openTexts[saved] = "run\n"
        val unsaved = folder.resolve("other.txt").also { it.writeText("run\n") }
        openTexts[unsaved] = "run()\n"
        val closed = folder.resolve("c.py").also { it.writeText("run\n") }
        val outside = elsewhere.resolve("lib.py").also { it.writeText("run\n") }
        val missing = folder.resolve("gone.py")
        val files = listOf(clean, dirty, saved, unsaved, closed, outside, missing)

        val found = CompletableFuture<Map<Path, RenameFileState>>()
        fx.onFx {
            BindingRenameFileStates(checkNotNull(bindings[clean]), io = Runnable::run, fx = Runnable::run)
                .of(files) { found.complete(it) }
        }
        assertEquals(
            mapOf(
                clean to RenameFileState.OPEN,
                dirty to RenameFileState.UNSAVED,
                saved to RenameFileState.OPEN,
                unsaved to RenameFileState.UNSAVED_UNKNOWN,
                closed to RenameFileState.CLOSED,
                outside to RenameFileState.OUTSIDE,
                missing to RenameFileState.CLOSED,
            ),
            found.get(5, TimeUnit.SECONDS),
        )
    }
}
