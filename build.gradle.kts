import org.gradle.build.event.BuildEventsListenerRegistry
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.OperationCompletionListener
import org.gradle.tooling.events.task.TaskOperationDescriptor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.inject.Inject

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.javafx) apply false
    alias(libs.plugins.pitest) apply false
    alias(libs.plugins.spotless)
}

// `./gradlew spotlessApply` formats, `spotlessCheck` (part of `check`/`build`) verifies. Rules: .editorconfig
// Targets name the source folders only. With `**/*.kt` plus a `build/` exclude, spotlessKotlin failed twice under
// parallel execution with "Could not read path …/build/…" while other tasks wrote build/; naming the source folders
// avoids that walk. A new top-level source folder (e.g. buildSrc/) must be added to the targets.
spotless {
    kotlin {
        target("ide/src/**/*.kt", "modules/*/src/**/*.kt", "tools/*/src/**/*.kt")
        ktlint(libs.versions.ktlint.get())
    }
    kotlinGradle {
        target("*.gradle.kts", "ide/*.gradle.kts", "modules/*/*.gradle.kts", "tools/*/*.gradle.kts")
        ktlint(libs.versions.ktlint.get())
    }
}

allprojects {
    group = "dev.sluide"
    // CI passes -PreleaseVersion=<x.y.z> when building from a release tag.
    version = providers.gradleProperty("releaseVersion").getOrElse("0.1.0-SNAPSHOT")
}

/**
 * Gives every running UI test task a display of its own: TestFX tests of parallel modules on one display took each
 * other's window focus, which closed their popups (auto-hide follows the owner window's focus) and failed tests at
 * random. With one display (CI, a plain `./gradlew build`) the tasks run one at a time; full-build.sh starts a pool and
 * passes it as `-Psluide.uiDisplays=:99,:100,…` (ADR 0081). A display is given back when its task finishes, failed or
 * not; the finish event can arrive after Gradle already started the next task, so [lease] waits for it.
 */
abstract class DisplayService :
    BuildService<DisplayService.Params>,
    OperationCompletionListener {
    interface Params : BuildServiceParameters {
        val displays: ListProperty<String>
    }

    private val free = LinkedBlockingQueue(parameters.displays.get())
    private val leased = ConcurrentHashMap<String, String>()

    fun lease(taskPath: String): String {
        val display = free.poll(1, TimeUnit.MINUTES) ?: error("No display became free for $taskPath")
        leased[taskPath] = display
        return display
    }

    override fun onFinish(event: FinishEvent) {
        val taskPath = (event.descriptor as? TaskOperationDescriptor)?.taskPath ?: return
        leased.remove(taskPath)?.let(free::put)
    }
}

/** The registry that tells [DisplayService] when a task finishes; only injectable into Gradle-created objects. */
abstract class BuildEvents {
    @get:Inject
    abstract val registry: BuildEventsListenerRegistry
}

// One empty entry on systems without DISPLAY (macOS): their tests then keep the environment as it is. An empty
// property counts as unset.
val uiDisplays =
    providers
        .gradleProperty("sluide.uiDisplays")
        .map { property -> property.split(",").map(String::trim).filter(String::isNotEmpty) }
        .filter(List<String>::isNotEmpty)
        .orElse(providers.environmentVariable("DISPLAY").map(::listOf))
        .orElse(listOf(""))
        .get()

val display =
    gradle.sharedServices.registerIfAbsent("display", DisplayService::class) {
        parameters.displays.set(uiDisplays)
        maxParallelUsages.set(uiDisplays.size)
    }
objects.newInstance<BuildEvents>().registry.onTaskCompletion(display)

// Modules whose UI tests alone would set the build's duration run them as two tasks, on two displays at once
// (ADR 0082): `uiTestPart1` the classes that match the pattern, `uiTestPart2` all others, so that no new class is left
// out. `check` runs the two instead of `uiTest`, which still runs all classes, e.g. with `--tests`.
val uiTestSplits =
    mapOf(
        ":modules:git-log" to "dev.sluide.gitlog.GitLogView*",
        ":modules:previews" to "dev.sluide.previews.preview.*",
    )

// Modules whose tests play sound: media (JavaFX Media, ADR 0123) and speech (its audio sink tests will open a
// javax.sound line, ADR 0124). On Linux without a sound card (CI runners, the sandbox) ALSA cannot open the default
// device, so their test tasks get a null default device that discards the sound (docs/testing.md, "Media playback").
// Machines with a sound card keep their own.
val nullAlsaProjects = setOf(":modules:media", ":modules:speech")
val nullAlsaConfigPath: String? =
    file("/usr/share/alsa/alsa.conf")
        .takeIf { System.getProperty("os.name").lowercase().contains("linux") && !file("/dev/snd").exists() }
        ?.takeIf { it.exists() }
        ?.let { "$it:${file("gradle/alsa/null-default.conf")}" }

