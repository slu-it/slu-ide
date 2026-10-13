plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.javafx)
    `maven-publish`
}

javafx {
    version = libs.versions.javafx.get()
    modules("javafx.controls")
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

    testImplementation(libs.testfx.core)
    testImplementation(libs.testfx.junit5)
}

// The fat jar contains the JavaFX natives of the build platform only (see docs/adr/0003), so every
// platform is published under its own artifactId: ide-linux-x64, ide-linux-aarch64, ide-mac-aarch64, ...
val platform: String =
    run {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        val osName =
            when {
                "linux" in os -> "linux"
                "mac" in os -> "mac"
                else -> error("Unsupported operating system for publishing: $os")
            }
        val archName = if (arch == "aarch64" || arch == "arm64") "aarch64" else "x64"
        "$osName-$archName"
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
