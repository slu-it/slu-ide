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
}

dependencies {
    implementation(libs.spring.boot.starter)
    implementation(project(":modules:file-explorer"))
    implementation(project(":modules:file-viewers"))
    implementation(project(":modules:editor"))
    implementation(project(":modules:file-watcher"))
    implementation(project(":modules:terminal"))
    implementation(project(":modules:settings"))
    implementation(project(":modules:docker-sandbox"))
    implementation(project(":modules:git"))
    implementation(project(":modules:git-log"))
    implementation(project(":modules:http-client"))
    implementation(project(":modules:projects"))
    implementation(project(":modules:project-trust"))

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
    inputs.files(jpackageInput, appModules)
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