// Shared configuration for every Kotlin module (modules/*, ide and tools/*).
subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val javaVersion =
            rootProject.libs.versions.java
                .get()
                .toInt()

        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(javaVersion)
        }

        dependencies {
            // Versions of Spring and its transitive libraries are managed by the Spring Boot BOM.
            add("implementation", platform(rootProject.libs.spring.boot.dependencies))
            add("testImplementation", rootProject.libs.kotlin.test.junit5)
            add("testImplementation", rootProject.libs.junit.jupiter.params)
            add("testRuntimeOnly", rootProject.libs.junit.platform.launcher)
        }

        // Plain unit tests never need a display. UI tests (JUnit tag "ui", TestFX) run in `uiTest`, part of `check`
        // (or in its two parts, see uiTestSplits).
        // Linux without DISPLAY (e.g. a headless sandbox) skips them unless CI is set; start a virtual display
        // with .claude/scripts/setup-display.sh to run them there.
        tasks.named<Test>("test") {
            useJUnitPlatform { excludeTags("ui") }
        }

        // With `-q` Gradle would only say that tests failed; this names them with their exception, passed ones stay
        // silent.
        tasks.withType<Test>().configureEach {
            // JavaFX and JNA load native libraries from the class path; without this, every test JVM warns about it.
            jvmArgs("--enable-native-access=ALL-UNNAMED")
            // Tests that start Spring write their log file here, not into ~/.sluide/logs (ADR 0051).
            // An argument provider, not systemProperty: the absolute path would be part of every test's cache key.
            jvmArgumentProviders.add(LogFolderArgument(layout.buildDirectory.dir("test-logs")))
            testLogging {
                quiet {
                    events(org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED)
                    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                }
            }
        }

        val alsaConfigPath = nullAlsaConfigPath
        if (project.path in nullAlsaProjects && alsaConfigPath != null) {
            tasks.withType<Test>().configureEach { environment("ALSA_CONFIG_PATH", alsaConfigPath) }
        }

        if (!project.path.startsWith(":tools")) configurePitest()

        val testSourceSet = extensions.getByType<SourceSetContainer>().named("test")
        // A local copy: task actions that read a property of the build script would drag the script into the
        // configuration cache.
        val displayService = display
        val displayAvailable =
            providers.environmentVariable("DISPLAY").isPresent ||
                providers.environmentVariable("CI").isPresent ||
                !System.getProperty("os.name").lowercase().contains("linux")

        fun registerUiTest(
            name: String,
            taskDescription: String,
            select: TestFilter.() -> Unit = {},
        ) = tasks.register<Test>(name) {
            description = taskDescription
            group = "verification"
            filter(select)
            testClassesDirs = testSourceSet.get().output.classesDirs
            classpath = testSourceSet.get().runtimeClasspath
            useJUnitPlatform { includeTags("ui") }
            // Software rendering is the most reliable pipeline on virtual displays.
            systemProperty("prism.order", "sw")
            onlyIf("a display is available") { displayAvailable }
            usesService(displayService)
            // The environment is no task input, so the leased display changes no up-to-date check or cache key.
            doFirst {
                val leased = displayService.get().lease(path)
                if (leased.isNotEmpty()) (this as Test).environment("DISPLAY", leased)
            }
            shouldRunAfter(tasks.named("test"))
        }

        val uiTest = registerUiTest("uiTest", "Runs TestFX UI tests (JUnit tag 'ui'); needs a display.")
        val split = uiTestSplits[project.path]
        // `-x uiTest` (the macOS jobs of CI and release) means no UI tests at all, so it leaves out the parts too.
        val uiTestExcluded =
            gradle.startParameter.excludedTaskNames.any { it == "uiTest" || it == "${project.path}:uiTest" }
        if (split == null) {
            tasks.named("check") { dependsOn(uiTest) }
        } else {
            val part1 =
                registerUiTest("uiTestPart1", "Runs the UI tests of the classes matching $split.") {
                    includeTestsMatching(split)
                }
            val part2 =
                registerUiTest("uiTestPart2", "Runs the UI tests of the classes not matching $split.") {
                    excludeTestsMatching(split)
                }
            if (!uiTestExcluded) tasks.named("check") { dependsOn(part1, part2) }
        }
    }
}

/**
 * Mutation testing with pitest for every Kotlin module outside `:tools`, driven by `-Psluide.pitest.*` properties
 * (`classes`, `tests`, `lines`, `ui`, `displays`, `threads`). The mutators, the report and the plugin
 * `tools/pitest-kotlin` (junk filter, allow-list of lines) are fixed here; `pitest` is no part of `check`. UI runs
 * (`ui=true`) put every worker JVM on a display of its own through `.claude/scripts/pitest-java.sh`.
 */
