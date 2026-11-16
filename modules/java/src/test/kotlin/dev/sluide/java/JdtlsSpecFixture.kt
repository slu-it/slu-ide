package dev.sluide.java

import com.google.gson.JsonParser
import dev.sluide.languageservers.SessionAccess
import dev.sluide.projectdata.ProjectDataStore
import dev.sluide.settings.ProjectSettingsOpeners
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import kotlin.test.assertIs

internal abstract class JdtlsSpecFixture {
    @TempDir
    lateinit var dir: Path

    protected val fakes by lazy { JdtlsFakes(dir) }
    protected val installer by lazy { fakes.installer() }
    protected val projectSettings by lazy {
        JavaProjectSettings(ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())) {
            it.run()
        }
    }
    protected val use by lazy { JavaWorkspaceUse { it.run() } }

    /** Runs inside every `deleteData`, before the delete; Clean tests set it. */
    protected var duringDelete: () -> Unit = {}
    protected val workspaces by lazy {
        object : JavaWorkspaces(dir.resolve("workspaces")) {
            override fun deleteData(windowFolder: Path) {
                duringDelete()
                super.deleteData(windowFolder)
            }
        }
    }
    protected val thoreauJdk by lazy { fakeJdk(dir.resolve("thoreau-jdk"), 25) }
    protected val homes by lazy { JavaHomes({ _, _ -> null }, emptyMap(), thoreauJdk) }
    protected val openers = ProjectSettingsOpeners()
    protected val checks = OutputIgnoreChecks()
    protected val now = Instant.parse("2026-01-01T10:00:00Z")
    protected val clock = MutableClock(now)
    protected val classes by lazy { dir.resolve("classes") }
    protected val window by lazy { Files.createDirectories(dir.resolve("work")) }
    protected val spec by lazy {
        JdtlsSpec(
            installer,
            fakes.settings,
            projectSettings,
            use,
            homes,
            workspaces,
            openers,
            checks,
            classes,
            Runnable::run,
            30,
            clock,
        )
    }

    /** `work/app` (Maven), `work/g` (Gradle) and `work/loose`. */
    protected fun fixtures(): Triple<Path, Path, Path> {
        window.resolve("app/pom.xml").file("<project/>")
        val maven = window.resolve("app/src/main/java/A.java").file("class A {}")
        window.resolve("g/settings.gradle").file()
        val gradle = window.resolve("g/src/main/java/B.java").file("class B {}")
        val loose = window.resolve("loose/C.java").file("class C {}")
        return Triple(maven, gradle, loose)
    }

    protected fun javaSession(access: SessionAccess): JavaSession =
        assertIs<JavaSession>(spec.sessionExtension(window, access))

    protected val begin = JsonParser.parseString("""{"kind":"begin","title":"Updating workspace folders"}""")
    protected val end = JsonParser.parseString("""{"kind":"end"}""")
    protected val added =
        JsonParser.parseString(
            """{"type":"Message","message":"Updating workspace folders: Adding 1 folder(s), removing 0 folders."}""",
        )

    /** Announces [root] and ends its import without a project: it fails with the log text. */
    protected fun fail(
        session: JavaSession,
        access: ScriptedAccess,
        root: Path,
        announce: Boolean = true,
    ) {
        access.answers["java.project.getAll"] = { CompletableFuture.completedFuture(emptyList<Any?>()) }
        if (announce) session.imports.onFolderAdded(root)
        session.imports.onStatus(added)
        session.imports.onProgress("t$root", begin)
        session.imports.onProgress("t$root", end)
    }

    protected fun finish(
        session: JavaSession,
        access: ScriptedAccess,
        root: Path,
        compliance: String? = null,
        announce: Boolean = true,
    ) {
        access.answers["java.project.getAll"] = { CompletableFuture.completedFuture(listOf("file:$root/")) }
        access.answers["java.project.getSettings"] =
            { CompletableFuture.completedFuture(mapOf("org.eclipse.jdt.core.compiler.compliance" to compliance)) }
        // Compiled output for the offer to ignore it: git is asked about folders with a file only.
        root.resolve("target/A.class").file("x")
        root.resolve("build/jdtls-bin/A.class").file("x")
        if (announce) session.imports.onFolderAdded(root)
        session.imports.onStatus(added)
        session.imports.onProgress("t$root", begin)
        session.imports.onProgress("t$root", end)
    }
}

internal class MutableClock(
    var current: Instant,
) : Clock() {
    override fun instant(): Instant = current

    override fun getZone() = ZoneOffset.UTC

    override fun withZone(zone: java.time.ZoneId?): Clock = this
}
