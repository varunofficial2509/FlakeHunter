pluginManagement {
    includeBuild("build-logic")
}

plugins {
    // Downloads the Java 21 toolchain when no matching local JDK is found.
    // Settings plugins cannot read the version catalog, so this version is pinned here.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "flakehunter"

include(
    "services:api-gateway",
    "services:project-service",
    "services:hunt-orchestrator",
    "services:result-aggregator",
    "runner:junit-extension",
)
