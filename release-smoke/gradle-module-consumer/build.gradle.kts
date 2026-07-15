import org.gradle.api.JavaVersion
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.DocsType
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.java.TargetJvmVersion
import org.gradle.api.tasks.compile.JavaCompile
import java.util.zip.ZipFile

plugins {
    `java-library`
}

val flowWeftVersion = providers.gradleProperty("fileweftVersion").orNull
    ?: throw GradleException("-PfileweftVersion is required for the Gradle module metadata consumer.")
val expectedPublishedFlowWeftModules = sortedSetOf(
    "flowweft-retrieval-api",
    "flowweft-retrieval-spi",
    "flowweft-retrieval-runtime",
    "flowweft-retrieval-testkit",
    "flowweft-agent-api",
    "flowweft-agent-runtime",
    "flowweft-agent-testkit",
    "flowweft-workflow-api",
    "flowweft-workflow-spi",
    "flowweft-workflow-domain",
    "flowweft-workflow-runtime",
    "flowweft-workflow-persistence-jdbc",
    "flowweft-migration-cli",
    "flowweft-adapter-dify",
    "flowweft-adapter-oss",
)
val representativeClassEntries = mapOf(
    "flowweft-retrieval-api" to "ai/icen/fw/retrieval/api/RetrievalAuthorizationPlanner.class",
    "flowweft-retrieval-spi" to "ai/icen/fw/retrieval/spi/EmbeddingProvider.class",
    "flowweft-retrieval-runtime" to
        "ai/icen/fw/retrieval/runtime/RetrievalRuntimeConfiguration.class",
    "flowweft-retrieval-testkit" to
        "ai/icen/fw/testkit/retrieval/CandidateRetrieverContractTest.class",
    "flowweft-agent-api" to "ai/icen/fw/agent/api/AgentRunService.class",
    "flowweft-agent-runtime" to "ai/icen/fw/agent/runtime/DurableAgentRunCoordinator.class",
    "flowweft-agent-testkit" to
        "ai/icen/fw/testkit/agent/LanguageModelProviderContractTest.class",
    "flowweft-workflow-api" to "ai/icen/fw/workflow/api/WorkflowParticipantResolver.class",
    "flowweft-workflow-spi" to "ai/icen/fw/workflow/spi/WorkflowDecisionProvider.class",
    "flowweft-workflow-domain" to "ai/icen/fw/workflow/domain/WorkflowDomainEngine.class",
    "flowweft-workflow-runtime" to "ai/icen/fw/workflow/runtime/WorkflowDurableRuntime.class",
    "flowweft-workflow-persistence-jdbc" to
        "ai/icen/fw/workflow/persistence/jdbc/JdbcWorkflowRuntimePersistence.class",
    "flowweft-migration-cli" to "ai/icen/fw/migration/cli/FlowWeftMigrationExitCode.class",
    "flowweft-adapter-dify" to "ai/icen/fw/adapter/dify/DifyKnowledgeBaseConnector.class",
    "flowweft-adapter-oss" to "ai/icen/fw/adapter/oss/OssStorageAdapter.class",
)
data class MetadataPublicationInventoryEntry(
    val artifactId: String,
    val artifactKind: String,
    val lineage: String,
    val jvmBaseline: Int,
)
val publicationInventoryEntries = providers.gradleProperty("publicationInventoryEntries")
    .orNull
    ?.split(',')
    ?.filter(String::isNotEmpty)
    ?.map { encodedEntry ->
        val fields = encodedEntry.split('|')
        require(fields.size == 4) { "Invalid publication inventory metadata entry '$encodedEntry'." }
        val jvmBaseline = fields[3].toIntOrNull()
        require(jvmBaseline != null) { "Invalid JVM baseline in publication inventory entry '$encodedEntry'." }
        MetadataPublicationInventoryEntry(fields[0], fields[1], fields[2], jvmBaseline)
    }
    ?: throw GradleException("-PpublicationInventoryEntries is required for the Gradle module metadata consumer.")
val publicationEntriesByModule = publicationInventoryEntries.associateBy { entry -> entry.artifactId }
require(publicationEntriesByModule.size == publicationInventoryEntries.size) {
    "Gradle metadata inventory contains duplicate artifact IDs."
}
val publishedFlowWeftModules = publicationInventoryEntries
    .filter { entry -> entry.artifactKind == "jar" && entry.lineage == "new-physical" }
    .map { entry -> entry.artifactId }
    .toSortedSet()
