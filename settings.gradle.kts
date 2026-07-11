pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "fileweft"

includeBuild("build-logic")

include(
    ":fileweft-core",
    ":fileweft-spi",
    ":fileweft-domain",
    ":fileweft-application",
    ":fileweft-web-api",
    ":fileweft-web-runtime",
    ":fileweft-web-spring-boot2-starter",
    ":fileweft-web-spring-boot3-starter",
    ":fileweft-persistence",
    ":fileweft-runtime",
    ":fileweft-spring-boot2-starter",
    ":fileweft-spring-boot3-starter",
    ":fileweft-adapter",
    ":fileweft-adapter-micrometer",
    ":fileweft-adapter-s3",
    ":fileweft-agent",
    ":fileweft-testkit",
    ":fileweft-dev",
)
