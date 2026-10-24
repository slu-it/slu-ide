package dev.sluide.gradle.view

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.gradle.GradleSession
import dev.sluide.gradle.tasks.GradleTask
import dev.sluide.projecttrust.ProjectTrust
import dev.sluide.projecttrust.ProjectTrustCheck
import dev.sluide.projecttrust.TrustFinding
import dev.sluide.projecttrust.TrustedProjects
import dev.sluide.settings.FontSettings
import dev.sluide.settings.SettingsStore
import dev.sluide.terminal.Terminals
import dev.sluide.terminal.settings.TerminalScrollSettings
import javafx.event.Event
import javafx.scene.Scene
import javafx.scene.control.TreeCell
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.util.WaitForAsyncUtils
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * A Gradle view of a fixture build whose `gradlew` is a fake POSIX sh script: `sluideTaskList` writes the task list
 * (the init script's real output, paths moved to the fixture), `:fail` exits 1, `:slow` writes its pid and sleeps, other
 * tasks echo. Runs in terminals with `/bin/sh` as the login shell.
 */
abstract class GradleFileViewUiFixture {
    @TempDir
    lateinit var project: Path

    @TempDir
    lateinit var support: Path

    internal lateinit var root: StackPane

    /** Every background task log line of the session. */
    internal val taskLog = CopyOnWriteArrayList<String>()

    /** How often the global check ran, and what it answers. */
    internal val globalChecks = AtomicInteger()
    internal var globalGradle = false

    /** What the trust check finds: nothing (Gradle may run) unless set before the view is opened. */
    internal var findings: List<TrustFinding> = emptyList()
    internal var reviews = 0

    internal val trust by lazy {
        val check =
            object : ProjectTrustCheck {
                override val id = "gradle"
                override val name = "Gradle"

                override fun check(folder: Path) = findings
            }
        ProjectTrust(
            listOf(check),
            TrustedProjects(support.resolve("trust.properties")),
            BackgroundTasks.forTests(),
            javafx.application.Platform::runLater,
        ).apply { open(project) }
    }

    private val store by lazy { SettingsStore(support.resolve("settings.properties")) }
    private val terminals by lazy {
        Terminals(
            FontSettings(store, FontSettings.systemDefaults()),
            TerminalScrollSettings(store),
            System.getenv() + ("SHELL" to "/bin/sh"),
        )
    }

    internal val session by lazy {
        GradleSession(trust, BackgroundTasks.forTests { taskLog += it }, terminals) {
            globalChecks.incrementAndGet()
            globalGradle
        }.apply { reviewTrust = { reviews++ } }
    }

    private val host =
        object : FileViewHost {
            override fun openFile(file: Path) = false

            override fun openWebPage(uri: URI) {}

            override val projectFolder: Path get() = project

            @Suppress("UNCHECKED_CAST")
            override fun <T : Any> windowService(type: Class<T>): T? =
                if (type == GradleSession::class.java) session as T else null
        }

    private val views = mutableListOf<GradleFileView>()

    /** The file the fake script writes the pid of `:slow` to. */
    internal val slowPid: Path get() = support.resolve("slow.pid")

    /** The file the fake script appends a line to for every task list it writes. */
    internal val loads: Path get() = support.resolve("loads.log")

    internal fun startView(stage: Stage) {
        root = StackPane()
        stage.scene = Scene(root, 1000.0, 700.0)
        stage.show()
    }

    @AfterEach
    fun closeViews(robot: FxRobot) {
        robot.interact { views.forEach(GradleFileView::dispose) }
        session.close()
        terminals.close()
        trust.close()
    }

    /** Writes the build: a wrapper with the fake script, and the build files the task list names. */
    internal fun build(wrapper: Boolean = true) {
        for (file in listOf("build.gradle", "app/build.gradle", "app/core/build.gradle", "lib/build.gradle.kts")) {
            project.resolve(file).apply { parent.createDirectories() }.writeText("// $file\n")
        }
        if (!wrapper) return
        val fixture = checkNotNull(javaClass.getResource("/dev/sluide/gradle/tasks/task-list.json")).readText()
        val json =
            fixture
                .replace("/work/fx", project.toRealPath().toString())
                .replaceFirst(
                    "\"tasks\": [",
                    "\"tasks\": [" + task("fail", "Exits with 1") + "," + task("slow", "Sleeps") + ",",
                )
        val taskList = support.resolve("task-list.json").apply { writeText(json) }
        project.resolve("gradle/wrapper").createDirectories()
        project.resolve("gradle/wrapper/gradle-wrapper.properties").writeText("distributionUrl=none\n")
        project.resolve("gradlew").apply {
            writeText(
                """
                |#!/bin/sh
                |for arg in "$@"; do
                |  case "${'$'}arg" in -Dsluide.taskList=*) out="${'$'}{arg#-Dsluide.taskList=}" ;; esac
                |done
                |case " $* " in
                |  *" sluideTaskList "*) cp '$taskList' "${'$'}out"; echo load >> '$loads'; exit 0 ;;
                |  *" :fail "*) echo failing; exit 1 ;;
                |  *" :slow "*) echo ${'$'}${'$'} > '$slowPid'; exec sleep 30 ;;
                |esac
                |echo "ran $*"
                """.trimMargin(),
            )
            toFile().setExecutable(true)
        }
    }

    private fun task(
        name: String,
        description: String,
    ) = """{"name": "$name", "path": ":$name", "group": "demo", "description": "$description"}"""

    /** Opens [file] (relative to the project) in a Gradle view inside the window and gives it the host. */
    internal fun open(
        robot: FxRobot,
        file: String = "build.gradle",
    ): GradleFileView {
        lateinit var view: GradleFileView
        robot.interact {
            val path = project.resolve(file)
            val viewer = if (file.endsWith(".kts")) GradleKotlinFileViewer() else GradleGroovyFileViewer()
            view = viewer.open(path) as GradleFileView
            views += view
            root.children.setAll(view.node)
            view.host = host
        }
        return view
    }

    /** Waits until [assertion] holds on the FX thread, up to 20 s. */
    internal fun eventually(
        robot: FxRobot,
        assertion: () -> Unit,
    ) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (true) {
            WaitForAsyncUtils.waitForFxEvents()
            var failure: Throwable? = null
            robot.interact { failure = runCatching(assertion).exceptionOrNull() }
            val error = failure ?: return
            if (System.nanoTime() > deadline) throw error
            Thread.sleep(50)
        }
    }

    /** Waits until the session's thread has done what was given to it so far, and the FX thread what that posted. */
    internal fun sessionIdle() {
        val done = java.util.concurrent.CountDownLatch(1)
        session.execute { done.countDown() }
        done.await()
        WaitForAsyncUtils.waitForFxEvents()
    }

    /** Waits until the view's tasks are loaded and shown. */
    internal fun awaitTasks(
        robot: FxRobot,
        view: GradleFileView,
    ) = eventually(robot) {
        check(
            view.tasks.shownNote == null &&
                view.tasks.tree.root.children
                    .isNotEmpty(),
        ) { "tasks not shown: ${view.tasks.shownNote}" }
    }

    /** The tree cell showing the task [path]. */
    @Suppress("UNCHECKED_CAST")
    internal fun taskCell(
        robot: FxRobot,
        view: GradleFileView,
        path: String,
    ): TreeCell<Any> =
        robot
            .from(view.tasks.tree)
            .lookup(".tree-cell")
            .queryAllAs(TreeCell::class.java)
            .first { (it.item as? GradleTask)?.path == path } as TreeCell<Any>

    /** A double-click on [cell], as the mouse handlers see it, after its first click selected it. */
    internal fun doubleClick(cell: TreeCell<*>) {
        cell.treeView.selectionModel.clearAndSelect(cell.index)
        Event.fireEvent(
            cell,
            MouseEvent(
                MouseEvent.MOUSE_CLICKED,
                1.0,
                1.0,
                1.0,
                1.0,
                MouseButton.PRIMARY,
                2,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                true,
                null,
            ),
        )
    }

    /** The pid the fake `:slow` wrote, once it did. */
    internal fun slowProcess(robot: FxRobot): ProcessHandle {
        eventually(robot) { check(slowPid.toFile().length() > 0) { "no pid yet" } }
        return ProcessHandle.of(slowPid.readText().trim().toLong()).orElseThrow()
    }
}