require(publishedFlowWeftModules == expectedPublishedFlowWeftModules) {
    "Gradle metadata inventory differs from the expected new physical module set; " +
        "missing=${expectedPublishedFlowWeftModules - publishedFlowWeftModules}, " +
        "unexpected=${publishedFlowWeftModules - expectedPublishedFlowWeftModules}."
}
val knownApiInternalDependencies = mapOf(
    "flowweft-retrieval-api" to setOf("fileweft-core", "fileweft-spi"),
    "flowweft-retrieval-spi" to setOf("flowweft-retrieval-api"),
    "flowweft-retrieval-runtime" to setOf("flowweft-retrieval-spi"),
    "flowweft-retrieval-testkit" to setOf("flowweft-retrieval-api", "flowweft-retrieval-spi"),
    "flowweft-agent-api" to setOf("fileweft-core"),
    "flowweft-agent-runtime" to setOf("flowweft-agent-api"),
    "flowweft-agent-testkit" to setOf("flowweft-agent-api"),
    "flowweft-workflow-api" to emptySet(),
    "flowweft-workflow-spi" to setOf("flowweft-workflow-api"),
    "flowweft-workflow-domain" to setOf("flowweft-workflow-api"),
    "flowweft-workflow-runtime" to setOf("flowweft-workflow-domain", "flowweft-workflow-spi"),
    "flowweft-workflow-persistence-jdbc" to setOf("flowweft-workflow-runtime"),
    "flowweft-migration-cli" to emptySet(),
    "flowweft-adapter-dify" to setOf("fileweft-spi"),
    "flowweft-adapter-oss" to setOf("fileweft-spi"),
)
val expectedApiInternalDependencies = knownApiInternalDependencies
    .filterKeys(publishedFlowWeftModules::contains)
val expectedRuntimeInternalDependencies = expectedApiInternalDependencies + mapOf(
    "flowweft-migration-cli" to setOf("fileweft-persistence", "flowweft-workflow-persistence-jdbc"),
    "flowweft-adapter-dify" to setOf("fileweft-core", "fileweft-spi"),
    "flowweft-adapter-oss" to setOf("fileweft-core", "fileweft-spi"),
)
val expectedApiResolvedInternalModules =
    (publishedFlowWeftModules + expectedApiInternalDependencies.values.flatten()).toSortedSet()
val expectedRuntimeResolvedInternalModules = (
    expectedApiResolvedInternalModules +
        setOf("fileweft-application", "fileweft-domain", "fileweft-metadata-api", "fileweft-persistence")
).toSortedSet()
val publishedProviderTestKitModules = sortedSetOf(
    "flowweft-agent-testkit",
    "flowweft-retrieval-testkit",
)

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}
tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
    options.encoding = "UTF-8"
}
configurations.configureEach {
    // Local SNAPSHOT metadata must be fresh, while immutable external modules keep Gradle's normal cache.
    resolutionStrategy.cacheChangingModulesFor(0, "seconds")
}

dependencies {
    publishedFlowWeftModules.forEach { moduleName ->
        implementation("ai.icen:$moduleName:$flowWeftVersion")
    }
}

val flowWeftSourcesMetadata = configurations.create("flowWeftSourcesMetadata") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
    attributes {
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.DOCUMENTATION))
        attribute(DocsType.DOCS_TYPE_ATTRIBUTE, objects.named(DocsType.SOURCES))
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
    }
}
dependencies {
    publishedFlowWeftModules.forEach { moduleName ->
        add(flowWeftSourcesMetadata.name, "ai.icen:$moduleName:$flowWeftVersion")
    }
}

fun ResolvedArtifactResult.moduleIdentifier(): ModuleComponentIdentifier? =
    id.componentIdentifier as? ModuleComponentIdentifier

fun internalArtifacts(configuration: Configuration): Map<String, List<ResolvedArtifactResult>> =
    configuration.incoming.artifacts.artifacts
        .mapNotNull { artifact ->
            val component = artifact.moduleIdentifier()
            if (component?.group == "ai.icen" && component.version == flowWeftVersion) {
                component.module to artifact
            } else {
                null
            }
        }
        .groupBy({ (moduleName, _) -> moduleName }, { (_, artifact) -> artifact })

