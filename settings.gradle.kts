rootProject.name = "GraalMail"

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

include(
    ":core",
    ":security",
    ":provider-api",
    ":storage",
    ":sync",
    ":provider-imap",
    ":provider-gmail",
    ":provider-microsoft",
    ":provider-yahoo",
    ":provider-proton",
    ":testing",
    ":desktop",
    ":android",
)