fun Project.configurePitest() {
    apply(plugin = "info.solidsoft.pitest")
    val libs = rootProject.libs
    dependencies {
        add("pitest", project(":tools:pitest-kotlin"))
    }

    fun list(name: String) =
        providers
            .gradleProperty(
                "sluide.pitest.$name",
            ).map { it.split(",").map(String::trim).filter(String::isNotEmpty) }
    val ui = providers.gradleProperty("sluide.pitest.ui").map(String::toBoolean).orElse(false)
    val displays = list("displays").orElse(emptyList())
    val classes = list("classes")
    val lines = providers.gradleProperty("sluide.pitest.lines")
    val buildDirectory = layout.buildDirectory
    val toolchainJava =
        extensions
            .getByType<JavaToolchainService>()
            .launcherFor(extensions.getByType<JavaPluginExtension>().toolchain)
            .map { it.executablePath.asFile.absolutePath }
    val unitThreads = 4
    val testPackages = "dev.sluide.${project.name.replace("-", "")}.*"
    val alsaConfigPath = nullAlsaConfigPath.takeIf { project.path in nullAlsaProjects }
    val rootDirectory = rootProject.projectDir
    // The display locks are shared by the modules of one Gradle call (parallel UI runs must not lease one display).
    val rootBuildDirectory = rootProject.layout.buildDirectory
    val wrapper = rootProject.file(".claude/scripts/pitest-java.sh")

    extensions.configure<info.solidsoft.gradle.pitest.PitestPluginExtension> {
        pitestVersion =
            libs.versions.pitest
                .asProvider()
                .get()
        junit5PluginVersion =
            libs.versions.pitest.junit5
                .get()
        mutators =
            listOf(
                "DEFAULTS",
                "REMOVE_CONDITIONALS_EQUAL_IF",
                "REMOVE_CONDITIONALS_EQUAL_ELSE",
                "NON_VOID_METHOD_CALLS",
            )
        outputFormats = listOf("XML")
        timestampedReports = false
        skipFailingTests = false
        // pitest allows a test 1.25 times its normal time plus this constant. A mutant that makes a test fail only
        // after it waited (`eventually` 20 s, `waitFor` 10 s and 30 s) would count as a timeout, not a kill; the
        // longest wait of a test is 30 s.
        timeoutConstInMillis = 35_000
        targetClasses = classes.orElse(emptyList())
        targetTests = list("tests").orElse(listOf(testPackages))
        excludedGroups = ui.map { if (it) emptyList() else listOf("ui") }
        features =
            lines.map { listOf("+LINES(file[${rootDirectory.resolve(it).absolutePath}])") }.orElse(emptyList())
        threads =
            providers
                .gradleProperty("sluide.pitest.threads")
                .map(String::toInt)
                .orElse(ui.zip(displays) { isUi, ds -> if (isUi) ds.size.coerceAtLeast(1) else unitThreads })
        jvmArgs =
            ui.map { isUi ->
                listOf(
                    "--enable-native-access=ALL-UNNAMED",
                    // Without a limit a worker takes up to a quarter of the RAM: four per run grew to 1.5 GB each.
                    // Gradle's test tasks run the same tests in its default 512 MB.
                    "-Xmx768m",
                    "-Dsluide.log.dir=${buildDirectory.get().asFile}/test-logs",
                ) +
                    if (isUi) listOf("-Dprism.order=sw") else emptyList()
            }
        jvmPath = ui.flatMap { isUi -> if (isUi) provider { wrapper } else provider { null } }
    }

    tasks.named<JavaExec>("pitest") {
        // Only the path of the allow-list is an input, not its content, so a rewritten file would return the old
        // report as UP-TO-DATE or from the build cache.
        outputs.upToDateWhen { false }
        outputs.doNotCacheIf("The allow-list changes between runs under the same path") { true }
        // pitest makes one work unit per source file and one worker runs a unit, so a run on one file used one
        // worker (one display) only. Units of at most 10 mutants spread a file over the workers; the Gradle plugin
        // has no property for pitest's option.
        argumentProviders.add { listOf("--mutationUnitSize=10") }
        // The worker JVMs inherit this environment; pitest-java.sh reads it.
        if (alsaConfigPath != null) environment("ALSA_CONFIG_PATH", alsaConfigPath)
        environment("SLUIDE_PITEST_DISPLAYS", displays.get().joinToString(","))
        environment("SLUIDE_PITEST_JAVA", toolchainJava.get())
        environment(
            "SLUIDE_PITEST_LOCKS",
            rootBuildDirectory
                .dir("pitest")
                .get()
                .asFile.absolutePath,
        )
        doFirst {
            if (!classes.isPresent) {
                throw GradleException(
                    "pitest needs -Psluide.pitest.classes=<comma list of class patterns>",
                )
            }
            if (ui.get() && displays.get().isEmpty()) {
                throw GradleException(
                    "pitest with -Psluide.pitest.ui=true needs -Psluide.pitest.displays=<comma list of display numbers>",
                )
            }
            buildDirectory
                .dir("pitest")
                .get()
                .asFile
                .mkdirs()
        }
    }
}

/** `-Dsluide.log.dir` for test JVMs, internal so that checkouts in other folders share cached test results. */
class LogFolderArgument(
    @get:Internal val folder: Provider<Directory>,
) : CommandLineArgumentProvider {
    override fun asArguments() = listOf("-Dsluide.log.dir=${folder.get().asFile.path}")
}
