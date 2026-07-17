dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        val flowWeftRepositoryUrl = providers.gradleProperty("fileweftRepositoryUrl")
            .orElse(rootDir.resolve("../../build/repository").toURI().toString())
        exclusiveContent {
            forRepository {
                maven {
                    name = "flowWeftReleaseGradleMetadata"
                    url = uri(flowWeftRepositoryUrl.get())
                    // Intentionally use Gradle's defaults. This lane must consume .module metadata.
                }
            }
            filter {
                includeGroup("ai.icen")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "flowweft-gradle-module-consumer"
