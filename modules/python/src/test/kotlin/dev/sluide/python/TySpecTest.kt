package dev.sluide.python

import dev.sluide.languageservers.LspUris
import dev.sluide.languageservers.ProblemAction
import org.eclipse.lsp4j.ConfigurationItem
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
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

    @ParameterizedTest
    @CsvSource(
        "start, true",
        "_private, true",
        "__init__, true",
        "größe, true",
        "名前, true",
        "x1, true",
        "match, true",
        "case, true",
        "type, true",
        "_, true",
        "'foo bar', false",
        "1abc, false",
        "class, false",
        "None, false",
        "async, false",
        "a-b, false",
        "'', false",
    )
    fun `rename takes Python identifiers that are no hard keyword`(
        name: String,
        expected: Boolean,
    ) {
        assertEquals(expected, spec.isIdentifier(name))
    }

    private fun venv(
        folder: Path,
        name: String = ".venv",
        config: String = "version_info = 3.12.12\n",
    ): Path {
        val venv = folder.resolve(name).createDirectories()
        venv.resolve("pyvenv.cfg").writeText(config)
        return venv
    }

    private fun scope(
        folder: Path,
        section: String? = "ty",
    ) = ConfigurationItem().apply {
        this.section = section
        scopeUri = LspUris.of(folder)
    }

    @Test
    fun `a venv not named dot venv is passed to ty as its interpreter`() {
        val venv = venv(dir, "venv")
        assertEquals(
            mapOf("configuration" to mapOf("environment" to mapOf("python" to venv.toString()))),
            spec.configuration(scope(dir)),
        )
        assertEquals(spec.configuration(scope(dir)), spec.configuration(scope(dir, null)))
    }

    @Test
    fun `dot venv is passed too, so an inherited VIRTUAL_ENV does not win`() {
        val venv = venv(dir)
        assertEquals(
            mapOf("configuration" to mapOf("environment" to mapOf("python" to venv.toString()))),
            spec.configuration(scope(dir)),
        )
    }

    @Test
    fun `other sections, folders without venv and other URIs configure nothing`() {
        venv(dir, "venv")
        val empty = emptyMap<String, Any?>()

        assertEquals(empty, spec.configuration(scope(dir, "python")))
        assertEquals(empty, spec.configuration(scope(dir.resolve("none").createDirectories())))
        assertEquals(empty, spec.configuration(ConfigurationItem().apply { section = "ty" }))
        assertEquals(empty, spec.configuration(ConfigurationItem().apply { scopeUri = "http://x/y" }))
    }

    @ParameterizedTest
    @CsvSource(
        "uv.lock, true",
        "pyproject.toml, true",
        "pyvenv.cfg, true",
        ".venv, true",
        "venv, true",
        "env, true",
        "app.py, false",
        "notes.txt, false",
        "requirements.txt, false",
        ".gitignore, false",
        ".gitattributes, false",
        "__pycache__, false",
        ".pytest_cache, false",
        ".git, false",
        "node_modules, false",
        ".idea, false",
        "my.venv, false",
    )
    fun `changes that may move a project root or venv are recognized by name`(
        name: String,
        expected: Boolean,
    ) {
        assertEquals(expected, spec.affectsWorkspaceFolders(dir.resolve("p").resolve(name)))
    }

    @Test
    fun `the workspace folder is the project root and the watched folders its site-packages`() {
        val venv = venv(dir.resolve("api").createDirectories())
        val sitePackages = venv.resolve("lib/python3.12/site-packages").createDirectories()
        val file = dir.resolve("api/src/a.py")

        assertEquals(dir.resolve("api"), spec.workspaceFolder(file, dir))
        assertEquals(listOf(sitePackages), spec.watchedFolders(dir.resolve("api")))
        assertEquals(emptyList(), spec.watchedFolders(dir))
    }

    @Test
    fun `the status names project, venv and version`() {
        val api = dir.resolve("backends/api").createDirectories()
        val venv = venv(api)

        val status = spec.workspaceStatus(api, dir)!!

        assertEquals("Python project: ${dir.relativize(api)} \u00b7 .venv \u00b7 Python 3.12", status.text)
        assertEquals(api, status.folder)
        assertEquals("Project: $api\nVirtual environment: $venv", status.tooltip)
    }

    @Test
    fun `the status of the window folder uses its own name, one without version leaves it out`() {
        venv(dir, "venv", "home = /x\n")

        assertEquals(
            "Python project: ${dir.fileName} \u00b7 venv",
            spec.workspaceStatus(dir, dir)!!.text,
        )
    }

    @Test
    fun `the status of a project without venv says so`() {
        val status = spec.workspaceStatus(dir, dir)!!

        assertEquals("Python project: ${dir.fileName} \u00b7 no virtual environment", status.text)
        assertEquals("Project: $dir\nVirtual environment: none", status.tooltip)
    }

    @Test
    fun `the status of a native file names the default Python`() {
        val status = spec.workspaceStatus(null, dir)!!

        assertEquals("No Python project \u00b7 default Python", status.text)
        assertNull(status.folder)
        assertEquals(
            "No uv.lock or virtual environment above this file in $dir; " +
                "ty uses VIRTUAL_ENV, Conda or python3 on the PATH.",
            status.tooltip,
        )
    }

    @Test
    fun `a project without venv gets a notice offering uv sync`() {
        val app = dir.resolve("app").createDirectories()
        app.resolve("uv.lock").writeText("")

        val problem = spec.environmentProblem(app.resolve("a.py"), dir)!!

        assertEquals(app, problem.folder)
        assertEquals("No virtual environment for app, so its packages cannot be resolved.", problem.message)
        assertEquals(ProblemAction("Run uv sync", "uv sync (app)", "uv sync"), problem.action)
    }

    @Test
    fun `a project with a venv has no environment problem`() {
        venv(dir)
        assertNull(spec.environmentProblem(dir.resolve("a.py"), dir))
    }
}
