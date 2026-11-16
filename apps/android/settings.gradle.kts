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
    }
}

rootProject.name = "AbodeNestly"

include(":app", ":contract", ":shared", ":ui", ":core", ":sync", ":shopping", ":pantry", ":maintenance")
