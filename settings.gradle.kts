pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // libsu, the root shell library, is published through JitPack.
        maven(url = "https://jitpack.io")
    }
}

rootProject.name = "lsposed-modules-manager"
include(":app")
