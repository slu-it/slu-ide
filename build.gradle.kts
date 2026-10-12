plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.javafx) apply false
}

allprojects {
    group = "dev.sluide"
    version = "0.1.0-SNAPSHOT"
}

// Shared configuration for every Kotlin module (modules/* and ide).
subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val javaVersion = rootProject.libs.versions.java.get().toInt()

        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(javaVersion)
        }

        dependencies {
            // Versions of Spring and its transitive libraries are managed by the Spring Boot BOM.
            add("implementation", platform(rootProject.libs.spring.boot.dependencies))
            add("testImplementation", rootProject.libs.kotlin.test.junit5)
            add("testRuntimeOnly", rootProject.libs.junit.platform.launcher)
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
    }
}
