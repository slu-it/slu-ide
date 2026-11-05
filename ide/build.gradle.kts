plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.javafx)
    `maven-publish`
}

javafx {
    version = libs.versions.javafx.get()
    modules("javafx.controls", "javafx.web")
}

springBoot {
    mainClass = "dev.sluide.ide.SluIdeLauncher"
    // META-INF/build-info.properties with the version for the window title; without the time, so it stays cacheable.
    buildInfo { excludes = setOf("time") }
}

dependencies {
    implementation(libs.spring.boot.starter)
    implementation(project(":modules:file-explorer"))
    implementation(project(":modules:file-viewers"))
    // Needed only for the Spring beans: the previews are FileViewers found by component scan (ADR 0069).
    implementation(project(":modules:previews"))
    implementation(project(":modules:editor-area"))
    implementation(project(":modules:file-watcher"))
    implementation(project(":modules:terminal"))
    implementation(project(":modules:settings"))
    implementation(project(":modules:docker-sandbox"))
    implementation(project(":modules:git"))
    implementation(project(":modules:git-log"))
    implementation(project(":modules:http-client"))
    implementation(project(":modules:projects"))
    implementation(project(":modules:project-trust"))
    // MainWindow's fixtures and the explorer wiring use the ProjectDataStore (ADR 0090).
    implementation(project(":modules:project-data"))
    // The window's background tasks, shown under the file explorer (ADR 0061).
    implementation(project(":modules:background-tasks"))
    // The shared scratch files in every window's explorer and editor (ADR 0066).
    implementation(project(":modules:scratches"))
    // Deliberate module dependency: `ide` implements the workflow graph's `RemoteSources` over the git module's remote
    // source cache and registers it as a window service (ADR 0076).
    implementation(project(":modules:github-actions"))
    // CommandRunSession is a window bean, created per window; MainWindow registers it as a window service for the views
    // that run commands, and the window tests create it (ADR 0088).
    implementation(project(":modules:command-runs"))
    // The Gradle view of build files and its window session (ADR 0085).
    implementation(project(":modules:gradle"))
    // Needed only for the Spring bean: ShellScriptFileViewer is a FileViewer found by component scan (ADR 0089).
    implementation(project(":modules:shell-scripts"))
    // The TODO panel at the right of every window; TodoPanel is a window bean, Todos a singleton (ADR 0102).
    implementation(project(":modules:todos"))
    // The Coloring tool: a tab opened from the Tools menu, one per window (ADR 0109, ADR 0110).
    implementation(project(":modules:coloring"))
    // The Codecs tool: a tab opened from the Tools menu, one per window (ADR 0110, ADR 0112).
    implementation(project(":modules:codecs"))
    // The Converting tool: a tab opened from the Tools menu, one per window (ADR 0110, ADR 0115).
    implementation(project(":modules:converting"))
    // Docker through its CLI: the window's DockerSession and the Docker settings page (ADR 0117).
    implementation(project(":modules:docker"))
    // The Databases tab of the Services menu: JDBC connections, consoles, results and the object tree (ADR 0121).
    implementation(project(":modules:databases"))
    // The Help menu's Capture Diagnostics and the JFR ring buffer started with the app (ADR 0127).
    implementation(project(":modules:diagnostics"))
    // Audio and video files play in a file view with JavaFX Media; MediaPreferences and PlaybackCoordinator are
    // singletons (ADR 0123).
    implementation(project(":modules:media"))
    // The Read Aloud tool: a tab opened from the Tools menu, one per window; KokoroEngine and its settings page are found
    // by component scan (ADR 0124).
    implementation(project(":modules:speech"))
    implementation(project(":modules:translation"))
    // Saved secrets: values encrypted with one key in the OS secret store; Secrets is a singleton (ADR 0122).
    implementation(project(":modules:secrets"))
    // The explorer's Run item has a Material Design play icon, like the HTTP client's Run (ADR 0012).
    implementation(libs.ikonli.javafx)
    implementation(libs.ikonli.materialdesign2)

    testImplementation(libs.testfx.core)
    testImplementation(libs.testfx.junit5)
    // File views are RichTextFX code areas; the tests read their text.
    testImplementation(libs.richtextfx)
}

