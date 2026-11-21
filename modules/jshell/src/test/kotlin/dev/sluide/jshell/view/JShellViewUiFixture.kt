package dev.sluide.jshell.view

import dev.sluide.fileviewers.CodeEditor
import dev.sluide.fileviewers.CodeEditors
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.fileviewers.Highlighters
import dev.sluide.java.JavaHomes
import dev.sluide.java.Jdk
import dev.sluide.jshell.FakeAgent
import dev.sluide.jshell.JShellJdks
import dev.sluide.jshell.JShellSession
import dev.sluide.jshell.JShellWindow
import dev.sluide.projectdata.ProjectDataStore
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.fxmisc.richtext.CodeArea
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.util.WaitForAsyncUtils
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.fail

/**
 * A [JShellView] in a window with a [JShellWindow] whose sessions run on fake agents, queued in [agents]; the JDKs are
 * folders with a `release` file and a `bin/java`. ApplicationExtension only finds `@Start` on the test class itself:
 * each topic class declares one that calls [startWindow].
 */
abstract class JShellViewUiFixture {
    @TempDir
    lateinit var dir: Path

    @TempDir
    lateinit var support: Path

    private lateinit var root: StackPane

    internal val agents = LinkedBlockingQueue<FakeAgent>()
    internal val views = mutableListOf<JShellView>()
    internal var chooseJdkCalls = 0

    internal val jdks by lazy {
        JShellJdks(
            ProjectDataStore(support.resolve("projects"), support.resolve("shared.json"), emptyList()),
            JavaHomes({ _, _ -> null }, emptyMap(), dir.resolve("no-thoreau-jdk")),
        ) {
            settingGate?.await()
            settingReads.incrementAndGet()
            projectSetting
        }
    }

    /** While set, the JDK lookup waits for it to open before it reads the project's JDK setting. */
    @Volatile internal var settingGate: java.util.concurrent.CountDownLatch? = null

    /** How often the JDK lookup read the project's JDK setting (on a worker thread). */
    internal val settingReads = AtomicInteger()

    /** While set, the lookup of the installed JDKs waits for it to open. */
    @Volatile internal var installedGate: java.util.concurrent.CountDownLatch? = null

    /** How often the installed JDKs were looked up (on a worker thread). */
    internal val installedCalls = AtomicInteger()

    /** Whether the window can open the project settings. */
    internal var canChoose = true

    /** The project's JDK setting the window gives. */
    internal var projectSetting: Path? = null

    internal val window by lazy {
        JShellWindow(
            jdks,
            {
                installedGate?.await()
                installedCalls.incrementAndGet()
                listOf(Jdk(jdkHome(25, "25.0.5"), "25.0.5", 25), Jdk(jdkHome(21, "21.0.13.1"), "21.0.13.1", 21))
            },
            { chooseJdkCalls++ },
            {
                jdk,
                onEvent,
                ->
                JShellSession.start(jdk, onEvent, { checkNotNull(agents.poll()) { "no agent" } }, 10_000, 2_000)
            },
            { canChoose },
        )
    }

    internal fun startWindow(stage: Stage) {
        root = StackPane()
        stage.scene = Scene(root, 900.0, 500.0)
        stage.show()
    }

    @AfterEach
    fun closeViews(robot: FxRobot) {
        settingGate?.countDown()
        installedGate?.countDown()
        robot.interact { views.forEach(JShellView::dispose) }
    }

    /** A JDK folder of [major] that the JDK readers accept. */
    internal fun jdkHome(
        major: Int,
        version: String,
    ): Path {
        val home = dir.resolve("jdks/jdk-$major").createDirectories()
        home
            .resolve("bin")
            .createDirectories()
            .resolve("java")
            .takeUnless { Files.exists(it) }
            ?.writeText("")
        home.resolve("release").writeText("JAVA_VERSION=\"$version\"\n")
        return home
    }

    /** A host of the view in [folder]; it has the [window] as its JShell service unless [withService] is false. */
    internal fun host(
        folder: Path = dir,
        withService: Boolean = true,
    ) = object : FileViewHost {
        override fun openFile(file: Path) = false

        override fun openWebPage(uri: URI) {}

        override val projectFolder: Path get() = folder

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> windowService(type: Class<T>): T? =
            if (withService && type == JShellWindow::class.java) window as T else null
    }

    /** A view of a `.jsh` file with [text], shown in the window, with the host that has the [window]. */
    internal fun open(
        robot: FxRobot,
        text: String,
        withHost: Boolean = true,
    ): JShellView {
        val file = dir.resolve("deploy.jsh").apply { writeText(text) }
        val editor =
            WaitForAsyncUtils
                .asyncFx<CodeEditor> {
                    CodeEditors.open(file, Highlighters.forLanguage("java"), "//")
                }.get()
        editorOf = editor
        val view = WaitForAsyncUtils.asyncFx<JShellView> { JShellView(file, editor) }.get()
        views += view
        robot.interact {
            root.children.setAll(view.node)
            if (withHost) view.host = host()
        }
        return view
    }

    internal lateinit var editorOf: CodeEditor

    /** Shows [node] in the window, in place of what it showed. */
    internal fun show(
        robot: FxRobot,
        node: Node,
    ) = robot.interact { root.children.setAll(node) }

    internal fun codeArea(robot: FxRobot): CodeArea = robot.lookup(".code-area").queryAs(CodeArea::class.java)

    internal fun waitUntil(
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + 15_000_000_000L
        // the condition reads controls: on the FX thread
        while (!fx(condition)) {
            if (System.nanoTime() > deadline) fail("$what did not happen within 15 s")
            WaitForAsyncUtils.waitForFxEvents()
        }
    }

    /** [block] on the FX thread, for what reads the state of controls. */
    internal fun <T> fx(block: () -> T): T = WaitForAsyncUtils.asyncFx<T> { block() }.get()

    private val shortcutIsMeta = System.getProperty("os.name").startsWith("Mac")

    internal fun key(
        code: KeyCode,
        shift: Boolean = false,
    ) = KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, !shortcutIsMeta, false, shortcutIsMeta)

    /** [event] fired at [target] on the FX thread. */
    internal fun fire(
        robot: FxRobot,
        target: Node,
        event: Event,
    ) = robot.interact { Event.fireEvent(target, event) }
}
