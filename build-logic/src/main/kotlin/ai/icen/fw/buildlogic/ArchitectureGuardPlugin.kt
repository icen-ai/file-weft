package ai.icen.fw.buildlogic

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
                "ai.icen.fw.core.", "java.", "javax.", "kotlin.",
            ),
            "fileweft-spi" to listOf(
                "ai.icen.fw.core.", "ai.icen.fw.spi.", "java.", "javax.", "kotlin.",
            ),
            "flowweft-retrieval-api" to listOf(
                "ai.icen.fw.retrieval.api.", "ai.icen.fw.core.", "ai.icen.fw.spi.",
                "java.", "javax.", "kotlin.",
            ),
            "flowweft-retrieval-spi" to listOf(
                "ai.icen.fw.retrieval.spi.", "ai.icen.fw.retrieval.api.", "ai.icen.fw.core.",
                "java.", "javax.", "kotlin.",
            ),
            "flowweft-agent-api" to listOf(
                "ai.icen.fw.agent.api.", "ai.icen.fw.core.", "java.", "javax.", "kotlin.",
            ),
            "flowweft-agent-runtime" to listOf(
                "ai.icen.fw.agent.runtime.", "ai.icen.fw.agent.api.", "ai.icen.fw.core.",
                "java.", "javax.", "kotlin.",
            ),
            "flowweft-workflow-api" to listOf(
                "ai.icen.fw.workflow.api.", "java.", "javax.", "kotlin.",
            ),
            "flowweft-workflow-spi" to listOf(
                "ai.icen.fw.workflow.spi.", "ai.icen.fw.workflow.api.",
                "java.", "javax.", "kotlin.",
            ),
            "flowweft-workflow-domain" to listOf(
                "ai.icen.fw.workflow.domain.", "ai.icen.fw.workflow.api.",
                "java.", "javax.", "kotlin.",
            ),
            "flowweft-workflow-runtime" to listOf(
                "ai.icen.fw.workflow.runtime.", "ai.icen.fw.workflow.domain.", "ai.icen.fw.workflow.api.",
                "ai.icen.fw.workflow.spi.",
                "java.", "javax.", "kotlin.",
            ),
            "fileweft-domain" to listOf(
                "ai.icen.fw.core.", "ai.icen.fw.domain.", "java.", "javax.", "kotlin.",
            ),
            "fileweft-application" to listOf(
                "ai.icen.fw.application.", "ai.icen.fw.core.", "ai.icen.fw.domain.", "ai.icen.fw.spi.",
                "ai.icen.fw.metadata.api.",
                "java.", "javax.", "kotlin.",
            ),
            "fileweft-metadata-api" to listOf(
                "ai.icen.fw.metadata.api.", "java.", "kotlin.",
            ),
            "fileweft-metadata-runtime" to listOf(
                "ai.icen.fw.metadata.api.", "ai.icen.fw.metadata.runtime.",
                "com.google.re2j.", "java.", "kotlin.",
            ),
            "fileweft-web-api" to listOf(
                "ai.icen.fw.web.api.", "java.", "kotlin.",
            ),
            "fileweft-web-runtime" to listOf(
                "ai.icen.fw.web.runtime.", "ai.icen.fw.web.api.", "ai.icen.fw.application.",
                "ai.icen.fw.core.", "ai.icen.fw.domain.", "ai.icen.fw.metadata.api.", "java.", "kotlin.",
            ),
            "fileweft-web-spring-boot2-starter" to listOf(
                "ai.icen.fw.web.spring.boot2.", "ai.icen.fw.web.api.", "ai.icen.fw.web.runtime.",
                "ai.icen.fw.application.", "ai.icen.fw.core.", "ai.icen.fw.spi.",
                "java.", "kotlin.", "org.springframework.",
            ),
            "fileweft-web-spring-boot3-starter" to listOf(
                "ai.icen.fw.web.spring.boot3.", "ai.icen.fw.web.api.", "ai.icen.fw.web.runtime.",
                "ai.icen.fw.application.", "ai.icen.fw.core.", "ai.icen.fw.spi.",
                "java.", "kotlin.", "org.springframework.",
            ),
        )

        // Spring is an outer adapter concern.  Keep the global infrastructure deny-list intact and
        // grant this one exception only to the MVC starter modules that deliberately own that boundary.
        val allowedInfrastructurePrefixesByModule = mapOf(
            "fileweft-web-spring-boot2-starter" to listOf("org.springframework."),
            "fileweft-web-spring-boot3-starter" to listOf("org.springframework."),
        )

        val allowedAutoConfigurationAfterNameReferencesByModule = mapOf(
            "fileweft-web-spring-boot2-starter" to listOf("ai.icen.fw.starter.boot2.FileWeftAutoConfiguration"),
            "fileweft-web-spring-boot3-starter" to listOf("ai.icen.fw.starter.boot3.FileWeftAutoConfiguration"),
        )

        val verifyTask = project.tasks.register(
            "verifyFileWeftArchitecture",
            VerifyFileWeftArchitectureTask::class.java,
        ) {
            group = "verification"
            description = "Verifies FlowWeft module boundaries and forbidden infrastructure imports."
            this.approvedImportPrefixesByModule.putAll(approvedImportPrefixesByModule)
            this.allowedInfrastructurePrefixesByModule.putAll(allowedInfrastructurePrefixesByModule)
            this.allowedAutoConfigurationAfterNameReferencesByModule.putAll(
                allowedAutoConfigurationAfterNameReferencesByModule,
            )
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
                    "flowweft-retrieval-api" to listOf(
                        "suspend fun", "value class", "sealed interface", "data object",
                    ),
                    "flowweft-retrieval-spi" to listOf(
                        "suspend fun", "value class", "sealed interface", "data object",
                    ),
                    "flowweft-agent-api" to listOf(
                        "suspend fun", "value class", "sealed interface", "data object",
                    ),
                    "flowweft-agent-runtime" to listOf(
                        "suspend fun", "value class", "sealed interface", "data object",
                    ),
                    "flowweft-workflow-api" to listOf(
                        "suspend fun", "value class", "sealed interface", "data object",
                    ),
                    "flowweft-workflow-spi" to listOf(
                        "suspend fun", "value class", "sealed interface", "data object",
                    ),
                    "flowweft-workflow-domain" to listOf(
                        "suspend fun", "value class", "sealed interface", "data object",
                    ),
                    "flowweft-workflow-runtime" to listOf(
                        "suspend fun", "value class", "sealed interface", "data object",
                    ),
                    "fileweft-metadata-api" to listOf("suspend fun", "value class", "sealed interface", "data object"),
                    "fileweft-metadata-runtime" to listOf("suspend fun", "value class", "sealed interface", "data object"),
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
            repositoryDirectory.set(project.layout.projectDirectory)
            namespaceFiles.from(
                project.fileTree(project.projectDir) {
                    include(
                        "**/*.kt", "**/*.java", "**/*.kts", "**/*.gradle",
                        "**/*.properties", "**/*.factories", "**/*.imports",
                        "**/*.yaml", "**/*.yml", "**/*.xml", "**/*.json", "**/*.toml",
                        "**/*.html", "**/*.js", "**/*.ts", "**/*.tsx", "**/*.css",
                        "**/*.ps1", "**/*.sh", "**/*.bat", "**/Dockerfile*",
                        "**/META-INF/services/*",
                    )
                    exclude(
                        ".ai/**", ".git/**", ".gradle/**", ".idea/**", ".kotlin/**",
                        "**/build/**", "**/node_modules/**",
                    )
                },
            )
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
