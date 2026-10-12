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
    }
}

include("modules:file-explorer")
include("modules:file-viewers")
include("modules:editor")
include("modules:file-watcher")
include("ide")
