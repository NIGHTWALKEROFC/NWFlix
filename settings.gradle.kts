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
        // The prebuilt Telegram library (TDLib for Android) is published on JitPack, not Maven Central.
        maven {
            url = uri("https://jitpack.io")
            content { includeGroup("com.github.tdlibx") }
        }
    }
}
rootProject.name = "NWMovie"
include(":app")
