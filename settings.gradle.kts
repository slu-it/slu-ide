rootProject.name = "slu-ide"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        // JediTerm is only published to JetBrains' repository (ADR 0007); nothing else is taken from there.
        exclusiveContent {
            forRepository {
                maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
            }
            filter { includeGroup("org.jetbrains.jediterm") }
        }
    }
}

include("modules:file-explorer")
include("modules:file-viewers")
include("modules:editor")
include("modules:file-watcher")
include("modules:terminal")
include("modules:settings")
include("modules:docker-sandbox")
include("ide")
