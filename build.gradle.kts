plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.javafx) apply false
    alias(libs.plugins.spotless)
}

// `./gradlew spotlessApply` formats, `spotlessCheck` (part of `check`/`build`) verifies. Rules: .editorconfig
// Targets name the source folders only. With `**/*.kt` plus a `build/` exclude, spotlessKotlin failed twice under
// parallel execution with "Could not read path …/build/…" while other tasks wrote build/; naming the source folders
// avoids that walk. A new top-level source folder (e.g. buildSrc/) must be added to the targets.
spotless {
    kotlin {
        target("ide/src/**/*.kt", "modules/*/src/**/*.kt")
        ktlint(libs.versions.ktlint.get())
    }
    kotlinGradle {
        target("*.gradle.kts", "ide/*.gradle.kts", "modules/*/*.gradle.kts")
        ktlint(libs.versions.ktlint.get())
    }
}

allprojects {
    group = "dev.sluide"
    // CI passes -PreleaseVersion=<x.y.z> when building from a release tag.
    version = providers.gradleProperty("releaseVersion").getOrElse("0.1.0-SNAPSHOT")
}

/**
 * Lets one `uiTest` task at a time use the display: TestFX tests of parallel modules on one display took each other's
 * window focus, which closed their popups (auto-hide follows the owner window's focus) and failed tests at random.
 */
abstract class DisplayService : BuildService<BuildServiceParameters.None>

val display = gradle.sharedServices.registerIfAbsent("display", DisplayService::class) { maxParallelUsages.set(1) }

// Shared configuration for every Kotlin module (modules/* and ide).
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
            add("testRuntimeOnly", rootProject.libs.junit.platform.launcher)
        }

        // Plain unit tests never need a display. UI tests (JUnit tag "ui", TestFX) run in `uiTest`, part of `check`.
        // Linux without DISPLAY (e.g. a headless sandbox) skips them unless CI is set; start a virtual display
        // with .claude/scripts/setup-display.sh to run them there.
        tasks.named<Test>("test") {
            useJUnitPlatform { excludeTags("ui") }
        }

        // With `-q` Gradle would only say that tests failed; this names them with their exception, passed ones stay
        // silent.
        tasks.withType<Test>().configureEach {
            testLogging {
                quiet {
                    events(org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED)
                    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                }
            }
        }

        val testSourceSet = extensions.getByType<SourceSetContainer>().named("test")
        val displayAvailable =
            providers.environmentVariable("DISPLAY").isPresent ||
                providers.environmentVariable("CI").isPresent ||
                !System.getProperty("os.name").lowercase().contains("linux")

        val uiTest =
            tasks.register<Test>("uiTest") {
                description = "Runs TestFX UI tests (JUnit tag 'ui'); needs a display."
                group = "verification"
                testClassesDirs = testSourceSet.get().output.classesDirs
                classpath = testSourceSet.get().runtimeClasspath
                useJUnitPlatform { includeTags("ui") }
                // Software rendering is the most reliable pipeline on virtual displays.
                systemProperty("prism.order", "sw")
                // JavaFX loads its native libraries from the class path; without this, every test JVM warns about it.
                jvmArgs("--enable-native-access=ALL-UNNAMED")
                onlyIf("a display is available") { displayAvailable }
                usesService(display)
                shouldRunAfter(tasks.named("test"))
            }
        tasks.named("check") { dependsOn(uiTest) }
    }
}
