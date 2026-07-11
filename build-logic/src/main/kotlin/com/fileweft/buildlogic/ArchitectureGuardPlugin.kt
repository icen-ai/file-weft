package com.fileweft.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project

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
        )

        val verifyTask = project.tasks.register(
            "verifyFileWeftArchitecture",
            VerifyFileWeftArchitectureTask::class.java,
        ) {
            group = "verification"
            description = "Verifies FileWeft module boundaries and forbidden infrastructure imports."
            this.approvedImportPrefixesByModule.putAll(approvedImportPrefixesByModule)
            forbiddenInfrastructurePrefixes.set(
                listOf("java.sql.", "javax.sql.", "javax.persistence.", "jakarta.persistence."),
            )
            forbiddenKotlinSyntaxByModule.putAll(
                mapOf(
                    "fileweft-core" to listOf("suspend fun", "value class", "sealed interface", "data object"),
                    "fileweft-spi" to listOf("suspend fun", "value class", "sealed interface", "data object"),
                ),
            )
            sourceRoots.from(approvedImportPrefixesByModule.keys.map { module ->
                project.layout.projectDirectory.dir("$module/src/main/kotlin")
            })
        }

        project.subprojects.forEach { subproject ->
            subproject.tasks.matching { task -> task.name == "check" }.configureEach {
                dependsOn(verifyTask)
            }
        }
    }
}
