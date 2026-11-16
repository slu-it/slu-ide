package dev.sluide.java

import dev.sluide.languageservers.LspUris
import dev.sluide.languageservers.WorkspaceFolderMode
import dev.sluide.languageservers.WorkspaceStatus
import dev.sluide.projectdata.ProjectDataStore
import org.eclipse.lsp4j.ConfigurationItem
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class JdtlsSpecTest {
    @TempDir
    lateinit var dir: Path

    private val fakes by lazy { JdtlsFakes(dir) }
    private val installer by lazy { fakes.installer() }
    private val projectSettings by lazy {
        JavaProjectSettings(ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())) {
            it.run()
        }
    }
    private val use by lazy { JavaWorkspaceUse { it.run() } }
    private val workspaces by lazy { JavaWorkspaces(dir.resolve("workspaces")) }
    private val thoreauJdk by lazy { fakeJdk(dir.resolve("thoreau-jdk"), 25) }
    private val homes by lazy { JavaHomes({ _, _ -> null }, emptyMap(), thoreauJdk) }
    private val classes by lazy { dir.resolve("classes") }
    private val window by lazy { Files.createDirectories(dir.resolve("work")) }
    private val spec by lazy {
        JdtlsSpec(installer, fakes.settings, projectSettings, use, homes, workspaces, classes, Runnable::run, 30)
    }

    /** `work/app` (Maven), `work/g` (Gradle) and `work/loose`. */
    private fun fixtures(): Triple<Path, Path, Path> {
        window.resolve("app/pom.xml").file("<project/>")
        val maven = window.resolve("app/src/main/java/A.java").file("class A {}")
        window.resolve("g/settings.gradle").file()
        val gradle = window.resolve("g/src/main/java/B.java").file("class B {}")
        val loose = window.resolve("loose/C.java").file("class C {}")
        return Triple(maven, gradle, loose)
    }

    @ParameterizedTest
    @CsvSource(
        "A.java, true",
        "A.JAVA, true",
        "a.Java, true",
        "A.kt, false",
        "java, false",
        "A.java.txt, false",
        "A.class, false",
    )
    fun `it handles java files by extension in any case`(
        name: String,
        expected: Boolean,
    ) {
        assertEquals(expected, spec.matches(window.resolve("src").resolve(name)))
    }

    @Test
    fun `it names the language, runs project code behind both build checks and offers its installer`() {
        assertEquals("java", spec.id)
        assertEquals("Java", spec.name)
        assertEquals("java", spec.languageId(window.resolve("A.java")))
        assertEquals(WorkspaceFolderMode.ROOTS_ONLY, spec.workspaceFolderMode)
        assertTrue(spec.runsProjectCode)
        assertEquals(listOf("gradle", "maven"), spec.trustCheckIds)
        assertSame(installer, spec.installer)
    }

    @Test
    fun `jdtls asks for no configuration section`() {
        assertNull(spec.configuration(ConfigurationItem().apply { section = "java.format.settings.url" }))
        assertNull(spec.configuration(ConfigurationItem()))
    }

    @Test
    fun `the workspace folder is the build root, none for a loose file`() {
        val (maven, gradle, loose) = fixtures()

        assertEquals(window.resolve("app"), spec.workspaceFolder(maven, window))
        assertEquals(window.resolve("g"), spec.workspaceFolder(gradle, window))
        assertNull(spec.workspaceFolder(loose, window))
    }

    @Test
    fun `the Kotlin flag of a file is kept per window until its session closes`() {
        val (maven, _, _) = fixtures()
        window.resolve("app/src/main/kotlin").toFile().mkdirs()
        spec.workspaceFolder(maven, window)
        assertTrue(spec.kotlinNear(window, maven))
        assertFalse(spec.kotlinNear(dir.resolve("other-window"), maven))

        val extension = assertNotNull(spec.sessionExtension(window, RecordingAccess()))
        assertTrue(spec.kotlinNear(window, maven))
        extension.close()

        assertFalse(spec.kotlinNear(window, maven))
    }

    @Test
    fun `a file that lost its build is forgotten with its Kotlin flag`() {
        val (maven, _, _) = fixtures()
        window.resolve("app/src/main/kotlin").toFile().mkdirs()
        spec.workspaceFolder(maven, window)
        assertTrue(spec.kotlinNear(window, maven))

        Files.delete(window.resolve("app/pom.xml"))
        assertNull(spec.workspaceFolder(maven, window))

        assertFalse(spec.kotlinNear(window, maven))
    }

    @Test
    fun `a change reaches the sessions of every window whose folder holds it, nested windows too`() {
        val outer = Files.createDirectories(dir.resolve("mono"))
        val inner = Files.createDirectories(outer.resolve("services/billing"))
        inner.resolve("settings.gradle").file()
        val other = Files.createDirectories(dir.resolve("elsewhere"))
        val outerAccess = RecordingAccess()
        val innerAccess = RecordingAccess()
        val otherAccess = RecordingAccess()
        val first = assertNotNull(spec.sessionExtension(outer, outerAccess))
        val second = assertNotNull(spec.sessionExtension(inner, innerAccess))
        val third = assertNotNull(spec.sessionExtension(other, otherAccess))
        first.onFolderAdded(inner)
        second.onFolderAdded(inner)
        third.onFolderAdded(other)

        spec.affectsWorkspaceFolders(inner.resolve("settings.gradle"))

        awaitCondition("both notifications") {
            outerAccess.notifications.isNotEmpty() && innerAccess.notifications.isNotEmpty()
        }
        assertTrue(otherAccess.notifications.isEmpty(), "a window that does not hold the file hears nothing")
        first.close()
        second.close()
        third.close()
    }

    @Test
    fun `the initialization options announce the capabilities and the settings with the project's JDK`() {
        val jdk = fakeJdk(dir.resolve("jdk-21"), 21)
        projectSettings.setJdk(window, jdk)

        val options = spec.initializationOptions(window)

        val script = workspaces.folderFor(window).resolve("thoreau-output.gradle")
        assertEquals(
            mapOf(
                "extendedClientCapabilities" to
                    mapOf("classFileContentsSupport" to true, "progressReportProvider" to false),
                "settings" to JdtlsSettings.of(Jdk(jdk, "21.0.1", 21), script),
            ),
            options,
        )
        assertEquals(JdkSource.PROJECT_SETTING, spec.jdkOf(window)?.source)
    }

    @Test
    fun `without a project JDK the resolved one is taken and remembered with its source`() {
        val options = spec.initializationOptions(window) as Map<*, *>

        val java = (options["settings"] as Map<*, *>)["java"] as Map<*, *>
        val runtimes = (java["configuration"] as Map<*, *>)["runtimes"]
        assertEquals(
            listOf(mapOf("name" to "JavaSE-25", "path" to thoreauJdk.toString(), "default" to true)),
            runtimes,
        )
        assertEquals(JdkSource.THOREAU, spec.jdkOf(window)?.source)
    }

    @Test
    fun `a project JDK that is not a JDK falls back and says why`() {
        projectSettings.setJdk(window, dir.resolve("nothing-here"))

        spec.initializationOptions(window)

        val resolved = assertNotNull(spec.jdkOf(window))
        assertEquals(JdkSource.THOREAU, resolved.source)
        assertNotNull(resolved.problem)
    }

    @Test
    fun `the command needs an installed jdtls`() {
        assertNull(spec.command(window))
    }

    @Test
    fun `the command is Thoreau's java with a data folder per window, the copied config and the memory setting`() {
        val marker = fakeJdtlsHome(dir.resolve("jdtls-1.63"))
        fakes.settings.apply(marker.toString())
        fakes.settings.applyMaxMemory(3072)

        val command = assertNotNull(spec.command(window))

        val prepared = workspaces.folderFor(window)
        val arguments = command.arguments
        assertEquals(Path.of(System.getProperty("java.home")).resolve("bin/java"), command.executable)
        assertEquals(prepared.resolve("data").toString(), arguments[arguments.indexOf("-data") + 1])
        assertEquals(
            prepared.resolve("config-jdtls-1.63").toString(),
            arguments[arguments.indexOf("-configuration") + 1],
        )
        assertTrue("-Xmx3072m" in arguments)
        assertTrue(arguments.none { it.startsWith("-javaagent") })
        assertEquals(prepared.resolve("data"), command.workingDirectory)
        assertEquals(GradleInitScript.CONTENT, Files.readString(prepared.resolve("thoreau-output.gradle")))
        assertFalse(
            Files.exists(window.resolve("thoreau-output.gradle")),
            "the script goes into the workspace folder, never the project",
        )
    }

    @Test
    fun `two windows get two data folders`() {
        fakes.settings.apply(fakeJdtlsHome(dir.resolve("jdtls-1.63")).toString())
        val other = Files.createDirectories(dir.resolve("other"))

        val first = assertNotNull(spec.command(window))
        val second = assertNotNull(spec.command(other))

        assertTrue(first.workingDirectory != second.workingDirectory)
    }

    @Test
    fun `a folder that is no unpacked jdtls gives no command`() {
        val marker = dir.resolve("broken/bin/jdtls").file()
        marker.toFile().setExecutable(true)
        fakes.settings.apply(marker.toString())

        assertNull(spec.command(window))
    }

    @Test
    fun `the Lombok agent is added only once the window's flag is set and a jar is installed`() {
        fakes.settings.apply(fakeJdtlsHome(dir.resolve("jdtls-1.63")).toString())
        installer.install(null)
        fakes.runQueued()
        val jar = assertNotNull(installer.lombokJar())

        assertTrue(assertNotNull(spec.command(window)).arguments.none { it.startsWith("-javaagent") })

        spec.stateOf(window).lombokRestarted.set(true)

        assertTrue("-javaagent:$jar" in assertNotNull(spec.command(window)).arguments)
        assertTrue(
            assertNotNull(spec.command(dir.resolve("other-window"))).arguments.none { it.startsWith("-javaagent") },
            "another window has its own flag",
        )
    }

    @Test
    fun `the Lombok flag without a jar adds no agent`() {
        fakes.settings.apply(fakeJdtlsHome(dir.resolve("jdtls-1.63")).toString())
        spec.stateOf(window).lombokRestarted.set(true)

        assertTrue(assertNotNull(spec.command(window)).arguments.none { it.startsWith("-javaagent") })
    }

    @Test
    fun `the plain command is the installer's availability command`() {
        assertEquals(installer.command(), spec.command())
    }

    @Test
    fun `a jdt URI is fetched into the classes cache and other URIs are not`() {
        val uri = "jdt://contents/java.base/java.lang/String.class?=x"

        assertEquals(JdtLibraryDocuments.of(uri, classes), spec.libraryDocument(uri))
        assertNull(spec.libraryDocument("file:///a/B.java"))
    }

    @ParameterizedTest
    @CsvSource(
        "pom.xml, true",
        "build.gradle, true",
        "settings.gradle.kts, true",
        "gradle-wrapper.properties, true",
        "A.java, false",
        "notes.txt, false",
    )
    fun `a change affects the workspace folders when it is a build file`(
        name: String,
        expected: Boolean,
    ) {
        assertEquals(expected, spec.affectsWorkspaceFolders(window.resolve("app").resolve(name)))
    }

    @Test
    fun `a settings or wrapper change reaches the session of the window that holds it`() {
        val (_, gradle, _) = fixtures()
        val access = RecordingAccess()
        val extension = assertNotNull(spec.sessionExtension(window, access))
        extension.onFolderAdded(window.resolve("g"))

        spec.affectsWorkspaceFolders(window.resolve("g/settings.gradle"))

        awaitCondition("the notification") { access.notifications.isNotEmpty() }
        assertEquals(
            listOf<Pair<String, Any?>>(
                "java/projectConfigurationUpdate" to mapOf("uri" to LspUris.of(window.resolve("g/settings.gradle"))),
            ),
            access.notifications,
        )
        assertTrue(gradle.startsWith(window))
        extension.close()
    }

    @Test
    fun `a pom change or a change in another window reaches no session`() {
        fixtures()
        val access = RecordingAccess()
        val extension = assertNotNull(spec.sessionExtension(window, access))
        extension.onFolderAdded(window.resolve("g"))

        spec.affectsWorkspaceFolders(window.resolve("app/pom.xml"))
        spec.affectsWorkspaceFolders(dir.resolve("elsewhere/g/settings.gradle"))

        Thread.sleep(150) // Past the 30 ms debounce; nothing may come.
        assertTrue(access.notifications.isEmpty())
        extension.close()
    }

    @Test
    fun `the session of a window is found while its extension lives and holds the workspace folder in use`() {
        val extension = assertNotNull(spec.sessionExtension(window, RecordingAccess()))

        assertSame<Any?>(extension, spec.session(window))
        assertSame<Any?>(extension, spec.session(window.resolve("../work")))
        assertNull(spec.session(dir.resolve("other")))
        assertTrue(use.isInUse(workspaces.folderFor(window)))

        extension.close()

        assertNull(spec.session(window))
        assertFalse(use.isInUse(workspaces.folderFor(window)))
    }

    @Test
    fun `the status line names the root relative to the window and its tool`() {
        val (maven, gradle, _) = fixtures()
        val mavenRoot = assertNotNull(spec.workspaceFolder(maven, window))
        val gradleRoot = assertNotNull(spec.workspaceFolder(gradle, window))

        assertEquals(
            WorkspaceStatus("Java project: app · Maven", mavenRoot, mavenRoot.toString()),
            spec.workspaceStatus(maven, mavenRoot, window),
        )
        assertEquals(
            WorkspaceStatus("Java project: g · Gradle", gradleRoot, gradleRoot.toString()),
            spec.workspaceStatus(gradle, gradleRoot, window),
        )
    }

    @Test
    fun `a build at the window folder is named by the folder's name`() {
        window.resolve("pom.xml").file("<project/>")
        val file = window.resolve("src/A.java").file()
        val root = assertNotNull(spec.workspaceFolder(file, window))

        assertEquals("Java project: work · Maven", spec.workspaceStatus(file, root, window)?.text)
    }

    @Test
    fun `a loose file says there is no Java build and is not clickable`() {
        val (_, _, loose) = fixtures()

        assertEquals(WorkspaceStatus("No Java build", null, null), spec.workspaceStatus(loose, null, window))
    }

    @Test
    fun `there is no environment problem yet`() {
        val (maven, _, _) = fixtures()

        assertNull(spec.environmentProblem(maven, window))
    }
}
