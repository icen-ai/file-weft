pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    name = "fileWeftReleasePomOnly"
                    url = uri(rootDir.resolve("../build/repository"))
                    metadataSources {
                        // The smoke build deliberately ignores Gradle module metadata.
                        // Maven consumers only receive the scopes declared in the POM.
                        mavenPom()
                        artifact()
                    }
                }
            }
            filter {
                includeGroup("ai.icen")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "fileweft-release-consumer-smoke"

include(
    ":boot2-consumer",
    ":boot3-consumer",
    ":library-consumer",
)
