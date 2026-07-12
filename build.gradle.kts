import groovy.json.JsonSlurper
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Delete
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.jvm.tasks.Jar
import org.gradle.api.tasks.bundling.Zip
import java.util.Locale
import java.util.zip.ZipFile

plugins {
    id("fileweft.architecture-guard")
    id("org.cyclonedx.bom") version "3.2.4"
    id("fileweft.sbom-verification")
}

group = "ai.icen"
version = "0.0.2-SNAPSHOT"

val publishableModuleNames = setOf(
    "fileweft-core",
    "fileweft-spi",
    "fileweft-domain",
    "fileweft-application",
    "fileweft-web-api",
    "fileweft-web-runtime",
    "fileweft-web-spring-boot2-starter",
    "fileweft-web-spring-boot3-starter",
    "fileweft-persistence",
    "fileweft-runtime",
    "fileweft-spring-boot2-starter",
    "fileweft-spring-boot3-starter",
    "fileweft-adapter",
    "fileweft-adapter-micrometer",
    "fileweft-adapter-s3",
    "fileweft-agent",
    "fileweft-testkit",
)
val releaseRepositoryDirectory = layout.buildDirectory.dir("repository")
val releaseVersion = version.toString()
val releaseNotesFile = layout.projectDirectory.file(
    "docs/releases/${releaseVersion.removeSuffix("-SNAPSHOT")}.md",
)
val cnbArtifactsPassword = providers.environmentVariable("CNB_TOKEN")
    .orElse(providers.gradleProperty("cnbArtifactsGradlePassword"))
val cnbPublishingRequested = gradle.startParameter.taskNames.any { requestedTask ->
    val taskName = requestedTask.substringAfterLast(':')
    taskName == "publishCnbArtifacts" ||
        taskName.endsWith("CnbArtifactsRepository")
}
@Suppress("DEPRECATION")
val configurationCacheRequested = gradle.startParameter.isConfigurationCacheRequested

if (
    cnbPublishingRequested &&
    configurationCacheRequested
) {
    throw GradleException("CNB publishing credentials require --no-configuration-cache.")
}
if (cnbPublishingRequested && releaseVersion.endsWith("-SNAPSHOT")) {
    throw GradleException("CNB release publishing requires a non-SNAPSHOT project version.")
}

allprojects {
    group = rootProject.group
    version = rootProject.version
}

val verifyFileWeftBuildLogic = tasks.register("verifyFileWeftBuildLogic") {
    group = "verification"
    description = "Runs the included build-logic verification suite."
    dependsOn(gradle.includedBuild("build-logic").task(":check"))
}

val compatibilityCheck = tasks.register("compatibilityCheck") {
    group = "verification"
    description = "Runs the supported Java runtime matrices for all FileWeft modules."
}

val publishReleaseRepository = tasks.register("publishReleaseRepository") {
    group = "publishing"
    description = "Publishes every public FileWeft $releaseVersion module to build/repository."
}

