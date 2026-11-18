package dev.sluide.fileexplorer.generated

import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.stream.Stream
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeneratedFoldersTest {
    @TempDir
    lateinit var root: Path

    private fun reason(folder: Path): String? =
        Files
            .list(folder.parent)
            .use { entries -> entries.map { it.name }.toList().toSet() }
            .let { generatedReason(folder, it) }
            ?.text

    private fun folder(
        name: String,
        vararg siblings: String,
    ): Path {
        siblings.forEach { root.resolve(it).createFile() }
        return root.resolve(name).createDirectories()
    }

    @ParameterizedTest(name = "{0} next to {1}")
    @MethodSource("catalogueRows")
    fun `catalogue row marks the folder`(
        folderName: String,
        projectFile: String,
        parentName: String?,
        expected: String,
    ) {
        val folder =
            if (parentName == null) {
                folder(folderName, *listOfNotNull(projectFile.ifEmpty { null }).toTypedArray())
            } else {
                root.resolve(projectFile).createFile()
                root.resolve(parentName).resolve(folderName).createDirectories()
            }
        assertEquals(expected, reason(folder))
    }

    @Test
    fun `cachedir tag with the signature marks any folder`() {
        val folder = folder("anything")
        folder.resolve("CACHEDIR.TAG").writeText("Signature: 8a477f597d28d172789f06886806bc55\n# comment")
        assertEquals("marked by CACHEDIR.TAG", reason(folder))
    }

    @Test
    fun `cachedir tag with a wrong signature is no marker`() {
        val folder = folder("anything")
        folder.resolve("CACHEDIR.TAG").writeText("Signature: 0000")
        assertNull(reason(folder))
    }

    @Test
    fun `gitignore with only a star marks the folder, comments and blanks aside`() {
        val folder = folder("anything")
        folder.resolve(".gitignore").writeText("# created by a tool\n\n  *  \n")
        assertEquals("marked by its .gitignore", reason(folder))
    }

    @Test
    fun `gitignore with a BOM or CRLF line ends still marks the folder`() {
        val bomFolder = folder("bom")
        bomFolder.resolve(".gitignore").writeText("\uFEFF*\n")
        assertEquals("marked by its .gitignore", reason(bomFolder))
        val crlfFolder = folder("crlf")
        crlfFolder.resolve(".gitignore").writeText("# tool\r\n*\r\n")
        assertEquals("marked by its .gitignore", reason(crlfFolder))
    }

    @Test
    fun `gitignore with a star and another line is no marker`() {
        val folder = folder("anything")
        folder.resolve(".gitignore").writeText("*\n!keep\n")
        assertNull(reason(folder))
    }

    @Test
    fun `gitignore bigger than the read limit is no marker`() {
        val folder = folder("anything")
        folder.resolve(".gitignore").writeText("*\n" + "# padding\n".repeat(600))
        assertNull(reason(folder))
    }

    @Test
    fun `pyvenv cfg marks a virtual environment of any name`() {
        val folder = folder("env")
        folder.resolve("pyvenv.cfg").createFile()
        assertEquals("virtual environment (pyvenv.cfg)", reason(folder))
    }

    @Test
    fun `cmake cache marks a build folder`() {
        val folder = folder("out")
        folder.resolve("CMakeCache.txt").createFile()
        assertEquals("CMake build folder", reason(folder))
    }

    @Test
    fun `meson private folder marks a build folder`() {
        val folder = folder("out")
        folder.resolve("meson-private").createDirectories()
        assertEquals("Meson build folder", reason(folder))
    }

    @Test
    fun `build without a project file is not generated`() {
        assertNull(reason(folder("build", "README.md")))
    }

    @Test
    fun `go vendor is not generated`() {
        assertNull(reason(folder("vendor", "go.mod")))
    }

    @Test
    fun `tmp and out are never generated`() {
        assertNull(reason(folder("tmp", "package.json", "pom.xml")))
        assertNull(reason(folder("out")))
    }

    @Test
    fun `two-segment rule needs the project file in the grandparent`() {
        val bundle = root.resolve("vendor/bundle").createDirectories()
        assertNull(reason(bundle))
        root.resolve("Gemfile").createFile()
        assertEquals("vendor/bundle for Gemfile", reason(bundle))
    }

    @Test
    fun `two-segment rule needs the right parent name`() {
        root.resolve("Gemfile").createFile()
        assertNull(reason(root.resolve("other/bundle").createDirectories()))
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = [".ds_store", "x.DS_Store", ".DS_Store.bak", "DS_Store"])
    fun `only the exact name DS_Store is a generated file`(name: String) {
        assertNull(generatedFileReason(root.resolve(name)))
    }

    @Test
    fun `the filter classifies a DS_Store file but not a folder of that name`() {
        val filter = GeneratedFolderFilter { true }
        val file = root.resolve(".DS_Store").createFile()
        val folder = root.resolve("sub/.DS_Store").also { it.createDirectories() }

        assertEquals(GeneratedReason("macOS folder metadata", file = true), filter.structural(file, setOf(".DS_Store")))
        assertNull(filter.structural(folder, setOf(".DS_Store")))
        assertNull(filter.structural(root.resolve("missing"), emptySet()))
    }

    @ParameterizedTest(name = "file={0} tracked={1} ignored={2}")
    @CsvSource("true,true,", "true,false,", "true,,", "false,,true", "false,,false", "false,,")
    fun `git decides a file by tracked and a folder by ignored`(
        file: Boolean,
        tracked: Boolean?,
        ignored: Boolean?,
    ) {
        val filter = GeneratedFolderFilter { true }
        filter.trackedByGit = { tracked }
        filter.ignoredByGit = { ignored }
        val reason = GeneratedReason("x", file = file)

        val expectedGenerated = if (file) tracked != true else ignored != false
        assertEquals(if (expectedGenerated) reason else null, filter.effective(root, reason))
    }

    @Test
    fun `glob needs a non-empty stem`() {
        root.resolve(".sbt").createDirectories()
        assertNull(reason(folder("target")))
    }

    @Test
    fun `pyvenv cfg and cmake cache as folders and meson private as a file are no markers`() {
        val folder = folder("env")
        folder.resolve("pyvenv.cfg").createDirectories()
        folder.resolve("CMakeCache.txt").createDirectories()
        folder.resolve("meson-private").createFile()
        assertNull(reason(folder))
    }

    @Test
    fun `catalogue reason wins over a marker inside the folder`() {
        val folder = folder("__pycache__")
        folder.resolve("pyvenv.cfg").createFile()
        assertEquals("Python cache", reason(folder))
    }

    @Test
    fun `globs match project files and folder names`() {
        assertEquals("obj for App.csproj", reason(folder("obj", "App.csproj")))
        assertEquals("foo.egg-info for pyproject.toml", reason(folder("foo.egg-info", "pyproject.toml")))
        assertEquals("cmake-build-debug for CMakeLists.txt", reason(folder("cmake-build-debug", "CMakeLists.txt")))
    }

    @Test
    fun `globs need one character for a star, and a plain name is no prefix`() {
        root.resolve("CMakeLists.txt").createFile()
        root.resolve("package.json").createFile()

        fun reasonOf(name: String) = reason(root.resolve(name).createDirectories())

        assertEquals("cmake-build-x for CMakeLists.txt", reasonOf("cmake-build-x"))
        assertNull(reasonOf("cmake-build-"))
        assertNull(reasonOf("distro"), "dist is no prefix of distro")
        assertNull(reasonOf("dist-x"))
        assertNull(reasonOf("documentation"), "13 characters, but no cmake-build- before them")
        assertEquals("dist for package.json", reasonOf("dist"))
        root.resolve("a.sbt").createFile()
        assertEquals("target for a.sbt", reasonOf("target"))
    }

    @Test
    fun `a rule with a parent does not apply to a folder without a folder or in the root`() {
        val siblings = setOf("bundle", "Gemfile")

        assertNull(generatedReason(Path.of("bundle"), siblings), "a relative name has no parent")
        assertNull(generatedReason(Path.of("/bundle"), siblings), "the root has no name to compare")
        assertNull(generatedReason(Path.of("/"), emptySet()), "the root has no name")
    }

    @Test
    fun `a generated file reason needs a file name and a name of the catalogue`() {
        assertNull(generatedFileReason(Path.of("/")))
        assertNull(generatedFileReason(root.resolve("a.txt")))
        assertEquals(
            GeneratedReason("macOS folder metadata", file = true),
            generatedFileReason(root.resolve(".DS_Store")),
        )
    }

    private fun padded(
        start: String,
        length: Int,
    ) = start + "#".repeat(length - start.length - 1) + "\n"

    @Test
    fun `a marker file of exactly the read limit is read, one byte more is not`() {
        val limit = 4096
        val ignored = folder("ignored")
        val tagged = folder("tagged")
        val signature = "Signature: 8a477f597d28d172789f06886806bc55\n"
        ignored.resolve(".gitignore").writeText(padded("*\n", limit))
        tagged.resolve("CACHEDIR.TAG").writeText(padded(signature, limit))
        assertEquals("marked by its .gitignore", reason(ignored))
        assertEquals("marked by CACHEDIR.TAG", reason(tagged))

        ignored.resolve(".gitignore").writeText(padded("*\n", limit + 1))
        tagged.resolve("CACHEDIR.TAG").writeText(padded(signature, limit + 1))
        assertNull(reason(ignored))
        assertNull(reason(tagged))
    }

    @Test
    fun `a marker that is no regular file is not even opened`() {
        assumeFalse(System.getProperty("os.name").lowercase().contains("win"))
        val folder = folder("anything")
        val fifo = folder.resolve(".gitignore")
        // mkfifo is the real tool; opening the pipe for reading would block until a writer comes, so one is waiting.
        assumeTrue(ProcessBuilder("mkfifo", fifo.toString()).start().waitFor() == 0)
        val writer =
            Thread {
                runCatching { Files.newOutputStream(fifo).use { it.write("*\n".toByteArray()) } }
            }.apply {
                isDaemon = true
                start()
            }
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(30)) { assertNull(reason(folder)) }
        } finally {
            // Releases the writer should nothing have read from the pipe: opening it read-write never blocks and
            // counts as a reader, so the writer's open and write finish into the pipe buffer.
            RandomAccessFile(fifo.toFile(), "rw").use { writer.join(10_000) }
        }
    }

    @Test
    fun `a marker whose size is told as zero but whose content is longer than the limit is no marker`() {
        // Linux only: /proc files are regular files of size 0. A child process (env, then sleep) holds an environment
        // whose first variable is the signature of CACHEDIR.TAG and whose content is bigger than the read limit.
        val proc = Path.of("/proc/self/environ")
        assumeTrue(Files.isRegularFile(proc) && Files.size(proc) == 0L)
        val child =
            ProcessBuilder(
                "env",
                "-i",
                "Signature: 8a477f597d28d172789f06886806bc55=x",
                "PAD=" + "a".repeat(5000),
                "sleep",
                "60",
            ).redirectErrorStream(true).start()
        try {
            val environ = Path.of("/proc/${child.pid()}/environ")
            val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
            // Until env has become sleep, the child still has the environment of this process.
            while (!Files.readString(Path.of("/proc/${child.pid()}/cmdline")).startsWith("sleep")) {
                check(System.nanoTime() < deadline) { "the child did not start sleep" }
                Thread.sleep(10) // polling a process that execs: there is no signal for it
            }
            val folder = folder("anything")
            Files.createSymbolicLink(folder.resolve("CACHEDIR.TAG"), environ)
            // What makes it a marker if it were read whole: readable, regular, size 0, and the signature first.
            assertTrue(Files.readString(environ).startsWith("Signature: 8a477f597d28d172789f06886806bc55"))
            assertEquals(0L, Files.size(environ))

            assertNull(reason(folder))
        } finally {
            child.destroyForcibly()
        }
    }

    @Test
    fun `a pipe named like a catalogue file is no generated file`() {
        assumeFalse(System.getProperty("os.name").lowercase().contains("win"))
        val fifo = root.resolve(".DS_Store")
        assumeTrue(ProcessBuilder("mkfifo", fifo.toString()).start().waitFor() == 0)

        // A read of the pipe would block until a writer comes: the check must not hang.
        assertTimeoutPreemptively(Duration.ofSeconds(30)) {
            assertNull(GeneratedFolderFilter { true }.structural(fifo, setOf(".DS_Store")))
        }
    }

    @Test
    fun `without git callbacks nothing is known about a path`() {
        val filter = GeneratedFolderFilter { true }

        assertNull(filter.ignoredByGit(root))
        assertNull(filter.trackedByGit(root))
        assertEquals(true, filter.isCatalogueFile(root.resolve(".DS_Store")))
        assertEquals(false, filter.isCatalogueFile(root.resolve("a.txt")))
    }

    @Test
    fun `a generated file is left out of a compacted count only while hiding, unless revealed, and only as a file`() {
        var enabled = true
        val filter = GeneratedFolderFilter { enabled }
        val file = root.resolve(".DS_Store").createFile()
        val other = root.resolve("a.txt").createFile()
        val folder = root.resolve("sub/.DS_Store").createDirectories()

        assertEquals(true, filter.hidesFileForCompacting(file))
        assertEquals(false, filter.hidesFileForCompacting(other), "not a catalogue name")
        assertEquals(false, filter.hidesFileForCompacting(folder), "a folder of that name is no file")
        filter.unhide(file)
        assertEquals(false, filter.hidesFileForCompacting(file), "revealed")
        filter.clearRevealed()
        assertEquals(true, filter.hidesFileForCompacting(file))
        enabled = false
        assertEquals(false, filter.hidesFileForCompacting(file), "the setting is off")
    }

    companion object {
        // Folder, project file next to it (in the grandparent for a parent name; "" for none), parent name, reason.
        @JvmStatic
        fun catalogueRows(): Stream<Arguments> =
            Stream.of(
                Arguments.of("node_modules", "package.json", null, "node_modules for package.json"),
                Arguments.of(".next", "package.json", null, ".next for package.json"),
                Arguments.of(".nuxt", "package.json", null, ".nuxt for package.json"),
                Arguments.of(".output", "package.json", null, ".output for package.json"),
                Arguments.of(".svelte-kit", "package.json", null, ".svelte-kit for package.json"),
                Arguments.of(".angular", "package.json", null, ".angular for package.json"),
                Arguments.of(".turbo", "package.json", null, ".turbo for package.json"),
                Arguments.of(".parcel-cache", "package.json", null, ".parcel-cache for package.json"),
                Arguments.of(".docusaurus", "package.json", null, ".docusaurus for package.json"),
                Arguments.of(".astro", "package.json", null, ".astro for package.json"),
                Arguments.of(".pnpm-store", "package.json", null, ".pnpm-store for package.json"),
                Arguments.of("dist", "package.json", null, "dist for package.json"),
                Arguments.of("coverage", "package.json", null, "coverage for package.json"),
                Arguments.of("bower_components", "bower.json", null, "bower_components for bower.json"),
                Arguments.of("build", "build.gradle", null, "build for build.gradle"),
                Arguments.of("build", "build.gradle.kts", null, "build for build.gradle.kts"),
                Arguments.of("build", "settings.gradle", null, "build for settings.gradle"),
                Arguments.of("build", "settings.gradle.kts", null, "build for settings.gradle.kts"),
                Arguments.of(".gradle", "build.gradle", null, ".gradle for build.gradle"),
                Arguments.of(".gradle", "build.gradle.kts", null, ".gradle for build.gradle.kts"),
                Arguments.of(".gradle", "settings.gradle", null, ".gradle for settings.gradle"),
                Arguments.of(".gradle", "settings.gradle.kts", null, ".gradle for settings.gradle.kts"),
                Arguments.of(".kotlin", "build.gradle", null, ".kotlin for build.gradle"),
                Arguments.of(".kotlin", "build.gradle.kts", null, ".kotlin for build.gradle.kts"),
                Arguments.of(".kotlin", "settings.gradle", null, ".kotlin for settings.gradle"),
                Arguments.of(".kotlin", "settings.gradle.kts", null, ".kotlin for settings.gradle.kts"),
                Arguments.of("target", "pom.xml", null, "target for pom.xml"),
                Arguments.of("target", "App.sbt", null, "target for App.sbt"),
                Arguments.of(".bsp", "App.sbt", null, ".bsp for App.sbt"),
                Arguments.of(".bloop", "App.sbt", null, ".bloop for App.sbt"),
                Arguments.of(".metals", "App.sbt", null, ".metals for App.sbt"),
                Arguments.of("target", "project.clj", null, "target for project.clj"),
                Arguments.of("target", "deps.edn", null, "target for deps.edn"),
                Arguments.of(".cpcache", "deps.edn", null, ".cpcache for deps.edn"),
                Arguments.of(".shadow-cljs", "shadow-cljs.edn", null, ".shadow-cljs for shadow-cljs.edn"),
                Arguments.of(".venv", "pyproject.toml", null, ".venv for pyproject.toml"),
                Arguments.of(".venv", "setup.py", null, ".venv for setup.py"),
                Arguments.of(".venv", "setup.cfg", null, ".venv for setup.cfg"),
                Arguments.of(".tox", "pyproject.toml", null, ".tox for pyproject.toml"),
                Arguments.of(".tox", "setup.py", null, ".tox for setup.py"),
                Arguments.of(".tox", "setup.cfg", null, ".tox for setup.cfg"),
                Arguments.of("__pypackages__", "pyproject.toml", null, "__pypackages__ for pyproject.toml"),
                Arguments.of("__pypackages__", "setup.py", null, "__pypackages__ for setup.py"),
                Arguments.of("__pypackages__", "setup.cfg", null, "__pypackages__ for setup.cfg"),
                Arguments.of("build", "pyproject.toml", null, "build for pyproject.toml"),
                Arguments.of("build", "setup.py", null, "build for setup.py"),
                Arguments.of("build", "setup.cfg", null, "build for setup.cfg"),
                Arguments.of("dist", "pyproject.toml", null, "dist for pyproject.toml"),
                Arguments.of("dist", "setup.py", null, "dist for setup.py"),
                Arguments.of("dist", "setup.cfg", null, "dist for setup.cfg"),
                Arguments.of("foo.egg-info", "pyproject.toml", null, "foo.egg-info for pyproject.toml"),
                Arguments.of("foo.egg-info", "setup.py", null, "foo.egg-info for setup.py"),
                Arguments.of("foo.egg-info", "setup.cfg", null, "foo.egg-info for setup.cfg"),
                Arguments.of(".tox", "tox.ini", null, ".tox for tox.ini"),
                Arguments.of(".nox", "noxfile.py", null, ".nox for noxfile.py"),
                Arguments.of("target", "Cargo.toml", null, "target for Cargo.toml"),
                Arguments.of("bin", "App.csproj", null, "bin for App.csproj"),
                Arguments.of("bin", "App.fsproj", null, "bin for App.fsproj"),
                Arguments.of("bin", "App.vbproj", null, "bin for App.vbproj"),
                Arguments.of("obj", "App.csproj", null, "obj for App.csproj"),
                Arguments.of("obj", "App.fsproj", null, "obj for App.fsproj"),
                Arguments.of("obj", "App.vbproj", null, "obj for App.vbproj"),
                Arguments.of(".vs", "App.sln", null, ".vs for App.sln"),
                Arguments.of(".vs", "App.slnx", null, ".vs for App.slnx"),
                Arguments.of("cmake-build-debug", "CMakeLists.txt", null, "cmake-build-debug for CMakeLists.txt"),
                Arguments.of(".bundle", "Gemfile", null, ".bundle for Gemfile"),
                Arguments.of("coverage", "Gemfile", null, "coverage for Gemfile"),
                Arguments.of("vendor", "composer.json", null, "vendor for composer.json"),
                Arguments.of(".build", "Package.swift", null, ".build for Package.swift"),
                Arguments.of(".swiftpm", "Package.swift", null, ".swiftpm for Package.swift"),
                Arguments.of("Pods", "Podfile", null, "Pods for Podfile"),
                Arguments.of(".dart_tool", "pubspec.yaml", null, ".dart_tool for pubspec.yaml"),
                Arguments.of("build", "pubspec.yaml", null, "build for pubspec.yaml"),
                Arguments.of("local", "cpanfile", null, "local for cpanfile"),
                Arguments.of("blib", "Makefile.PL", null, "blib for Makefile.PL"),
                Arguments.of("blib", "Build.PL", null, "blib for Build.PL"),
                Arguments.of("_build", "Makefile.PL", null, "_build for Makefile.PL"),
                Arguments.of("_build", "Build.PL", null, "_build for Build.PL"),
                Arguments.of("lua_modules", "App.rockspec", null, "lua_modules for App.rockspec"),
                Arguments.of(".dub", "dub.json", null, ".dub for dub.json"),
                Arguments.of(".dub", "dub.sdl", null, ".dub for dub.sdl"),
                Arguments.of("build", "fpm.toml", null, "build for fpm.toml"),
                Arguments.of(".terraform", "main.tf", null, ".terraform for main.tf"),
                Arguments.of("bundle", "Gemfile", "vendor", "vendor/bundle for Gemfile"),
                Arguments.of("target", "build.sbt", "project", "project/target for build.sbt"),
                Arguments.of("Build", "Cartfile", "Carthage", "Carthage/Build for Cartfile"),
                Arguments.of("__pycache__", "", null, "Python cache"),
                Arguments.of(".pytest_cache", "", null, "Python cache"),
                Arguments.of(".mypy_cache", "", null, "Python cache"),
                Arguments.of(".ruff_cache", "", null, "Python cache"),
                Arguments.of(".hypothesis", "", null, "Python cache"),
                Arguments.of(".ipynb_checkpoints", "", null, "Jupyter checkpoints"),
                Arguments.of("htmlcov", "", null, "coverage report"),
            )
    }
}