// The fat jar contains the JavaFX natives of the build platform only (see docs/adr/0003), so every
// platform is published under its own artifactId: ide-linux-x64, ide-linux-aarch64, ide-mac-aarch64. Other systems
// (e.g. Windows) are not released (see ADR 0003), but can still configure and build locally (not tested); they get
// the first word of their OS name.
val platform: String =
    run {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        val osName =
            when {
                "linux" in os -> "linux"
                "mac" in os -> "mac"
                else -> os.substringBefore(' ')
            }
        val archName = if (arch == "aarch64" || arch == "arm64") "aarch64" else "x64"
        "$osName-$archName"
    }

// The ONNX Runtime jar of the speech module holds the natives of all platforms (about 50 MB); like the JavaFX natives,
// the fat jar keeps only the build platform's folder (ADR 0124). The module's own tests keep using the full jar. Other
// platforms (e.g. Windows) keep all natives.
val onnxRuntime =
    configurations.create("onnxRuntime") {
        isCanBeConsumed = false
        isTransitive = false
    }

dependencies { onnxRuntime(libs.onnxruntime) }

val onnxJars: FileCollection = onnxRuntime.incoming.files

val onnxNativeFolder: String? =
    mapOf("linux-x64" to "linux-x64", "linux-aarch64" to "linux-aarch64", "mac-aarch64" to "osx-aarch64")[platform]

if (onnxNativeFolder != null) {
    val onnxRuntimeForPlatform =
        tasks.register<Jar>("onnxRuntimeForPlatform") {
            description = "The ONNX Runtime jar with the natives of the build platform only."
            val jars = onnxJars
            // The original file name, so that the fat jar lists the same library.
            archiveFileName = provider { jars.singleFile.name }
            destinationDirectory = layout.buildDirectory.dir("onnxruntime")
            from({ zipTree(jars.singleFile) }) {
                // Natives of other platforms, and the debug symbols (.dSYM) of the macOS ones.
                exclude("ai/onnxruntime/native/*/**")
                exclude("META-INF/MANIFEST.MF")
            }
            from({ zipTree(jars.singleFile) }) {
                include("ai/onnxruntime/native/$onnxNativeFolder/**")
                exclude("**/*.dSYM/**")
            }
        }
    tasks.bootJar {
        val original = onnxJars.files
        val onNativeClasspath =
            configurations.runtimeClasspath
                .get()
                .files
                .any { it in original }
        check(onNativeClasspath) { "onnxruntime is not on the runtime classpath as ${original.map { it.name }}" }
        val bootClasspath = classpath ?: files()
        setClasspath(files(bootClasspath.filter { file -> file !in original }, onnxRuntimeForPlatform))
    }
}

// Lingua's jar holds five n-gram model files per language (about 80 MB); the translation module detects in
// low-accuracy mode, which reads the uni-, bi- and trigrams only (5.6 MB), so the fat jar drops the quadri- and
// fivegrams (ADR 0126). The module's own tests keep using the full jar.
val lingua =
    configurations.create("lingua") {
        isCanBeConsumed = false
        isTransitive = false
    }

dependencies { lingua(libs.lingua) }

val linguaJars: FileCollection = lingua.incoming.files

val linguaSmallModels =
    tasks.register<Jar>("linguaSmallModels") {
        description = "Lingua's jar without the quadrigram and fivegram models."
        val jars = linguaJars
        // The original file name, so that the fat jar lists the same library.
        archiveFileName = provider { jars.singleFile.name }
        destinationDirectory = layout.buildDirectory.dir("lingua")
        from({ zipTree(jars.singleFile) }) {
            exclude("language-models/*/quadrigrams.json", "language-models/*/fivegrams.json")
            exclude("META-INF/MANIFEST.MF")
        }
    }

tasks.bootJar {
    val original = linguaJars.files
    val onClasspath =
        configurations.runtimeClasspath
            .get()
            .files
            .any { it in original }
    check(onClasspath) { "lingua is not on the runtime classpath as ${original.map { it.name }}" }
    val bootClasspath = classpath ?: files()
    setClasspath(files(bootClasspath.filter { file -> file !in original }, linguaSmallModels))
}

// Native app with its own Java runtime (docs/adr/0011-native-app-packaging.md): `jpackageImage` builds Thoreau.app on
// macOS (a launcher folder on Linux), `jpackageDmg` a disk image with it (macOS only). jpackage cannot cross-build,
// so like the fat jar it is made on each platform for that platform.
val jdkHome =
    javaToolchains
        .launcherFor { languageVersion = JavaLanguageVersion.of(libs.versions.java.get()) }
        .map { it.metadata.installationPath }
