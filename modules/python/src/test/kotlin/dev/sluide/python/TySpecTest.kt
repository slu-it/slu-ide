package dev.sluide.python

import org.eclipse.lsp4j.ConfigurationItem
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

class TySpecTest {
    @TempDir
    lateinit var dir: Path

    private val fakes by lazy { TyFakes(dir) }
    private val installer by lazy { fakes.installer() }
    private val spec by lazy { TySpec(installer) }

    @ParameterizedTest
    @CsvSource(
        "app.py, true",
        "types.pyi, true",
        "gui.pyw, true",
        "Main.PY, true",
        "stub.PyI, true",
        "notes.txt, false",
        "py, false",
        "script, false",
        "archive.py.zip, false",
        "app.pyc, false",
    )
    fun `it handles Python sources, stubs and windowed scripts`(
        name: String,
        expected: Boolean,
    ) {
        assertEquals(expected, spec.matches(dir.resolve("src").resolve(name)))
    }

    @Test
    fun `it names the language, needs no trust and offers its installer`() {
        assertEquals("python", spec.id)
        assertEquals("Python", spec.name)
        assertEquals("python", spec.languageId(dir.resolve("app.pyi")))
        assertFalse(spec.runsProjectCode)
        assertNull(spec.trustCheckId)
        assertNull(spec.initializationOptions(dir))
        assertSame(installer, spec.installer)
    }

    @Test
    fun `every configuration section is an empty object`() {
        assertEquals(emptyMap<String, Any?>(), spec.configuration(ConfigurationItem().apply { section = "ty" }))
        assertEquals(emptyMap<String, Any?>(), spec.configuration(ConfigurationItem()))
    }

    @Test
    fun `the command is the installer's`() {
        assertNull(spec.command())

        spec.installer.install(null)
        fakes.runQueued()

        assertEquals(spec.installer.command(), spec.command())
        assertEquals(listOf("server"), spec.command()?.arguments)
        assertEquals(emptyMap(), spec.command()?.environment)
    }
}