fun verifyBinaryVariants(
    configuration: Configuration,
    variantName: String,
    usageName: String,
    expectedModules: Set<String>,
) {
    val artifactsByModule = internalArtifacts(configuration)
    require(artifactsByModule.keys == expectedModules) {
        "$variantName internal module artifacts differ; " +
            "missing=${expectedModules - artifactsByModule.keys}, " +
            "unexpected=${artifactsByModule.keys - expectedModules}."
    }
    artifactsByModule.forEach { (moduleName, artifacts) ->
        require(artifacts.size == 1) {
            "$variantName must resolve exactly one binary artifact for ai.icen:$moduleName: $artifacts"
        }
        val artifact = artifacts.single()
        val attributes = artifact.variant.attributes
        require(artifact.variant.displayName.contains(variantName)) {
            "ai.icen:$moduleName did not expose the Gradle $variantName variant: ${artifact.variant.displayName}"
        }
        require(attributes.getAttribute(Category.CATEGORY_ATTRIBUTE)?.name == Category.LIBRARY)
        require(attributes.getAttribute(Usage.USAGE_ATTRIBUTE)?.name == usageName)
        require(attributes.getAttribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE)?.name == LibraryElements.JAR)
        require(attributes.getAttribute(Bundling.BUNDLING_ATTRIBUTE)?.name == Bundling.EXTERNAL)
        val expectedJvmBaseline = publicationEntriesByModule[moduleName]?.jvmBaseline
            ?: error("Gradle metadata resolved an internal module absent from the publication inventory: $moduleName")
        require(attributes.getAttribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE) == expectedJvmBaseline) {
            "ai.icen:$moduleName $variantName does not carry its JVM $expectedJvmBaseline module-metadata target."
        }
        require(artifact.file.isFile && artifact.file.length() > 0L && artifact.file.extension == "jar") {
            "ai.icen:$moduleName $variantName did not resolve a non-empty JAR: ${artifact.file}"
        }
        representativeClassEntries[moduleName]?.let { expectedClassEntry ->
            ZipFile(artifact.file).use { jar ->
                require(jar.getEntry(expectedClassEntry) != null) {
                    "ai.icen:$moduleName $variantName JAR does not own $expectedClassEntry: ${artifact.file}"
                }
            }
        }
    }
}

fun verifyDirectInternalDependencies(
    configuration: Configuration,
    variantName: String,
    expectedDependencies: Map<String, Set<String>>,
) {
    val componentsByModule = configuration.incoming.resolutionResult.allComponents
        .mapNotNull { component ->
            val identifier = component.id as? ModuleComponentIdentifier
            if (identifier?.group == "ai.icen" && identifier.version == flowWeftVersion) {
                identifier.module to component
            } else {
                null
            }
        }
        .toMap()
    expectedDependencies.forEach { (moduleName, expectedModuleDependencies) ->
        val component = componentsByModule[moduleName]
            ?: error("$variantName did not resolve ai.icen:$moduleName:$flowWeftVersion")
        val actualDependencies = component.dependencies
            .filterIsInstance<ResolvedDependencyResult>()
            .mapNotNull { dependency -> dependency.selected.id as? ModuleComponentIdentifier }
            .filter { dependency -> dependency.group == "ai.icen" && dependency.version == flowWeftVersion }
            .map { dependency -> dependency.module }
            .toSet()
        require(actualDependencies == expectedModuleDependencies) {
            "$variantName dependencies differ for ai.icen:$moduleName; " +
                "expected=$expectedModuleDependencies, actual=$actualDependencies."
        }
    }
}

