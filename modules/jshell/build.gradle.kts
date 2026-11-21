import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.jvm.JvmTargetValidationMode
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.javafx)
    `java-library`
}

javafx {
    // Maven Central publishes no Gradle metadata for JavaFX, so the plugin picks the platform jars (ADR 0003).
    version = libs.versions.javafx.get()
    modules("javafx.controls")
}

dependencies {
    // Deliberate module API dependency: JShellSession.start takes the Jdk of the Java family's Jdks, and JShellJdks
    // resolves the project's JDK with JavaHomes and Jdks.hasJShell (ADR 0179, ADR 0191).
    api(project(":modules:java"))
    // Deliberate module API dependency: JShellJdks keeps a file's chosen JDK in its project data (field `jshell`) and
    // takes the ProjectDataStore (ADR 0090, ADR 0191).
    api(project(":modules:project-data"))
    // Deliberate module dependency: the session's reader and stderr threads are logged NamedThreads (ADR 0060).
    implementation(project(":modules:background-tasks"))
    // Deliberate module API dependency: JShellFileViewer is a public SourceFileViewer, ExtensionFileViewer showing a
    // CodeEditor beside the transcript (ADR 0191, as shell-scripts, ADR 0089).
    api(project(":modules:file-viewers"))
    // The toolbar's icons: Material Design Icons, like the shell scripts' (ADR 0012).
    implementation(libs.ikonli.javafx)
    implementation(libs.ikonli.materialdesign2)
    // JShellFileViewer is a Spring @Component.
    implementation(libs.spring.context)
    // The agent protocol's JSON lines on Thoreau's side; the agent itself has its own small codec (ADR 0191). Version
    // from the Spring Boot BOM.
    implementation(libs.jackson.databind)
    // The transcript pane (package ui) is a read-only RichTextFX area, like the code views (ADR 0005).
    implementation(libs.richtextfx)

    // Generic test helpers: LogCapture (ADR 0186).
    testImplementation(project(":modules:test-support"))
    testImplementation(libs.testfx.core)
    testImplementation(libs.testfx.junit5)
}

// The agent (package dev.sluide.jshell.agent) runs on the JDK the user picks, JDK 9 or newer (ADR 0191). Its sources
// stay in src/main/kotlin, so that the unit tests and the mutation runs see them; the module jar leaves those classes
// out. A second compilation builds them for Java 9 (`-Xjdk-release=9` also keeps newer JDK API out, which a low
// jvmTarget alone let through) into agent.jar, which the module jar carries with kotlin-stdlib as resources.
val agentPackage = "dev/sluide/jshell/agent/**"

val agent: SourceSet = sourceSets.create("agent")
kotlin.sourceSets.named("agent") {
    kotlin.srcDir("src/main/kotlin")
    kotlin.include(agentPackage)
}

tasks.named<KotlinJvmCompile>("compileAgentKotlin") {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_9)
        freeCompilerArgs.add("-Xjdk-release=9")
    }
    // The empty Java side of the source set keeps the toolchain's target; only Kotlin compiles here.
    jvmTargetValidationMode.set(JvmTargetValidationMode.IGNORE)
}

val agentJar =
    tasks.register<Jar>("agentJar") {
        from(agent.output)
        archiveFileName.set("agent.jar")
        destinationDirectory.set(layout.buildDirectory.dir("agent"))
        // The same classes give the same bytes, so the installed folder (named by the content hash) stays the same.
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

val agentStdlib: Configuration =
    configurations.create("agentStdlib") {
        isTransitive = false
        isCanBeConsumed = false
    }

dependencies {
    agentStdlib("org.jetbrains.kotlin:kotlin-stdlib:${libs.versions.kotlin.get()}")
}

val agentResources =
    tasks.register<Sync>("agentResources") {
        into(layout.buildDirectory.dir("generated/agent-resources"))
        into("jshell-agent") {
            from(agentJar)
            from(agentStdlib) { rename { "kotlin-stdlib.jar" } }
        }
    }

sourceSets.main {
    resources.srcDir(agentResources)
}

tasks.jar {
    exclude(agentPackage)
}

tasks.test {
    // The real-JDK test runs only with SLUIDE_TEST_JSHELL (JDK homes): a set variable means "check these JDKs now", so
    // the task is never up to date then (a cached run would keep the skipped test).
    inputs.property("sluideTestJshell", providers.environmentVariable("SLUIDE_TEST_JSHELL").orElse(""))
    outputs.upToDateWhen { System.getenv("SLUIDE_TEST_JSHELL") == null }
}
