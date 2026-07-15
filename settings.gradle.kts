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

rootProject.name = "flowweft"

includeBuild("build-logic")

include(
    ":fileweft-core",
    ":fileweft-spi",
    ":fileweft-domain",
    ":fileweft-application",
    ":fileweft-metadata-api",
    ":fileweft-metadata-runtime",
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
    ":fileweft-adapter-opentelemetry",
    ":fileweft-adapter-s3",
    ":fileweft-agent",
    ":fileweft-testkit",
    ":flowweft-workflow-api",
    ":flowweft-workflow-spi",
    ":flowweft-workflow-domain",
    ":flowweft-workflow-runtime",
    ":flowweft-workflow-persistence-jdbc",
    ":flowweft-migration-cli",
    ":flowweft-agent-api",
    ":flowweft-agent-runtime",
    ":flowweft-retrieval-api",
    ":flowweft-retrieval-spi",
    ":flowweft-retrieval-runtime",
    ":flowweft-adapter-oss",
    ":fileweft-sample-host",
    ":fileweft-dev",
)