val compileClasspath = configurations.named("compileClasspath")
val runtimeClasspath = configurations.named("runtimeClasspath")
tasks.register("verifyGradleModuleMetadata") {
    group = "verification"
    description = "Verifies native Gradle API/runtime/source variants and internal dependencies from .module metadata."
    dependsOn(tasks.named("compileJava"))
    inputs.files(compileClasspath, runtimeClasspath, flowWeftSourcesMetadata)

    doLast {
        val compileConfiguration = compileClasspath.get()
        val resolvedInternalModules = compileConfiguration.incoming.resolutionResult.allComponents
            .mapNotNull { component -> component.id as? ModuleComponentIdentifier }
            .filter { component -> component.group == "ai.icen" && component.version == flowWeftVersion }
            .map { component -> component.module }
            .toSet()
        require(resolvedInternalModules == expectedApiResolvedInternalModules) {
            "Gradle module metadata internal graph differs; " +
                "missing=${expectedApiResolvedInternalModules - resolvedInternalModules}, " +
                "unexpected=${resolvedInternalModules - expectedApiResolvedInternalModules}."
        }
        verifyDirectInternalDependencies(
            compileConfiguration,
            "apiElements",
            expectedApiInternalDependencies,
        )

        val declaredExternalDependencies = compileConfiguration.allDependencies
            .filter { dependency -> dependency.group != "ai.icen" }
            .map { dependency -> "${dependency.group}:${dependency.name}" }
            .toSet()
        require(declaredExternalDependencies.isEmpty()) {
            "The metadata consumer must not declare external compile dependencies directly: " +
                declaredExternalDependencies
        }
        publishedProviderTestKitModules.forEach { moduleName ->
            val component = compileConfiguration.incoming.resolutionResult.allComponents
                .firstOrNull { candidate ->
                    val identifier = candidate.id as? ModuleComponentIdentifier
                    identifier?.group == "ai.icen" &&
                        identifier.module == moduleName &&
                        identifier.version == flowWeftVersion
                }
                ?: error("Gradle module metadata did not resolve ai.icen:$moduleName:$flowWeftVersion")
            val directExternalDependencies = component.dependencies
                .filterIsInstance<ResolvedDependencyResult>()
                .mapNotNull { dependency -> dependency.selected.id as? ModuleComponentIdentifier }
                .filter { dependency -> dependency.group != "ai.icen" }
                .map { dependency -> "${dependency.group}:${dependency.module}" }
                .toSet()
            require("org.junit.jupiter:junit-jupiter" in directExternalDependencies) {
                "ai.icen:$moduleName must publish JUnit Jupiter as an API dependency; " +
                    "actual=$directExternalDependencies."
            }
        }
        val resolvedExternalModules = compileConfiguration.incoming.resolutionResult.allComponents
            .mapNotNull { component -> component.id as? ModuleComponentIdentifier }
            .filter { component -> component.group != "ai.icen" }
            .map { component -> "${component.group}:${component.module}" }
            .toSet()
        require("org.junit.jupiter:junit-jupiter-api" in resolvedExternalModules) {
            "The published provider TestKits must expose JUnit Jupiter API transitively to Java consumers."
        }

        val runtimeConfiguration = runtimeClasspath.get()
        val resolvedRuntimeInternalModules = runtimeConfiguration.incoming.resolutionResult.allComponents
            .mapNotNull { component -> component.id as? ModuleComponentIdentifier }
            .filter { component -> component.group == "ai.icen" && component.version == flowWeftVersion }
            .map { component -> component.module }
            .toSet()
        require(resolvedRuntimeInternalModules == expectedRuntimeResolvedInternalModules) {
            "Gradle runtime metadata internal graph differs; " +
                "missing=${expectedRuntimeResolvedInternalModules - resolvedRuntimeInternalModules}, " +
                "unexpected=${resolvedRuntimeInternalModules - expectedRuntimeResolvedInternalModules}."
        }
        verifyDirectInternalDependencies(
            runtimeConfiguration,
            "runtimeElements",
            expectedRuntimeInternalDependencies,
        )

        verifyBinaryVariants(
            compileConfiguration,
            "apiElements",
            Usage.JAVA_API,
            expectedApiResolvedInternalModules,
        )
        verifyBinaryVariants(
            runtimeConfiguration,
            "runtimeElements",
            Usage.JAVA_RUNTIME,
            expectedRuntimeResolvedInternalModules,
        )

        val sourceArtifacts = internalArtifacts(flowWeftSourcesMetadata)
        require(sourceArtifacts.keys == publishedFlowWeftModules) {
            "Gradle sources variants differ; missing=${publishedFlowWeftModules - sourceArtifacts.keys}, " +
                "unexpected=${sourceArtifacts.keys - publishedFlowWeftModules}."
        }
        sourceArtifacts.forEach { (moduleName, artifacts) ->
            require(artifacts.size == 1) {
                "sourcesElements must resolve exactly one artifact for ai.icen:$moduleName: $artifacts"
            }
            val artifact = artifacts.single()
            val attributes = artifact.variant.attributes
            require(artifact.variant.displayName.contains("sourcesElements")) {
                "ai.icen:$moduleName did not expose the Gradle sourcesElements variant: ${artifact.variant.displayName}"
            }
            require(attributes.getAttribute(Category.CATEGORY_ATTRIBUTE)?.name == Category.DOCUMENTATION)
            require(attributes.getAttribute(DocsType.DOCS_TYPE_ATTRIBUTE)?.name == DocsType.SOURCES)
            require(artifact.file.isFile && artifact.file.length() > 0L && artifact.file.name.endsWith("-sources.jar")) {
                "ai.icen:$moduleName sourcesElements did not resolve its non-empty sources JAR: ${artifact.file}"
            }
        }
    }
}