val verifyPublishedStarterConfigurationMetadata = tasks.register("verifyPublishedStarterConfigurationMetadata") {
    group = "verification"
    description = "Verifies Spring configuration metadata in both published runtime Starter JARs."
    dependsOn(publishReleaseRepository)
    inputs.dir(releaseRepositoryDirectory)
        .withPropertyName("releaseRepository")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("releaseRepositoryPath", releaseRepositoryDirectory.map { directory -> directory.asFile.absolutePath })
    inputs.property("releaseVersion", releaseVersion)

    doLast {
        val expectedTypes = linkedMapOf(
            "fileweft.default-tenant-enabled" to "java.lang.Boolean",
            "fileweft.default-tenant-id" to "java.lang.String",
            "fileweft.storage.local-enabled" to "java.lang.Boolean",
            "fileweft.storage.local-root" to "java.lang.String",
        )
        val booleanProperties = setOf(
            "fileweft.default-tenant-enabled",
            "fileweft.storage.local-enabled",
        )
        val descriptionRequirements = mapOf(
            "fileweft.default-tenant-enabled" to listOf(
                "fixed single-tenant",
                "TenantProvider",
                "production multi-tenant",
            ),
            "fileweft.default-tenant-id" to listOf(
                "fileweft.default-tenant-enabled",
                "configured explicitly",
            ),
            "fileweft.storage.local-enabled" to listOf(
                "process-local filesystem",
                "StorageAdapter",
                "durable production storage",
            ),
            "fileweft.storage.local-root" to listOf(
                "fileweft.storage.local-enabled",
                "configured explicitly",
            ),
        )
        val starterModules = listOf(
            "fileweft-spring-boot2-starter",
            "fileweft-spring-boot3-starter",
        )
        val metadataPath = "META-INF/spring-configuration-metadata.json"
        val repositoryRoot = java.io.File(inputs.properties.getValue("releaseRepositoryPath").toString())
        val publishedVersion = inputs.properties.getValue("releaseVersion").toString()
        val starterProjections = linkedMapOf<String, Map<String, List<Any?>>>()

        starterModules.forEach { moduleName ->
            val versionDirectory = repositoryRoot.resolve("ai/icen/$moduleName/$publishedVersion")
            val binaryJars = versionDirectory.listFiles()
                .orEmpty()
                .filter { candidate ->
                    candidate.isFile &&
                        candidate.extension == "jar" &&
                        !candidate.name.endsWith("-sources.jar") &&
                        !candidate.name.endsWith("-javadoc.jar")
                }
            require(binaryJars.size == 1) {
                "Expected exactly one published binary JAR for $moduleName in ${versionDirectory.absolutePath}, " +
                    "but found ${binaryJars.map { it.name }}."
            }

            val properties = ZipFile(binaryJars.single()).use { archive ->
                val metadataEntries = archive.entries().asSequence()
                    .filter { entry -> entry.name == metadataPath }
                    .toList()
                require(metadataEntries.size == 1) {
                    "Expected exactly one $metadataPath entry in ${binaryJars.single().absolutePath}, " +
                        "but found ${metadataEntries.size}."
                }
                val document = archive.getInputStream(metadataEntries.single()).use { input ->
                    JsonSlurper().parse(input) as? Map<*, *>
                        ?: error("$metadataPath in ${binaryJars.single().absolutePath} is not a JSON object.")
                }
                val propertyNodes = document["properties"] as? List<*>
                    ?: error("$metadataPath in ${binaryJars.single().absolutePath} has no properties array.")
                propertyNodes.mapIndexed { index, node ->
                    node as? Map<*, *>
                        ?: error("Property $index in $metadataPath for $moduleName is not a JSON object.")
                }
            }

            val projection = linkedMapOf<String, List<Any?>>()
            expectedTypes.forEach { (propertyName, expectedType) ->
                val matches = properties.filter { property -> property["name"] == propertyName }
                require(matches.size == 1) {
                    "Expected exactly one $propertyName entry in $moduleName metadata, but found ${matches.size}."
                }
                val property = matches.single()
                require(property["type"] == expectedType) {
                    "$propertyName in $moduleName must have type $expectedType, but was ${property["type"]}."
                }
                if (propertyName in booleanProperties) {
                    require(property.containsKey("defaultValue") && property["defaultValue"] == false) {
                        "$propertyName in $moduleName must declare the JSON boolean defaultValue false."
                    }
                } else {
                    require(!property.containsKey("defaultValue")) {
                        "$propertyName in $moduleName must not declare an implicit defaultValue."
                    }
                }

                val description = property["description"] as? String
                    ?: error("$propertyName in $moduleName must declare a description.")
                require(description.isNotBlank()) {
                    "$propertyName in $moduleName must declare a non-blank description."
                }
                val normalizedDescription = description.lowercase(Locale.ROOT)
                descriptionRequirements.getValue(propertyName).forEach { requiredText ->
                    require(normalizedDescription.contains(requiredText.lowercase(Locale.ROOT))) {
                        "$propertyName in $moduleName metadata description must explain '$requiredText'."
                    }
                }
                projection[propertyName] = listOf(
                    property["type"],
                    property.containsKey("defaultValue"),
                    property["defaultValue"],
                    description,
                )
            }
            starterProjections[moduleName] = projection
        }

        require(starterProjections.values.distinct().size == 1) {
            "Boot 2 and Boot 3 Starter configuration metadata must have identical public property projections: " +
                starterProjections
        }
    }
}

val cleanReleaseRepository = tasks.register<Delete>("cleanReleaseRepository") {
    group = "publishing"
    description = "Removes stale coordinates before publishing the local release repository."
    delete(releaseRepositoryDirectory)
}

val installReleaseToMavenLocal = tasks.register("installReleaseToMavenLocal") {
    group = "publishing"
    description = "Installs every public FileWeft $releaseVersion module into Maven local."
}

val releaseConsumerSmoke = tasks.register<Exec>("releaseConsumerSmoke") {
    group = "verification"
    description = "Compiles independent Java and Kotlin consumers from the generated Maven POMs."
    dependsOn(publishReleaseRepository)
    workingDir(rootProject.projectDir)
    val wrapper = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        rootProject.file("gradlew.bat").absolutePath
    } else {
        rootProject.file("gradlew").absolutePath
    }
    commandLine(
        wrapper,
        "-p",
        rootProject.file("release-smoke").absolutePath,
        "clean",
        "build",
        "-PfileweftVersion=$releaseVersion",
        "--no-daemon",
        "--no-configuration-cache",
    )
}

val publishCnbArtifacts = tasks.register("publishCnbArtifacts") {
    group = "publishing"
    description = "Publishes every public FileWeft $releaseVersion module to the configured CNB Maven registry."
    doFirst {
        require(cnbArtifactsPassword.isPresent) {
            "Set CNB_TOKEN or cnbArtifactsGradlePassword before publishing CNB artifacts."
        }
    }
}

