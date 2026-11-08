package dev.sluide.codeintelligence.edits

import dev.sluide.codeintelligence.CodeIntelligence
import dev.sluide.codeintelligence.FakeCodeEditor
import dev.sluide.codeintelligence.FxServers
import dev.sluide.codeintelligence.PySpec
import javafx.collections.FXCollections
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The window as the target of workspace edits: a bound editor is found by its path, and by its real path when the
 * server names the file through a link (macOS names temp files `/private/var/…` for `/var/…`).
 */
class BindingEditTargetTest {
    @TempDir
    lateinit var folder: Path

    private lateinit var fx: FxServers

    @AfterEach
    fun close() = fx.close()

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
