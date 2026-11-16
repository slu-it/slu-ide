package dev.sluide.java

import dev.sluide.languageservers.EnvironmentProblem
import dev.sluide.languageservers.LspUris
import dev.sluide.languageservers.ProblemAction
import dev.sluide.languageservers.WorkspaceFolderMode
import dev.sluide.languageservers.WorkspaceStatus
import org.eclipse.lsp4j.ConfigurationItem
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal class JdtlsSpecTest : JdtlsSpecFixture() {
    private fun dataOf(folder: Path) = workspaces.folderFor(folder).resolve("data")

    /** A prepared workspace of [window] whose data holds a marker. */
    private fun workspaceWithData() {
        fakes.settings.apply(fakeJdtlsHome(dir.resolve("jdtls-1.63")).toString())
        assertNotNull(spec.command(window))
        dataOf(window).resolve("marker").file("old index")
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
    fun `a root that imports shows a banner with its tool and the elapsed time`() {
        val (maven, gradle, _) = fixtures()
        val session = javaSession(ScriptedAccess())
        val mavenRoot = assertNotNull(spec.workspaceFolder(maven, window))
        val gradleRoot = assertNotNull(spec.workspaceFolder(gradle, window))
        session.imports.onFolderAdded(mavenRoot)
        session.imports.onFolderAdded(gradleRoot)

        assertEquals(
            EnvironmentProblem(mavenRoot, "Importing app (Maven)… 0:00", emptyList(), dismissible = false),
            spec.environmentProblem(maven, window),
        )
        clock.current = now.plusSeconds(75)
        assertEquals(
            EnvironmentProblem(gradleRoot, "Importing g (Gradle)… 1:15", emptyList(), dismissible = false),
            spec.environmentProblem(gradle, window),
        )
    }

    @Test
    fun `a build at the window folder is labelled by the folder's name in its banner`() {
        window.resolve("pom.xml").file("<project/>")
        val file = window.resolve("src/A.java").file()
        val root = assertNotNull(spec.workspaceFolder(file, window))
        javaSession(ScriptedAccess()).imports.onFolderAdded(root)

        assertEquals("Importing work (Maven)… 0:00", spec.environmentProblem(file, window)?.message)
    }

    @Test
    fun `a failed import says why and offers Retry, the log and the JDK choice`() {
        val (maven, _, _) = fixtures()
        val access = ScriptedAccess()
        val session = javaSession(access)
        val root = assertNotNull(spec.workspaceFolder(maven, window))
        fail(session, access, root)

        val problem = assertNotNull(spec.environmentProblem(maven, window))

        assertTrue(problem.dismissible)
        assertEquals(root, problem.folder)
        assertEquals("Import of app failed: No project was found; see the log.", problem.message)
        assertEquals(listOf("Retry Import", "Show Log", "Choose JDK…"), problem.actions.map { it.label })
        assertEquals(
            ProblemAction.OpenFile("Show Log", workspaces.folderFor(window).resolve("data/.metadata/.log")),
            problem.actions[1],
        )
    }

    @Test
    fun `Retry Import refreshes the root's folder and Choose JDK opens the Java page of Project Settings`() {
        val (maven, _, _) = fixtures()
        val access = ScriptedAccess()
        val session = javaSession(access)
        val root = assertNotNull(spec.workspaceFolder(maven, window))
        fail(session, access, root)
        val shown = mutableListOf<List<String>?>()
        openers.register(window) { shown.add(it) }
        val actions = assertNotNull(spec.environmentProblem(maven, window)).actions

        assertIs<ProblemAction.Callback>(actions[0]).perform()
        assertEquals(listOf(root), access.refreshed)
        assertEquals(emptyList(), shown)
        assertIs<ProblemAction.Callback>(actions[2]).perform()

        assertEquals(listOf<List<String>?>(listOf("Java")), shown)
    }

    @Test
    fun `an unchanged failure gives equal problems on every call`() {
        val (maven, _, _) = fixtures()
        val access = ScriptedAccess()
        val session = javaSession(access)
        fail(session, access, assertNotNull(spec.workspaceFolder(maven, window)))

        val first = assertNotNull(spec.environmentProblem(maven, window))
        assertEquals(first, spec.environmentProblem(maven, window))
    }

    @Test
    fun `a finished import, a loose file and a file never asked about have no banner`() {
        val (maven, _, loose) = fixtures()
        val access = ScriptedAccess()
        val session = javaSession(access)
        val root = assertNotNull(spec.workspaceFolder(maven, window))
        assertNull(spec.workspaceFolder(loose, window))
        finish(session, access, root)

        assertNull(spec.environmentProblem(maven, window))
        assertNull(spec.environmentProblem(loose, window))
        assertNull(spec.environmentProblem(window.resolve("never/Seen.java"), window))
    }

    @Test
    fun `a file that lost its build has no banner of the root it had`() {
        val (maven, _, _) = fixtures()
        val root = assertNotNull(spec.workspaceFolder(maven, window))
        javaSession(ScriptedAccess()).imports.onFolderAdded(root)
        assertNotNull(spec.environmentProblem(maven, window))

        Files.delete(window.resolve("app/pom.xml"))

        assertNull(spec.workspaceFolder(maven, window))
        assertNull(spec.environmentProblem(maven, window))
    }

    @Test
    fun `a clock that went back shows no negative time`() {
        val (maven, _, _) = fixtures()
        val root = assertNotNull(spec.workspaceFolder(maven, window))
        javaSession(ScriptedAccess()).imports.onFolderAdded(root)

        clock.current = now.minusSeconds(5)

        assertEquals("Importing app (Maven)… 0:00", spec.environmentProblem(maven, window)?.message)
    }

    @Test
    fun `a pom change below a failed root refreshes that root in the window's session`() {
        val (maven, _, _) = fixtures()
        val access = ScriptedAccess()
        val session = javaSession(access)
        val root = assertNotNull(spec.workspaceFolder(maven, window))
        session.onFolderAdded(root)
        fail(session, access, root, announce = false)

        spec.affectsWorkspaceFolders(window.resolve("app/pom.xml"))

        awaitCondition("the refresh") { access.refreshed.isNotEmpty() }
        assertEquals(listOf(root), access.refreshed)
    }

    @Test
    fun `a file whose root was never announced has no banner`() {
        val (maven, _, _) = fixtures()
        javaSession(ScriptedAccess())
        spec.workspaceFolder(maven, window)

        assertNull(spec.environmentProblem(maven, window))
    }

    @Test
    fun `the status line names the root, its tool, the compliance and the import's state`() {
        val (maven, gradle, _) = fixtures()
        val access = ScriptedAccess()
        val session = javaSession(access)
        val mavenRoot = assertNotNull(spec.workspaceFolder(maven, window))
        val gradleRoot = assertNotNull(spec.workspaceFolder(gradle, window))

        assertEquals("Java project: app · Maven", spec.workspaceStatus(maven, mavenRoot, window)?.text)
        session.imports.onFolderAdded(mavenRoot)
        assertEquals("Java project: app · Maven · importing…", spec.workspaceStatus(maven, mavenRoot, window)?.text)
        finish(session, access, mavenRoot, "17", announce = false)
        assertEquals("Java project: app · Maven · Java 17", spec.workspaceStatus(maven, mavenRoot, window)?.text)
        fail(session, access, gradleRoot)
        assertEquals("Java project: g · Gradle · import failed", spec.workspaceStatus(gradle, gradleRoot, window)?.text)
        assertEquals(mavenRoot, spec.workspaceStatus(maven, mavenRoot, window)?.folder)
    }

    @Test
    fun `Kotlin sources near the file end the line with that and add a line to the tooltip`() {
        val (maven, _, _) = fixtures()
        window.resolve("app/src/main/kotlin").toFile().mkdirs()
        val root = assertNotNull(spec.workspaceFolder(maven, window))
        spec.initializationOptions(window)
        val status = assertNotNull(spec.workspaceStatus(maven, root, window))

        assertEquals("Java project: app · Maven · Kotlin sources not visible", status.text)
        assertEquals(
            listOf(
                root.toString(),
                "JDK 25.0.1: $thoreauJdk (Thoreau's own JDK)",
                "Kotlin sources in this build aren't visible to jdtls; references to them show as errors.",
            ),
            status.tooltip?.lines(),
        )
    }

    @Test
    fun `the tooltip names the root, then the JDK with its source and the problem with it`() {
        val (maven, _, _) = fixtures()
        val root = assertNotNull(spec.workspaceFolder(maven, window))
        val broken = dir.resolve("broken-jdk").also { Files.createDirectories(it) }
        projectSettings.setJdk(window, broken)
        spec.initializationOptions(window)

        assertEquals(
            listOf(
                root.toString(),
                "JDK 25.0.1: $thoreauJdk (Thoreau's own JDK)",
                "The project's JDK $broken is not a JDK (no bin/java or release file); using Thoreau's own JDK.",
            ),
            spec.workspaceStatus(maven, root, window)?.tooltip?.lines(),
        )
    }

    @Test
    fun `a loose file says there is no build and names the JDK once it is resolved`() {
        val (_, _, loose) = fixtures()

        assertEquals(
            WorkspaceStatus("No Java build", null, "No build: JDK classes only"),
            spec.workspaceStatus(loose, null, window),
        )

        spec.initializationOptions(window)
        val status = assertNotNull(spec.workspaceStatus(loose, null, window))

        assertEquals("No Java build · JDK 25", status.text)
        assertNull(status.folder)
        assertEquals("No build: JDK classes only\nJDK 25.0.1: $thoreauJdk (Thoreau's own JDK)", status.tooltip)
    }

    @Test
    fun `a pending clean deletes the data folder once, keeps the rest of the workspace and clears the flag`() {
        workspaceWithData()
        val folder = workspaces.folderFor(window)
        spec.stateOf(window).cleanPending.set(true)

        assertNotNull(spec.command(window))

        assertFalse(dataOf(window).resolve("marker").exists(), "the old data is gone")
        assertTrue(Files.isDirectory(dataOf(window)), "and the folder is there for jdtls")
        assertTrue(Files.exists(folder.resolve("project-path.txt")))
        assertTrue(Files.isDirectory(folder.resolve("config-jdtls-1.63")))
        assertTrue(Files.exists(folder.resolve("thoreau-output.gradle")))
        assertFalse(spec.stateOf(window).cleanPending.get())
    }

    @Test
    fun `a command after the clean does not delete again`() {
        workspaceWithData()
        spec.stateOf(window).cleanPending.set(true)
        assertNotNull(spec.command(window))
        dataOf(window).resolve("marker").file("new index")

        assertNotNull(spec.command(window))

        assertTrue(dataOf(window).resolve("marker").exists())
    }

    @Test
    fun `a pending clean deletes the data also when jdtls has no usable path, and the command is null`() {
        val prepared = workspaces.prepare(window, Files.createDirectories(dir.resolve("config")), "k")
        prepared.data.resolve("marker").file("old index")
        spec.stateOf(window).cleanPending.set(true)

        assertNull(spec.command(window), "no jdtls is installed")

        assertFalse(prepared.data.resolve("marker").exists())
        assertFalse(spec.stateOf(window).cleanPending.get())
    }

    @Test
    fun `a clean requested while the delete runs is kept for the next start`() {
        workspaceWithData()
        val state = spec.stateOf(window)
        state.cleanPending.set(true)
        duringDelete = { state.cleanPending.set(true) }

        assertNotNull(spec.command(window))

        assertTrue(state.cleanPending.get())
        duringDelete = {}
        dataOf(window).resolve("marker").file("index of the first start")
        assertNotNull(spec.command(window))
        assertFalse(dataOf(window).resolve("marker").exists(), "the second Clean deleted it")
        assertFalse(state.cleanPending.get())
    }

    @Test
    fun `a clean of another window does not delete this window's data`() {
        workspaceWithData()
        spec.stateOf(dir.resolve("other-window")).cleanPending.set(true)

        assertNotNull(spec.command(window))

        assertTrue(dataOf(window).resolve("marker").exists())
    }

    @Test
    fun `cleanRunning sets the flag of the window and restarts its session`() {
        val access = RecordingAccess()
        val extension = assertNotNull(spec.sessionExtension(window, access))
        val other = assertNotNull(spec.sessionExtension(dir.resolve("other-window"), RecordingAccess()))

        spec.cleanRunning(window)

        assertTrue(spec.stateOf(window).cleanPending.get())
        assertEquals(1, access.restarts.size)
        assertFalse(spec.stateOf(dir.resolve("other-window")).cleanPending.get())
        extension.close()
        other.close()
    }

    @Test
    fun `cleanRunning without a session only sets the flag`() {
        spec.cleanRunning(window)

        assertTrue(spec.stateOf(window).cleanPending.get())
    }

    @Test
    fun `a workspace runs while a session of its window lives`() {
        assertFalse(spec.workspaceRuns(window))

        val extension = assertNotNull(spec.sessionExtension(window, RecordingAccess()))

        assertTrue(spec.workspaceRuns(window))
        assertFalse(spec.workspaceRuns(dir.resolve("other-window")))
        extension.close()
        assertFalse(spec.workspaceRuns(window))
    }

    @Test
    fun `cleanStopped deletes the data folder and keeps the rest`() {
        workspaceWithData()

        spec.cleanStopped(window)

        assertFalse(Files.exists(dataOf(window)))
        assertTrue(Files.exists(workspaces.folderFor(window).resolve("project-path.txt")))
    }
}