subprojects {
    val moduleCompatibilityTaskPath = "$path:compatibilityTest"
    dependencyLocking {
        lockAllConfigurations()
    }

    tasks.matching { task -> task.name == "check" }.configureEach {
        dependsOn(rootProject.tasks.named("verifyFileWeftBuildLogic"))
    }

    listOf("fileweft.jvm8-library", "fileweft.jvm17-library").forEach { conventionPluginId ->
        pluginManager.withPlugin(conventionPluginId) {
            rootProject.tasks.named("compatibilityCheck").configure {
                dependsOn(moduleCompatibilityTaskPath)
            }
        }
    }

    if (name in publishableModuleNames) {
        pluginManager.apply("maven-publish")
        pluginManager.withPlugin("java") {
            extensions.configure<PublishingExtension> {
                publications.create<MavenPublication>("mavenJava") {
                    from(components["java"])
                    artifactId = project.name
                    pom {
                        name.set(project.name)
                        description.set("FileWeft enterprise file infrastructure module ${project.name}.")
                    }
                }
                repositories.maven {
                    name = "FileWeftRelease"
                    url = rootProject.layout.buildDirectory.dir("repository").get().asFile.toURI()
                }
                if (cnbPublishingRequested && cnbArtifactsPassword.isPresent) {
                    repositories.maven {
                        name = "CnbArtifacts"
                        url = uri("https://maven.cnb.cool/china.ai/maven/-/packages/")
                        credentials {
                            username = "cnb"
                            password = cnbArtifactsPassword.get()
                        }
                    }
                }
            }
            tasks.named<Jar>("jar") {
                manifest.attributes(
                    "Implementation-Title" to project.name,
                    "Implementation-Version" to project.version.toString(),
                )
            }
        }
    }
}

val releaseBundle = tasks.register<Zip>("releaseBundle") {
    group = "distribution"
    description = "Builds the shareable FileWeft $releaseVersion Maven repository and SBOM bundle."
    dependsOn(publishReleaseRepository, verifyPublishedStarterConfigurationMetadata, "verifySbom")
    archiveBaseName.set("fileweft")
    archiveVersion.set(version.toString())
    archiveClassifier.set("release")
    destinationDirectory.set(layout.buildDirectory.dir("release"))
    from(releaseRepositoryDirectory) {
        into("repository")
    }
    from(layout.buildDirectory.dir("reports/cyclonedx")) {
        include("bom.json", "bom.xml")
        into("sbom")
    }
    if (releaseNotesFile.asFile.isFile) {
        from(releaseNotesFile) {
            rename { "RELEASE_NOTES.md" }
        }
    }
    from("README.md")
}

val releaseCheck = tasks.register("releaseCheck") {
    group = "verification"
    description = "Runs all FileWeft $releaseVersion release gates and creates the shareable release bundle."
    dependsOn(compatibilityCheck, "verifySbom", releaseBundle, releaseConsumerSmoke)
}

// A runtime matrix can otherwise start dozens of separate JVMs at once when
// Gradle parallel execution is enabled. Keep these heavyweight cross-JDK test
// processes globally ordered so `compatibilityCheck` remains reliable on a
// developer machine that is also running the Dev Compose stack.
gradle.projectsEvaluated {
    val runtimeMatrixTasks = allprojects
        .flatMap { project ->
            project.tasks.withType(Test::class.java)
                .matching { task -> task.name.matches(Regex("java(?:8|11|17|21|25)Test")) }
                .toList()
        }
        .sortedBy { task -> "${task.project.path}:${task.name}" }
    runtimeMatrixTasks.zipWithNext().forEach { (previous, next) ->
        next.mustRunAfter(previous)
    }

    val publishedProjects = subprojects.filter { project -> project.name in publishableModuleNames }
    val localReleasePublishTasks = publishedProjects.map { project ->
        project.tasks.named("publishMavenJavaPublicationToFileWeftReleaseRepository")
    }
    localReleasePublishTasks.forEach { taskProvider ->
        taskProvider.configure {
            dependsOn(cleanReleaseRepository)
        }
    }
    publishReleaseRepository.configure {
        dependsOn(localReleasePublishTasks)
    }
    installReleaseToMavenLocal.configure {
        dependsOn(
            publishedProjects.map { project ->
                project.tasks.named("publishMavenJavaPublicationToMavenLocal")
            },
        )
    }
    if (cnbPublishingRequested && cnbArtifactsPassword.isPresent) {
        val cnbPublishTasks = publishedProjects.map { project ->
            project.tasks.named("publishMavenJavaPublicationToCnbArtifactsRepository")
        }
        cnbPublishTasks.forEach { taskProvider ->
            taskProvider.configure {
                dependsOn(releaseCheck)
                notCompatibleWithConfigurationCache("CNB credentials must never be stored in the configuration cache.")
            }
        }
        publishCnbArtifacts.configure {
            dependsOn(cnbPublishTasks)
            notCompatibleWithConfigurationCache("CNB credentials must never be stored in the configuration cache.")
        }
    }
    releaseCheck.configure {
        dependsOn(
            subprojects.map { project -> project.tasks.named("check") },
        )
    }
}