val isMac = "mac" in System.getProperty("os.name").lowercase()

// The JDK modules the code needs, so the bundled runtime contains only those. jdeps prints them on the last line.
val appModulesFile = layout.buildDirectory.file("jpackage/modules.txt")
val appModules =
    tasks.register<Exec>("appModules") {
        description = "Lists the JDK modules the application needs (jdeps)."
        val jars = files(tasks.jar, configurations.runtimeClasspath)
        val javaVersion = libs.versions.java.get()
        inputs.files(jars)
        outputs.file(appModulesFile)
        executable(jdkHome.get().file("bin/jdeps").asFile)
        argumentProviders.add {
            listOf(
                "--ignore-missing-deps",
                "--print-module-deps",
                "--multi-release",
                javaVersion,
                "--class-path",
                jars.asPath,
            ) + jars.map { it.path }
        }
        val output = appModulesFile
        doFirst { standardOutput = output.get().asFile.outputStream() }
    }

val jpackageInput =
    tasks.register<Sync>("jpackageInput") {
        from(tasks.bootJar)
        into(layout.buildDirectory.dir("jpackage/input"))
    }

// macOS requires the first number of the app version to be at least 1; before 1.0 jpackage's default 1.0 is used there.
val appVersion = version.toString().substringBefore('-').takeUnless { isMac && it.startsWith("0.") }

fun registerJpackage(
    name: String,
    type: String,
    taskDescription: String,
) = tasks.register<Exec>(name) {
    description = taskDescription
    group = "distribution"
    val destination = layout.buildDirectory.dir("jpackage/$type")
    val mainJar = tasks.bootJar.flatMap { it.archiveFileName }
    val modules = appModulesFile
    val input = jpackageInput.map { it.destinationDir }
    val version = appVersion
    val mac = isMac
    // The logo, rendered from packaging/thoreau.svg by packaging/render-icon.sh: .icns on macOS, PNG on Linux.
    val icon = layout.projectDirectory.file(if (mac) "packaging/Thoreau.icns" else "packaging/thoreau-512.png")
    inputs.files(jpackageInput, appModules, icon)
    outputs.dir(destination)
    executable(jdkHome.get().file("bin/jpackage").asFile)
    argumentProviders.add {
        buildList {
            addAll(listOf("--type", type, "--name", "Thoreau", "--dest", destination.get().asFile.path))
            addAll(listOf("--input", input.get().path, "--main-jar", mainJar.get()))
            // Added to what jdeps finds: modules only loaded at runtime (charsets, zip file systems).
            val extra = listOf("jdk.unsupported", "jdk.charsets", "jdk.zipfs")
            val found =
                modules
                    .get()
                    .asFile
                    .readLines()
                    .last(String::isNotBlank)
                    .split(",")
            addAll(listOf("--add-modules", (found + extra).distinct().joinToString(",")))
            // jpackage's defaults plus compression: about 60 MB instead of 95 MB (linux-aarch64, JDK 25; ADR 0011).
            val jlinkOptions = "--strip-debug --no-man-pages --no-header-files --strip-native-commands --compress zip-6"
            addAll(listOf("--jlink-options", jlinkOptions))
            addAll(listOf("--vendor", "Thoreau", "--description", "A lightweight IDE"))
            addAll(listOf("--icon", icon.asFile.path))
            version?.let { addAll(listOf("--app-version", it)) }
            if (mac) addAll(listOf("--mac-package-identifier", "dev.sluide.ide", "--mac-package-name", "Thoreau"))
        }
    }
    // jpackage refuses to overwrite an existing app or image.
    doFirst { destination.get().asFile.deleteRecursively() }
}

registerJpackage(
    "jpackageImage",
    "app-image",
    "Builds the native app with its own Java runtime (Thoreau.app on macOS).",
)
registerJpackage("jpackageDmg", "dmg", "Builds a macOS disk image with Thoreau.app.").configure {
    val mac = isMac
    onlyIf("running on macOS") { mac }
}

publishing {
    publications {
        create<MavenPublication>("fatJar") {
            artifactId = "ide-$platform"
            artifact(tasks.bootJar)
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/slu-it/slu-ide")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
