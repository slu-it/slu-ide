package dev.sluide.librarycode

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LibraryCodeTest {
    /** A [file] in [project] (null: none open), with the [files] that exist (pyvenv.cfg, python.exe). */
    class Case(
        private val name: String,
        val file: String,
        val project: String?,
        val expected: LibraryLocation?,
        val description: String? = null,
        val files: Set<String> = emptySet(),
    ) {
        override fun toString() = name
    }

    @ParameterizedTest
    @MethodSource("cases")
    fun `a file is library code by its path`(case: Case) {
        val files = case.files.map(::Path).toSet()

        val location = LibraryCode.locate(Path(case.file), case.project?.let(::Path), isFile = { it in files })

        assertEquals(case.expected, location)
        assertEquals(case.description, location?.description)
    }

    @ParameterizedTest
    @CsvSource(
        "/home/u/.sluide/cache/java-classes/java.base/java/lang/String.java, /p, true",
        "/home/u/.sluide/cache/java-classes, /p, false",
        "/home/u/.sluide/cache/java-classes-other/A.java, /p, false",
        "/home/u/.sluide/cache/java-classes/../x/A.java, /p, false",
        "/home/u/work/A.java, /p, false",
    )
    fun `a file below the Java classes root is a Java library class`(
        file: String,
        project: String,
        expected: Boolean,
    ) {
        val root = Path("/home/u/.sluide/cache/java-classes")

        val location = LibraryCode.locate(Path(file), Path(project), isFile = { false }, javaClassesRoot = root)

        assertEquals(if (expected) LibraryLocation(root, LibraryKind.JAVA_CLASS) else null, location)
        assertEquals(if (expected) "Java library class" else null, location?.description)
    }

    @Test
    fun `the Java classes root wins over a venv or site-packages in its path`() {
        val root = Path("/home/u/.venv/cache/java-classes")

        val location =
            LibraryCode.locate(
                Path("/home/u/.venv/cache/java-classes/site-packages/A.java"),
                Path("/p"),
                isFile = { it == Path("/home/u/.venv/pyvenv.cfg") },
                javaClassesRoot = root,
            )

        assertEquals(LibraryLocation(root, LibraryKind.JAVA_CLASS), location)
    }

    @Test
    fun `the default Java classes root is the cache folder of the user's data`() {
        assertEquals(
            Path(System.getProperty("user.home"), ".sluide", "cache", "java-classes"),
            LibraryCode.JAVA_CLASSES_ROOT,
        )
    }

    @Test
    fun `relative paths are made absolute and normalized`() {
        val cwd = Path("").toAbsolutePath()

        val location =
            LibraryCode.locate(
                Path("x/../site-packages/./requests/api.py"),
                Path("project"),
                isFile = { false },
            )

        assertEquals(LibraryLocation(cwd.resolve("site-packages"), LibraryKind.SITE_PACKAGES), location)
        assertNull(LibraryCode.locate(Path("site-packages/api.py"), Path("x/../site-packages"), isFile = { false }))
    }

    @Test
    fun `a venv folder and __pypackages__ are library roots, their subfolders are not`() {
        val cfg = setOf(Path("/p/.venv/pyvenv.cfg"))
        val isFile = { path: Path -> path in cfg }

        assertEquals(LibraryKind.VIRTUAL_ENV, LibraryCode.libraryRootKind(Path("/p/.venv"), isFile))
        assertNull(LibraryCode.libraryRootKind(Path("/p/.venv/lib"), isFile))
        assertEquals(LibraryKind.PYPACKAGES, LibraryCode.libraryRootKind(Path("/p/__pypackages__"), isFile))
        assertNull(LibraryCode.libraryRootKind(Path("/p/__pypackages__/3.12"), isFile))
        assertNull(LibraryCode.libraryRootKind(Path("/p/site-packages"), isFile))
    }

    companion object {
        private const val PROJECT = "/home/u/work/app"
        private const val VENV = "$PROJECT/.venv"
        private const val HOMEBREW_FRAMEWORK =
            "/opt/homebrew/Cellar/python@3.12/3.12.7/Frameworks/Python.framework/Versions/3.12"

        private fun at(
            root: String,
            kind: LibraryKind,
        ) = LibraryLocation(Path(root), kind)

        @JvmStatic
        fun cases() =
            listOf(
                // Linux
                Case(
                    "Linux standard library",
                    "/usr/lib/python3.12/json/__init__.py",
                    PROJECT,
                    at("/usr/lib/python3.12", LibraryKind.STANDARD_LIBRARY),
                    "Python standard library",
                ),
                Case(
                    "lib64 standard library, free-threaded build",
                    "/usr/lib64/python3.13t/os.py",
                    PROJECT,
                    at("/usr/lib64/python3.13t", LibraryKind.STANDARD_LIBRARY),
                    "Python standard library",
                ),
                Case(
                    "user site-packages, inside the standard library layout",
                    "/home/u/.local/lib/python3.12/site-packages/x.py",
                    PROJECT,
                    at("/home/u/.local/lib/python3.12/site-packages", LibraryKind.SITE_PACKAGES),
                    "site-packages",
                ),
                Case(
                    "Debian dist-packages",
                    "/usr/lib/python3/dist-packages/x.py",
                    PROJECT,
                    at("/usr/lib/python3/dist-packages", LibraryKind.SITE_PACKAGES),
                    "dist-packages",
                ),
                Case(
                    "ty's typeshed stubs in its Linux cache",
                    "/home/u/.cache/ty/vendored/typeshed/0a1b2c/stdlib/builtins.pyi",
                    PROJECT,
                    at("/home/u/.cache/ty/vendored/typeshed", LibraryKind.TYPESHED),
                    "ty's typeshed stubs",
                ),
                Case(
                    "a typeshed folder with stdlib",
                    "/opt/typeshed/stdlib/builtins.pyi",
                    PROJECT,
                    at("/opt/typeshed", LibraryKind.TYPESHED),
                    "typeshed stubs",
                ),
                Case(
                    "a typeshed folder with stubs",
                    "/opt/typeshed/stubs/requests/requests/api.pyi",
                    PROJECT,
                    at("/opt/typeshed", LibraryKind.TYPESHED),
                    "typeshed stubs",
                ),
                // macOS
                Case(
                    "pyenv standard library",
                    "/Users/u/.pyenv/versions/3.12.1/lib/python3.12/json/decoder.py",
                    PROJECT,
                    at("/Users/u/.pyenv/versions/3.12.1/lib/python3.12", LibraryKind.STANDARD_LIBRARY),
                    "Python standard library",
                ),
                Case(
                    "Homebrew framework standard library",
                    "$HOMEBREW_FRAMEWORK/lib/python3.12/json/__init__.py",
                    PROJECT,
                    at("$HOMEBREW_FRAMEWORK/lib/python3.12", LibraryKind.STANDARD_LIBRARY),
                    "Python standard library",
                ),
                Case(
                    "ty's typeshed stubs in its macOS cache",
                    "/Users/u/Library/Caches/ty/vendored/typeshed/0a1b2c/stdlib/builtins.pyi",
                    PROJECT,
                    at("/Users/u/Library/Caches/ty/vendored/typeshed", LibraryKind.TYPESHED),
                    "ty's typeshed stubs",
                ),
                // Windows layout (the rules match names, so Unix-style paths stand in for C:\)
                Case(
                    "Windows standard library beside python.exe",
                    "/c/Python312/Lib/json/__init__.py",
                    PROJECT,
                    at("/c/Python312/Lib", LibraryKind.STANDARD_LIBRARY),
                    "Python standard library",
                    files = setOf("/c/Python312/python.exe"),
                ),
                Case(
                    "Windows site-packages inside Lib",
                    "/c/Python312/Lib/site-packages/requests/api.py",
                    PROJECT,
                    at("/c/Python312/Lib/site-packages", LibraryKind.SITE_PACKAGES),
                    "site-packages",
                    files = setOf("/c/Python312/python.exe"),
                ),
                Case(
                    "a Lib folder without python.exe beside it",
                    "/c/tools/Lib/json/__init__.py",
                    PROJECT,
                    null,
                ),
                // Project venvs
                Case(
                    "the project's venv wins over its site-packages, pyvenv.cfg is in an ancestor only",
                    "$VENV/lib/python3.12/site-packages/requests/api.py",
                    PROJECT,
                    at(VENV, LibraryKind.VIRTUAL_ENV),
                    "virtual environment .venv",
                    files = setOf("$VENV/pyvenv.cfg"),
                ),
                Case(
                    "a script in a venv's bin folder",
                    "/home/u/envs/tool/bin/tool.py",
                    PROJECT,
                    at("/home/u/envs/tool", LibraryKind.VIRTUAL_ENV),
                    "virtual environment tool",
                    files = setOf("/home/u/envs/tool/pyvenv.cfg"),
                ),
                Case(
                    "the same script without pyvenv.cfg",
                    "/home/u/envs/tool/bin/tool.py",
                    PROJECT,
                    null,
                ),
                Case(
                    "a tox environment",
                    "$PROJECT/.tox/py312/lib/python3.12/site-packages/pytest/__init__.py",
                    PROJECT,
                    at("$PROJECT/.tox/py312", LibraryKind.VIRTUAL_ENV),
                    "virtual environment py312",
                    files = setOf("$PROJECT/.tox/py312/pyvenv.cfg"),
                ),
                Case(
                    "PEP 582 __pypackages__",
                    "$PROJECT/__pypackages__/3.12/lib/requests/api.py",
                    PROJECT,
                    at("$PROJECT/__pypackages__", LibraryKind.PYPACKAGES),
                    "__pypackages__",
                ),
                Case(
                    "without a project folder",
                    "/home/u/.local/lib/python3.12/site-packages/mypkg/x.py",
                    null,
                    at("/home/u/.local/lib/python3.12/site-packages", LibraryKind.SITE_PACKAGES),
                    "site-packages",
                ),
                // Not library code
                Case(
                    "an ordinary project file",
                    "$PROJECT/src/app/main.py",
                    PROJECT,
                    null,
                    files = setOf("$VENV/pyvenv.cfg"),
                ),
                Case(
                    "an editable install's source in the project, beside the venv",
                    "$PROJECT/src/mypkg/__init__.py",
                    PROJECT,
                    null,
                    files = setOf("$VENV/pyvenv.cfg", "$VENV/lib/python3.12/site-packages/__editable__.mypkg.pth"),
                ),
                Case(
                    "a project inside site-packages",
                    "/home/u/.local/lib/python3.12/site-packages/mypkg/x.py",
                    "/home/u/.local/lib/python3.12/site-packages/mypkg",
                    null,
                ),
                Case(
                    "a project folder named lib/python3.12",
                    "/home/u/lib/python3.12/json/__init__.py",
                    "/home/u/lib/python3.12",
                    null,
                ),
                Case("a project folder named lib", "/home/u/lib/src/x.py", "/home/u/lib", null),
                Case("a python3.12 folder outside lib", "/home/u/tools/python3.12/json/__init__.py", PROJECT, null),
                Case("a typeshed folder without stubs", "/home/u/notes/typeshed/ideas/x.pyi", PROJECT, null),
                Case(
                    "a venv opened as the project, also its site-packages",
                    "$VENV/lib/python3.12/site-packages/requests/api.py",
                    VENV,
                    null,
                    files = setOf("$VENV/pyvenv.cfg"),
                ),
                Case(
                    "a project inside a venv, also the venv's site-packages",
                    "/home/u/envs/old/lib/python3.12/site-packages/requests/api.py",
                    "/home/u/envs/old/src/app",
                    null,
                    files = setOf("/home/u/envs/old/pyvenv.cfg"),
                ),
                Case(
                    "a project inside a venv, its own venv is library code: the nearest venv is the root",
                    "/home/u/envs/old/src/app/.venv/lib/python3.12/site-packages/x.py",
                    "/home/u/envs/old/src/app",
                    at("/home/u/envs/old/src/app/.venv", LibraryKind.VIRTUAL_ENV),
                    "virtual environment .venv",
                    files = setOf("/home/u/envs/old/pyvenv.cfg", "/home/u/envs/old/src/app/.venv/pyvenv.cfg"),
                ),
                Case(
                    "the standard library opened as the project, also its site-packages",
                    "/usr/lib/python3.12/site-packages/x.py",
                    "/usr/lib/python3.12",
                    null,
                ),
            )
    }
}
