pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        val fileWeftRepositoryUrl = providers.gradleProperty("fileweftRepositoryUrl")
            .orElse(rootDir.resolve("../build/repository").toURI().toString())
        exclusiveContent {
            forRepository {
                maven {
                    name = "fileWeftReleasePomOnly"
                    url = uri(fileWeftRepositoryUrl.get())
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
