package dev.sluide.python

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PythonProjectsTest {
    @TempDir
    lateinit var dir: Path

    private fun venv(
        folder: Path,
        name: String = ".venv",
        config: String = "home = /usr/bin\n",
    ): Path {
        val venv = folder.resolve(name).createDirectories()
        venv.resolve("pyvenv.cfg").writeText(config)
        return venv
    }

    private fun file(path: String): Path {
        val file = dir.resolve(path)
        file.parent.createDirectories()
        file.writeText("")
        return file
    }

    @Test
    fun `each backend of a monorepo with a venv is its own project`() {
        venv(dir.resolve("backends/a"))
        venv(dir.resolve("backends/b"))
        val a = file("backends/a/src/app/main.py")
        val b = file("backends/b/main.py")

        assertEquals(dir.resolve("backends/a"), projectRoot(a, dir))
        assertEquals(dir.resolve("backends/b"), projectRoot(b, dir))
    }

    @Test
    fun `a member of a uv workspace belongs to the workspace root`() {
        dir.resolve("uv.lock").writeText("")
        venv(dir)
        dir
            .resolve("packages/lib")
            .createDirectories()
            .resolve("pyproject.toml")
            .writeText("")
        val member = file("packages/lib/lib/core.py")

        assertEquals(dir, projectRoot(member, dir))
    }

    @Test
    fun `a venv named venv makes a project and the nearest root wins`() {
        venv(dir)
        venv(dir.resolve("sub"), "venv")
        val nested = file("sub/deep/x.py")

        assertEquals(dir.resolve("sub"), projectRoot(nested, dir))
    }

    @Test
    fun `a venv needs a pyvenv cfg`() {
        dir.resolve(".venv").createDirectories()
        assertNull(venvOf(dir))
        assertNull(projectRoot(file("a.py"), dir))
    }

    @Test
    fun `the dot venv wins over other venvs, which go alphabetically`() {
        venv(dir, "venv")
        venv(dir, "env")
        assertEquals(dir.resolve("env"), venvOf(dir))
        venv(dir, ".venv")
        assertEquals(dir.resolve(".venv"), venvOf(dir))
    }

    @Test
    fun `the window folder itself can be the project`() {
        venv(dir)
        assertEquals(dir, projectRoot(file("main.py"), dir))
    }

    @Test
    fun `a file without project is native`() {
        assertNull(projectRoot(file("src/main.py"), dir))
    }

    @Test
    fun `the walk stops at the window folder`() {
        dir.resolve("uv.lock").writeText("")
        val window = dir.resolve("window").createDirectories()
        val inside = window.resolve("a.py").also { it.writeText("") }

        assertNull(projectRoot(inside, window))
    }

    @Test
    fun `a file outside the window folder has no project`() {
        val window = dir.resolve("window").createDirectories()
        venv(dir)

        assertNull(projectRoot(file("other/a.py"), window))
        assertNull(missingVenvFolder(file("other/b.py"), window))
    }

    @Test
    fun `the version comes from uv's version_info, else from version`() {
        val uv = venv(dir, "uv", "home = /x\nversion_info = 3.12.12\n")
        val module = venv(dir, "module", "home = /x\nversion = 3.14.4\n")
        val none = venv(dir, "none", "home = /x\n")

        assertEquals("3.12", pythonVersion(uv))
        assertEquals("3.14", pythonVersion(module))
        assertNull(pythonVersion(none))
        assertNull(pythonVersion(dir.resolve("missing")))
    }

    @Test
    fun `site-packages are found under lib, lib64 and Lib, sorted`() {
        val venv = venv(dir)
        val expected =
            listOf("lib/python3.12", "lib64/python3.11", "lib/python3.10", "Lib")
                .map { venv.resolve(it).resolve("site-packages").also { path -> path.createDirectories() } }
        venv.resolve("lib/python3.9").createDirectories()
        venv.resolve("lib/notpython/site-packages").createDirectories()

        assertEquals(expected.sorted(), sitePackages(venv))
        assertEquals(emptyList(), sitePackages(dir.resolve("missing")))
    }

    @Test
    fun `a root without venv lacks one`() {
        dir
            .resolve("app")
            .createDirectories()
            .resolve("uv.lock")
            .writeText("")
        assertEquals(dir.resolve("app"), missingVenvFolder(file("app/src/a.py"), dir))
    }

    @Test
    fun `a root with a venv lacks nothing`() {
        venv(dir)
        assertNull(missingVenvFolder(file("a.py"), dir))
    }

    @Test
    fun `a native file under a pyproject lacks the venv of that folder`() {
        dir
            .resolve("tool")
            .createDirectories()
            .resolve("pyproject.toml")
            .writeText("")
        assertEquals(dir.resolve("tool"), missingVenvFolder(file("tool/pkg/a.py"), dir))
    }

    @Test
    fun `a native file without any project file lacks nothing`() {
        assertNull(missingVenvFolder(file("loose/a.py"), dir))
    }

    @Test
    fun `a path that is no folder has no venv and no site-packages`() {
        val notADirectory = dir.resolve("plain").also { Files.writeString(it, "x") }
        assertNull(venvOf(notADirectory))
        assertEquals(emptyList(), sitePackages(notADirectory))
    }
}
