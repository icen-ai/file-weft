package com.fileweft.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency

class ArchitectureGuardPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        check(project == project.rootProject) {
            "fileweft.architecture-guard must be applied to the root project."
        }

        val approvedImportPrefixesByModule = mapOf(
            "fileweft-core" to listOf(
                "com.fileweft.core.", "java.", "javax.", "kotlin.",
            ),
            "fileweft-spi" to listOf(
                "com.fileweft.core.", "com.fileweft.spi.", "java.", "javax.", "kotlin.",
            ),
            "fileweft-domain" to listOf(
                "com.fileweft.core.", "com.fileweft.domain.", "java.", "javax.", "kotlin.",
            ),
            "fileweft-application" to listOf(
                "com.fileweft.application.", "com.fileweft.core.", "com.fileweft.domain.", "com.fileweft.spi.",
                "java.", "javax.", "kotlin.",
            ),
            "fileweft-web-api" to listOf(
                "com.fileweft.web.api.", "java.", "kotlin.",
            ),
            "fileweft-web-runtime" to listOf(
                "com.fileweft.web.runtime.", "com.fileweft.web.api.", "com.fileweft.application.",
                "com.fileweft.core.", "com.fileweft.domain.", "java.", "kotlin.",
            ),
        )

        val verifyTask = project.tasks.register(
            "verifyFileWeftArchitecture",
            VerifyFileWeftArchitectureTask::class.java,
        ) {
            group = "verification"
            description = "Verifies FileWeft module boundaries and forbidden infrastructure imports."
            this.approvedImportPrefixesByModule.putAll(approvedImportPrefixesByModule)
            forbiddenInfrastructurePrefixes.set(
                listOf(
                    "java.sql.", "javax.sql.", "javax.persistence.", "jakarta.persistence.",
                    "javax.servlet.", "jakarta.servlet.", "javax.ws.rs.", "jakarta.ws.rs.", "org.springframework.",
                ),
            )
            forbiddenKotlinSyntaxByModule.putAll(
                mapOf(
                    "fileweft-core" to listOf("suspend fun", "value class", "sealed interface", "data object"),
                    "fileweft-spi" to listOf("suspend fun", "value class", "sealed interface", "data object"),
                    "fileweft-web-api" to listOf("suspend fun", "value class", "sealed interface", "data object"),
                    "fileweft-web-runtime" to listOf("suspend fun", "value class", "sealed interface", "data object"),
                ),
            )
            sourceRoots.from(approvedImportPrefixesByModule.keys.flatMap { module ->
                listOf(
                    project.layout.projectDirectory.dir("$module/src/main/kotlin"),
                    project.layout.projectDirectory.dir("$module/src/main/java"),
                )
            })
        }

        val verifyWebApiDependencies = project.tasks.register(
            "verifyFileWeftWebApiDependencies",
            VerifyWebApiDependenciesTask::class.java,
        ) {
            group = "verification"
            description = "Verifies that fileweft-web-api has no runtime or HTTP framework dependency."
        }

        project.subprojects.forEach { subproject ->
            subproject.tasks.matching { task -> task.name == "check" }.configureEach {
                dependsOn(verifyTask)
                dependsOn(verifyWebApiDependencies)
            }
        }

        project.gradle.projectsEvaluated {
            val webApi = project.findProject(":fileweft-web-api") ?: return@projectsEvaluated
            val productionConfigurations = setOf("api", "implementation", "compileOnly", "runtimeOnly")
            val dependencies = webApi.configurations
                .filter { configuration -> configuration.name in productionConfigurations }
                .flatMap { configuration -> configuration.dependencies }
                .map { dependency ->
                    if (dependency is ProjectDependency) {
                        "project:${dependency.path}"
                    } else {
                        listOfNotNull(dependency.group, dependency.name, dependency.version).joinToString(":")
                    }
                }
                .distinct()
                .sorted()
            verifyWebApiDependencies.configure {
                declaredProductionDependencies.set(dependencies)
            }
        }
    }
}
