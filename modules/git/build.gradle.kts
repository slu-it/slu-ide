plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.javafx)
    `java-library`
}

javafx {
    // Maven Central publishes no Gradle metadata for JavaFX, so the plugin picks the platform jars.
    version = libs.versions.javafx.get()
    modules("javafx.graphics")
}

dependencies {
    // Deliberate module API dependency: the status service runs on a logged thread (ADR 0060), and its public
    // constructor takes the window's BackgroundTasks (ADR 0061).
    api(project(":modules:background-tasks"))
    // Deliberate module API dependency: the .git folder is watched to refresh the status (ADR 0013).
    api(project(":modules:file-watcher"))
    // Deliberate module API dependency: git's tools run only in folders the user trusts (ADR 0053); GitTrustCheck is a
    // ProjectTrustCheck.
    api(project(":modules:project-trust"))
    // Deliberate module dependency: diffs decode file contents as the editor does, UTF-8 or Windows-1252 (ADR 0098).
    implementation(project(":modules:text-editing"))
    // Deliberate module dependency: a key's passphrase can be saved encrypted with Secrets and is listed in Settings ->
    // Secrets as a SavedSecretSource (ADR 0122, 0194); the beans' constructors are internal.
    implementation(project(":modules:secrets"))
    // Deliberate module dependency: the saved passphrases are kept in the shared data file (ADR 0101, 0194).
    implementation(project(":modules:project-data"))
    implementation(libs.spring.context)

    // Generic test helpers: LogCapture (ADR 0186).
    testImplementation(project(":modules:test-support"))
}

// The askpass helper (package dev.sluide.git.askpass) runs as a small JVM of its own on Thoreau's runtime when ssh asks
// a covered git call for a passphrase or a host key (ADR 0194). Its sources stay in src/main/kotlin, so that the unit
// tests and the mutation runs see them, and the module jar keeps them too: the server speaks the same protocol. A second
// compilation of only that package goes into askpass.jar, which the module jar carries with kotlin-stdlib as resources
// (as the JShell agent, ADR 0191).
val askpassPackage = "dev/sluide/git/askpass/**"

val askpass: SourceSet = sourceSets.create("askpass")
kotlin.sourceSets.named("askpass") {
    kotlin.srcDir("src/main/kotlin")
    kotlin.include(askpassPackage)
}

val askpassJar =
    tasks.register<Jar>("askpassJar") {
        from(askpass.output)
        archiveFileName.set("askpass.jar")
        destinationDirectory.set(layout.buildDirectory.dir("askpass"))
        // The same classes give the same bytes, so the installed folder (named by the content hash) stays the same.
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

val askpassStdlib: Configuration =
    configurations.create("askpassStdlib") {
        isTransitive = false
        isCanBeConsumed = false
    }

dependencies {
    askpassStdlib("org.jetbrains.kotlin:kotlin-stdlib:${libs.versions.kotlin.get()}")
}

val askpassResources =
    tasks.register<Sync>("askpassResources") {
        into(layout.buildDirectory.dir("generated/askpass-resources"))
        into("git-askpass") {
            from(askpassJar)
            from(askpassStdlib) { rename { "kotlin-stdlib.jar" } }
        }
    }

sourceSets.main {
    resources.srcDir(askpassResources)
}

tasks.named<Test>("test") {
    // The tests mostly wait on git processes, not on the CPU; forks isolate the global log sinks of the tests.
    maxParallelForks = 3
}
