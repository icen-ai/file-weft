import groovy.json.JsonSlurper
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Delete
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.jvm.tasks.Jar
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.bundling.Zip
import ai.icen.fw.buildlogic.PublicationInventoryVerifier
import ai.icen.fw.buildlogic.VerifyPublicationInventoryTask
import ai.icen.fw.buildlogic.ReleaseSbomExtension
import ai.icen.fw.buildlogic.TestTaskConcurrencyService
import java.util.Locale
import java.util.zip.ZipFile
import org.cyclonedx.gradle.CyclonedxDirectTask

plugins {
    id("fileweft.architecture-guard")
    id("org.cyclonedx.bom") version "3.2.4"
    id("fileweft.sbom-verification")
}

group = "ai.icen"
version = providers.gradleProperty("fileweftVersion").orElse("1.0.0-SNAPSHOT").get()

val publicationInventoryFile = layout.projectDirectory.file("gradle/publication-inventory.tsv").asFile
val publicationInventory = PublicationInventoryVerifier.parse(publicationInventoryFile)
val publishableModuleNames = publicationInventory
    .filter { entry -> entry.artifactKind == PublicationInventoryVerifier.JAR_KIND }
    .map { entry -> entry.artifactId }
    .toSet()
val releaseSbomModuleNames = publishableModuleNames.sorted()
extensions.configure<ReleaseSbomExtension>("fileWeftReleaseSbom") {
    publishableModuleNames.set(releaseSbomModuleNames)
}
val releaseRepositoryDirectory = layout.buildDirectory.dir("repository")
val releaseVersion = version.toString()
val stableReleaseVersionPattern = Regex("[0-9]+\\.[0-9]+\\.[0-9]+")
val releaseScmTag = if (releaseVersion == "0.0.1") "v0.0.1-ai.icen" else "v$releaseVersion"
val projectHomepage = "https://cnb.cool/china.ai/file-weft"
val projectScmConnection = "scm:git:https://cnb.cool/china.ai/file-weft.git"
val apacheLicenseName = "Apache License, Version 2.0"
val apacheLicenseUrl = "https://www.apache.org/licenses/LICENSE-2.0.txt"
val withdrawnMavenGroup = listOf("com", "fileweft").joinToString(".")
val withdrawnJvmPath = listOf("com", "fileweft").joinToString("/")
val projectLicenseFile = layout.projectDirectory.file("LICENSE")
val projectNoticeFile = layout.projectDirectory.file("NOTICE")
val verifyPublicationInventory = tasks.register<VerifyPublicationInventoryTask>("verifyPublicationInventory") {
    group = "verification"
    description = "Verifies the single checked-in physical FlowWeft publication inventory."
    inventoryFile.set(layout.projectDirectory.file("gradle/publication-inventory.tsv"))
    moduleBuildFiles.from(
        publicationInventory.map { entry ->
            layout.projectDirectory.file("${entry.artifactId}/build.gradle.kts")
        },
    )
    repositoryDirectory.set(layout.projectDirectory)
}
val releaseNotesFile = layout.projectDirectory.file(
    "docs/releases/${releaseVersion.removeSuffix("-SNAPSHOT")}.md",
)
val cnbArtifactsPassword = providers.environmentVariable("CNB_TOKEN")
    .orElse(providers.gradleProperty("cnbArtifactsGradlePassword"))
val verifiedCnbPublishingRequested = gradle.startParameter.taskNames.any { requestedTask ->
    requestedTask.substringAfterLast(':') == "publishVerifiedCnbArtifacts"
}
val cnbPublishingRequested = gradle.startParameter.taskNames.any { requestedTask ->
    val taskName = requestedTask.substringAfterLast(':')
    taskName == "publishCnbArtifacts" ||
        taskName == "publishVerifiedCnbArtifacts" ||
        taskName.endsWith("CnbArtifactsRepository")
}
val fullReleaseVerificationRequested = gradle.startParameter.taskNames.any { requestedTask ->
    requestedTask.substringAfterLast(':') in setOf("releaseCheck", "releaseVerification")
} || (cnbPublishingRequested && !verifiedCnbPublishingRequested)
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
if (cnbPublishingRequested && !stableReleaseVersionPattern.matches(releaseVersion)) {
    throw GradleException(
        "CNB release publishing requires a stable numeric X.Y.Z version, but was '$releaseVersion'.",
    )
}

allprojects {
    group = rootProject.group
    version = rootProject.version
    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}

val maxParallelTestTasks = providers.gradleProperty("fileweft.test.maxParallelTasks")
    .orElse(providers.environmentVariable("FILEWEFT_TEST_MAX_PARALLEL_TASKS"))
    .map { rawValue ->
        rawValue.toIntOrNull()?.takeIf { value -> value > 0 }
            ?: throw GradleException("fileweft.test.maxParallelTasks must be a positive integer, but was '$rawValue'.")
    }
    .orElse(2)
val testTaskConcurrencyService = gradle.sharedServices.registerIfAbsent(
    "fileweftTestTaskConcurrency",
    TestTaskConcurrencyService::class.java,
) {
    maxParallelUsages.set(maxParallelTestTasks)
}

tasks.cyclonedxBom {
    componentGroup = rootProject.group.toString()
    componentName = rootProject.name
    componentVersion = rootProject.version.toString()
}

// A public SBOM describes what consumers run. Restrict every direct BOM to the
// production runtime graph and keep the non-published Dev proof application out
// of the aggregate entirely. GenerateReleaseSbomTask still performs a second
// reachability prune so a plugin regression cannot retain orphan Dev/Test nodes.
allprojects {
    tasks.withType<CyclonedxDirectTask>().configureEach {
        includeConfigs.set(listOf("runtimeClasspath"))
        includeBuildEnvironment.set(false)
        includeConfigs.disallowChanges()
        includeBuildEnvironment.disallowChanges()
    }
}
subprojects.filter { candidate -> candidate.name !in publishableModuleNames }.forEach { candidate ->
    candidate.tasks.withType<CyclonedxDirectTask>().configureEach {
        enabled = false
    }
}

val verifyFileWeftBuildLogic = tasks.register("verifyFileWeftBuildLogic") {
    group = "verification"
    description = "Runs the included build-logic verification suite."
    dependsOn(gradle.includedBuild("build-logic").task(":check"))
}

fun registerCompatibilityLane(javaVersion: Int) = tasks.register("compatibilityJava${javaVersion}Check") {
    group = "verification"
    description = "Runs FileWeft module tests assigned to the Java $javaVersion compatibility lane."
}

val compatibilityJava8Check = registerCompatibilityLane(8)
val compatibilityJava11Check = registerCompatibilityLane(11)
val compatibilityJava17Check = registerCompatibilityLane(17)
val compatibilityJava21Check = registerCompatibilityLane(21)
val compatibilityJava25Check = registerCompatibilityLane(25)
val compatibilityCheck = tasks.register("compatibilityCheck") {
    group = "verification"
    description = "Runs the supported Java runtime matrices for all FileWeft modules."
    dependsOn(
        compatibilityJava8Check,
        compatibilityJava11Check,
        compatibilityJava17Check,
        compatibilityJava21Check,
        compatibilityJava25Check,
    )
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
            "fileweft.persistence.migration-mode" to "ai.icen.fw.persistence.migration.FileWeftMigrationMode",
            "fileweft.persistence.schema" to "java.lang.String",
            "fileweft.persistence.create-schema" to "java.lang.Boolean",
            "fileweft.persistence.kingbase-flyway-compatibility-enabled" to "java.lang.Boolean",
        )
        val expectedDefaults = mapOf<String, Any>(
            "fileweft.default-tenant-enabled" to false,
            "fileweft.storage.local-enabled" to false,
            "fileweft.persistence.migration-mode" to "DISABLED",
            "fileweft.persistence.create-schema" to false,
            "fileweft.persistence.kingbase-flyway-compatibility-enabled" to true,
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
            "fileweft.persistence.migration-mode" to listOf(
                "DISABLED",
                "VALIDATE",
                "MIGRATE",
                "exactly one DataSource",
            ),
            "fileweft.persistence.schema" to listOf(
                "VALIDATE",
                "MIGRATE",
                "leading or trailing whitespace",
            ),
            "fileweft.persistence.create-schema" to listOf(
                "MIGRATE",
                "DISABLED",
                "VALIDATE",
            ),
            "fileweft.persistence.kingbase-flyway-compatibility-enabled" to listOf(
                "Spring Boot Flyway",
                "KingbaseES",
                "main DataSource",
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
                if (propertyName in expectedDefaults) {
                    val expectedDefault = expectedDefaults.getValue(propertyName)
                    require(property.containsKey("defaultValue") && property["defaultValue"] == expectedDefault) {
                        "$propertyName in $moduleName must declare defaultValue $expectedDefault."
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

val nestedGradleWrapperCommand = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
    listOf(rootProject.file("gradlew.bat").absolutePath)
} else {
    listOf("bash", rootProject.file("gradlew").absolutePath)
}

val releaseConsumerSmoke = tasks.register<Exec>("releaseConsumerSmoke") {
    group = "verification"
    description = "Compiles independent consumers and starts Boot 2/3 hosts from the generated Maven POMs."
    dependsOn(publishReleaseRepository)
    workingDir(rootProject.projectDir)
    commandLine(nestedGradleWrapperCommand + listOf(
        "-p",
        rootProject.file("release-smoke").absolutePath,
        "clean",
        "releaseSmoke",
        "-PfileweftVersion=$releaseVersion",
        "--no-daemon",
        "--no-configuration-cache",
        "--refresh-dependencies",
    ))
}

val verifyDocsSite = tasks.register<Exec>("verifyDocsSite") {
    group = "verification"
    description = "Runs the zero-dependency documentation site contract tests."
    workingDir(layout.projectDirectory.dir("flowweft-docs"))
    commandLine("node", "--test", "test/site.test.mjs")
    inputs.files(
        fileTree("flowweft-docs") {
            include("**/*.html", "**/*.css", "**/*.js", "**/*.mjs", "**/*.json", "**/*.md")
        },
    ).withPropertyName("docsSiteSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

val verifyCnbPathPolicy = tasks.register<Exec>("verifyCnbPathPolicy") {
    group = "verification"
    description = "Verifies representative changed paths select the intended CNB verification lanes."
    workingDir(rootProject.projectDir)
    commandLine("node", "--test", ".ci/test/path-policy.test.mjs")
    inputs.files(
        rootProject.file(".cnb.yml"),
        rootProject.file(".cnb/settings.yml"),
        rootProject.file(".cnb/web_trigger.yml"),
        rootProject.file(".ci/.shared.yml"),
        rootProject.file(".ci/code-knowledge-acceptance.json"),
        rootProject.file(".ci/codewiki-sparse-checkout"),
        rootProject.file(".ci/codewiki.yml"),
        rootProject.file(".ci/knowledge.yml"),
        rootProject.file(".ci/main.yml"),
        rootProject.file(".ci/pr.yml"),
        rootProject.file(".ci/release.yml"),
        rootProject.file(".ci/scripts/prepare-kingbase-image.ps1"),
        rootProject.file(".ci/scripts/prepare-kingbase-image.sh"),
        rootProject.file(".ci/scripts/codewiki-evidence.mjs"),
        rootProject.file(".ci/scripts/verify-codewiki-knowledge.mjs"),
        rootProject.file(".ci/test/path-policy.test.mjs"),
        rootProject.file(".docker/docker-compose.dev.yaml"),
    ).withPropertyName("cnbPathPolicySources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

val fileWeftMigrationDialects = listOf("postgres", "mysql", "kingbase")
val expectedFileWeftMigrationResources = listOf(
    "V001__create_file_document_outbox.sql",
    "V002__harden_outbox_processing.sql",
    "V003__create_sync_record.sql",
    "V004__create_audit_record.sql",
    "V005__create_workflow_tables.sql",
    "V006__add_audit_operator_name.sql",
    "V007__create_document_delivery_target.sql",
    "V008__create_background_task.sql",
    "V009__create_doctor_record.sql",
    "V010__create_operation_log.sql",
    "V011__add_outbox_trace_context.sql",
    "V012__create_agent_result.sql",
    "V013__create_resumable_upload_session.sql",
    "V014__add_document_delivery_removal_state.sql",
    "V015__add_delivery_generation_for_controlled_republication.sql",
    "V016__harden_sync_and_task_query_indexes.sql",
    "V017__enforce_single_pending_workflow_per_document.sql",
    "V018__add_outbox_processing_leases.sql",
    "V019__add_task_processing_leases.sql",
    "V020__create_idempotency_record.sql",
    "V021__add_workflow_query_indexes.sql",
    "V022__add_workflow_assignee_inbox_index.sql",
    "V023__fence_document_delivery_dispatch.sql",
    "V024__bind_resumable_upload_session_owner.sql",
    "V025__index_document_audit_log_queries.sql",
    "V026__persist_workflow_decision_evidence.sql",
    "V027__stabilize_worker_claim_order.sql",
    "V028__enforce_binary_identifier_collation.sql",
    "V029__persist_workflow_submitter.sql",
    "V033__claim_completed_upload_asset.sql",
    "V034__create_presigned_upload_session.sql",
    "V035__claim_presigned_upload_asset.sql",
).flatMap { migration ->
    fileWeftMigrationDialects.map { dialect -> "ai/icen/fw/db/migration/$dialect/$migration" }
}
val fileWeftMigrationSourceDirectory = layout.projectDirectory.dir(
    "fileweft-persistence/src/main/resources/ai/icen/fw/db/migration",
)
val expectedFileWeftMigrationSourceFiles = expectedFileWeftMigrationResources.map { resource ->
    fileWeftMigrationSourceDirectory.file(resource)
}

val stablePostgresMigrationHashes = layout.projectDirectory.file(
    ".ci/fixtures/postgres-v0.0.1-ai.icen.sha256",
)
val verifyStablePostgresMigrationHashes = tasks.register("verifyStablePostgresMigrationHashes") {
    group = "verification"
    description = "Prevents changes to PostgreSQL V001-V025 shipped by v0.0.1-ai.icen without requiring Git history."
    inputs.file(stablePostgresMigrationHashes)
        .withPropertyName("stablePostgresMigrationHashes")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files((1..25).map { version ->
        val prefix = "V${version.toString().padStart(3, '0')}__"
        fileWeftMigrationSourceDirectory.asFileTree.matching { include("postgres/${prefix}*.sql") }
    })
        .withPropertyName("stablePostgresMigrationSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    doLast(
        org.gradle.api.Action<org.gradle.api.Task> {
            val inputFiles = inputs.files.files
            val fixtureFile = inputFiles.single { file -> file.extension == "sha256" }
            val fixture = fixtureFile.readLines(Charsets.UTF_8)
                .filterNot { line -> line.isBlank() || line.startsWith("#") }
                .associate { line ->
                    val parts = line.trim().split(Regex("\\s+"), limit = 2)
                    require(parts.size == 2 && parts[0].matches(Regex("[0-9a-f]{64}"))) {
                        "Invalid stable migration hash fixture line: $line"
                    }
                    parts[1] to parts[0]
                }
            val sources = inputFiles
                .filter { file ->
                    val version = Regex("^V(\\d{3})__.*\\.sql$").matchEntire(file.name)
                        ?.groupValues?.get(1)?.toIntOrNull()
                    file.isFile && version != null && version in 1..25
                }
                .associateBy { file -> file.name }
            require(sources.keys == fixture.keys) {
                "Stable PostgreSQL migration fixture differs from V001-V025 sources; " +
                    "missing=${fixture.keys - sources.keys}, unexpected=${sources.keys - fixture.keys}."
            }
            val changed = sources.filter { (name, file) ->
                val canonicalBytes = file.readText(Charsets.UTF_8)
                    .replace("\r\n", "\n")
                    .replace("\r", "\n")
                    .toByteArray(Charsets.UTF_8)
                val digest = java.security.MessageDigest.getInstance("SHA-256").digest(canonicalBytes)
                    .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
                digest != fixture.getValue(name)
            }.keys
            require(changed.isEmpty()) {
                "PostgreSQL migrations shipped by v0.0.1-ai.icen are immutable; changed=$changed"
            }
        },
    )
}

val verifyFileWeftMigrationResources = tasks.register("verifyFileWeftMigrationResources") {
    group = "verification"
    description = "Verifies that the persistence JAR contains the exact, byte-identical namespaced FileWeft migrations for PostgreSQL, MySQL, and Kingbase."
    inputs.property("expectedFileWeftMigrationResources", expectedFileWeftMigrationResources)
    inputs.dir(fileWeftMigrationSourceDirectory)
        .withPropertyName("fileWeftMigrationSourceDirectory")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(expectedFileWeftMigrationSourceFiles)
        .withPropertyName("fileWeftMigrationSourceFiles")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("fileWeftMigrationSourceDirectoryPath", fileWeftMigrationSourceDirectory.asFile.absolutePath)
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

val publishVerifiedCnbArtifacts = tasks.register("publishVerifiedCnbArtifacts") {
    group = "publishing"
    description = "Publishes FileWeft from a CNB Tag pipeline after all same-commit verification lanes succeed."
    doFirst {
        require(cnbArtifactsPassword.isPresent) {
            "CNB_TOKEN must be available to the verified CNB Tag publication pipeline."
        }
    }
}

val verifyReleaseCredentialHygiene = tasks.register("verifyReleaseCredentialHygiene") {
    group = "verification"
    description = "Rejects CNB credentials from the repository-level Gradle properties file."
    val repositoryGradleProperties = layout.projectDirectory.file("gradle.properties")
    inputs.file(repositoryGradleProperties)
        .withPropertyName("repositoryGradleProperties")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    doLast(
        org.gradle.api.Action<org.gradle.api.Task> {
            val credentialAssignment = Regex(
                pattern = "(?m)^\\s*(?:cnbArtifactsGradlePassword|CNB_TOKEN)\\s*[:=]",
                option = RegexOption.IGNORE_CASE,
            )
            val policyFile = inputs.files.singleFile
            require(!credentialAssignment.containsMatchIn(policyFile.readText(Charsets.UTF_8))) {
                "CNB credentials must not be stored in the tracked repository gradle.properties; " +
                    "use the user-level Gradle properties file or the process environment."
            }
        },
    )
}

val verifyCnbReleaseIdentity = tasks.register("verifyCnbReleaseIdentity") {
    group = "verification"
    description = "Verifies that the CNB Tag, remote main HEAD, commit, and Gradle version identify one release."
    notCompatibleWithConfigurationCache("CNB release identity verification reads the checked-out Git commit at execution time.")
    inputs.property("cnbEvent", providers.environmentVariable("CNB_EVENT").orElse(""))
    inputs.property("cnbTag", providers.environmentVariable("CNB_BRANCH").orElse(""))
    inputs.property("cnbCommit", providers.environmentVariable("CNB_COMMIT").orElse(""))
    inputs.property(
        "verifiedCommit",
        providers.environmentVariable("FILEWEFT_CI_VERIFIED_COMMIT").orElse(""),
    )
    inputs.property("expectedTag", releaseScmTag)
    inputs.property("releaseVersion", releaseVersion)

    doLast {
        val event = inputs.properties.getValue("cnbEvent").toString()
        val tag = inputs.properties.getValue("cnbTag").toString()
        val cnbCommit = inputs.properties.getValue("cnbCommit").toString()
        val verifiedCommit = inputs.properties.getValue("verifiedCommit").toString()
        val expectedTag = inputs.properties.getValue("expectedTag").toString()
        val expectedVersion = inputs.properties.getValue("releaseVersion").toString()

        require(!expectedVersion.endsWith("-SNAPSHOT")) {
            "Verified CNB publication requires a stable version, but was $expectedVersion."
        }
        require(stableReleaseVersionPattern.matches(expectedVersion)) {
            "Verified CNB publication requires a stable numeric X.Y.Z version, but was $expectedVersion."
        }
        require(event == "tag_push") {
            "Verified CNB publication is only permitted for tag_push, but CNB_EVENT was '$event'."
        }
        require(tag == expectedTag) {
            "CNB tag '$tag' must exactly match the Gradle release tag '$expectedTag'."
        }
        require(cnbCommit.matches(Regex("[0-9a-fA-F]{40}"))) {
            "CNB_COMMIT must be a full 40-character Git commit SHA."
        }
        require(verifiedCommit == cnbCommit) {
            "FILEWEFT_CI_VERIFIED_COMMIT must match CNB_COMMIT exactly."
        }

        val gitProcess = ProcessBuilder("git", "rev-parse", "HEAD")
            .directory(rootProject.projectDir)
            .redirectErrorStream(true)
            .start()
        val checkedOutCommit = gitProcess.inputStream.bufferedReader(Charsets.UTF_8).use { reader -> reader.readText().trim() }
        require(gitProcess.waitFor() == 0) {
            "Could not resolve the checked-out Git commit: $checkedOutCommit"
        }
        require(checkedOutCommit == cnbCommit) {
            "Checked-out commit '$checkedOutCommit' does not match CNB_COMMIT '$cnbCommit'."
        }

        val remoteMainProcess = ProcessBuilder(
            "git",
            "ls-remote",
            "--exit-code",
            "origin",
            "refs/heads/main",
        )
            .directory(rootProject.projectDir)
            .redirectErrorStream(true)
            .apply { environment()["GIT_TERMINAL_PROMPT"] = "0" }
            .start()
        val remoteMainOutput = remoteMainProcess.inputStream.bufferedReader(Charsets.UTF_8)
            .use { reader -> reader.readText().trim() }
        require(remoteMainProcess.waitFor() == 0) {
            "Could not resolve the remote main HEAD without prompting: $remoteMainOutput"
        }
        val remoteMainFields = remoteMainOutput.split(Regex("\\s+")).filter(String::isNotEmpty)
        require(remoteMainFields.size == 2 && remoteMainFields[1] == "refs/heads/main") {
            "Remote main lookup returned an unexpected response: $remoteMainOutput"
        }
        val remoteMainCommit = remoteMainFields[0]
        require(remoteMainCommit.matches(Regex("[0-9a-fA-F]{40}"))) {
            "Remote main HEAD must be a full 40-character Git commit SHA."
        }
        require(remoteMainCommit == cnbCommit) {
            "Release commit '$cnbCommit' is not the current remote main HEAD '$remoteMainCommit'."
        }
    }
}

val externalIntegrationTestFiles = fileTree(layout.projectDirectory) {
    include("*/src/test/**/*IntegrationTest.kt", "*/src/test/**/*IntegrationTest.java")
    exclude("**/build/**")
}
val verifyExternalTestPartition = tasks.register("verifyExternalTestPartition") {
    group = "verification"
    description = "Fails when an external IntegrationTest is not assigned to an explicit service lane."
    inputs.files(externalIntegrationTestFiles)
        .withPropertyName("externalIntegrationTests")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    dependsOn(verifyStablePostgresMigrationHashes)
    inputs.property("repositoryRootPath", layout.projectDirectory.asFile.absolutePath)

    doLast(
        org.gradle.api.Action<org.gradle.api.Task> {
            val repositoryRoot = java.io.File(inputs.properties.getValue("repositoryRootPath").toString())
            val unexpected = mutableListOf<String>()
            inputs.files.files.sortedBy { sourceFile -> sourceFile.absolutePath }.forEach { sourceFile ->
                val relativePath = sourceFile.relativeTo(repositoryRoot).invariantSeparatorsPath
                val requiredFlags = when {
                    relativePath ==
                        "flowweft-migration-cli/src/test/kotlin/ai/icen/fw/migration/cli/FlowWeftMigrationRealDatabaseIntegrationTest.kt" ||
                        relativePath ==
                        "flowweft-workflow-persistence-jdbc/src/test/kotlin/ai/icen/fw/workflow/persistence/jdbc/WorkflowRealDatabaseIntegrationTest.kt" ->
                        setOf(
                            "FILEWEFT_RUN_POSTGRES_TESTS",
                            "FILEWEFT_RUN_MYSQL_TESTS",
                            "FILEWEFT_RUN_KINGBASE_TESTS",
                        )
                    relativePath.startsWith("fileweft-persistence/src/test/") && "MySQL" in sourceFile.name ->
                        setOf("FILEWEFT_RUN_MYSQL_TESTS")
                    relativePath.startsWith("fileweft-persistence/src/test/") && "Kingbase" in sourceFile.name ->
                        setOf("FILEWEFT_RUN_KINGBASE_TESTS")
                    relativePath.startsWith("fileweft-spring-boot") && "Kingbase" in sourceFile.name ->
                        setOf("FILEWEFT_RUN_KINGBASE_TESTS")
                    relativePath.startsWith("fileweft-persistence/src/test/") ->
                        setOf("FILEWEFT_RUN_POSTGRES_TESTS")
                    relativePath ==
                        "fileweft-adapter-s3/src/test/kotlin/ai/icen/fw/adapter/s3/S3StorageAdapterRustFsIntegrationTest.kt" ->
                        setOf("FILEWEFT_RUN_RUSTFS_TESTS")
                    relativePath ==
                        "flowweft-adapter-oss/src/test/kotlin/ai/icen/fw/adapter/oss/OssStorageAdapterIntegrationTest.kt" ->
                        setOf("FLOWWEFT_RUN_OSS_TESTS")
                    relativePath ==
                        "flowweft-adapter-oss/src/test/kotlin/ai/icen/fw/adapter/oss/OssOverwriteGuardIntegrationTest.kt" ->
                        setOf("FLOWWEFT_RUN_OSS_OVERWRITE_TESTS")
                    relativePath ==
                        "fileweft-dev/src/test/kotlin/ai/icen/fw/dev/e2e/DevAcceptanceIntegrationTest.kt" ->
                        setOf("FILEWEFT_RUN_DEV_E2E")
                    else -> emptySet()
                }
                val source = sourceFile.readText(Charsets.UTF_8)
                if (requiredFlags.isEmpty() || requiredFlags.any { requiredFlag -> requiredFlag !in source }) {
                    unexpected += relativePath
                }
            }
            require(unexpected.isEmpty()) {
                "External integration tests are missing an explicit service-lane assignment or fail-closed flag: $unexpected"
            }
        },
    )
}

val verifyPublishedReleaseRepository = tasks.register("verifyPublishedReleaseRepository") {
    group = "verification"
    description = "Verifies the exact FileWeft Maven publication contract for all public modules."
    notCompatibleWithConfigurationCache("Release repository verification inspects generated Maven archives at execution time.")
    dependsOn(publishReleaseRepository)
    inputs.dir(releaseRepositoryDirectory)
        .withPropertyName("releaseRepository")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(projectLicenseFile, projectNoticeFile)
        .withPropertyName("legalFiles")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("releaseGroup", rootProject.group.toString())
    inputs.property("releaseVersion", releaseVersion)
    inputs.property("publishableModules", publishableModuleNames.sorted())

    doLast {
        val releaseGroup = inputs.properties.getValue("releaseGroup") as String
        val expectedVersion = inputs.properties.getValue("releaseVersion") as String
        val expectedModules = (inputs.properties.getValue("publishableModules") as List<*>)
            .map { module -> module as String }
            .toSet()
        val groupDirectory = releaseRepositoryDirectory.get()
            .dir(releaseGroup.replace('.', '/'))
            .asFile
        ai.icen.fw.buildlogic.PublishedReleaseRepositoryVerifier(
            releaseGroup = releaseGroup,
            releaseVersion = expectedVersion,
            publishableModuleNames = expectedModules,
            groupDirectory = groupDirectory,
            licenseBytes = projectLicenseFile.asFile.readBytes(),
            noticeBytes = projectNoticeFile.asFile.readBytes(),
            projectHomepage = projectHomepage,
            projectScmConnection = projectScmConnection,
            apacheLicenseName = apacheLicenseName,
            apacheLicenseUrl = apacheLicenseUrl,
            releaseScmTag = releaseScmTag,
            withdrawnMavenGroup = withdrawnMavenGroup,
            withdrawnJvmPath = withdrawnJvmPath,
        ).verify()
    }
}

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }

    tasks.withType<Test>().configureEach {
        usesService(testTaskConcurrencyService)
    }

    tasks.matching { task -> task.name == "check" }.configureEach {
        dependsOn(rootProject.tasks.named("verifyFileWeftBuildLogic"))
    }

    pluginManager.withPlugin("fileweft.jvm8-library") {
        val java8Test = tasks.named("java8Test")
        val java11Test = tasks.named("java11Test")
        val java21Test = tasks.named("java21Test")
        val java25Test = tasks.named("java25Test")
        compatibilityJava8Check.configure { dependsOn(java8Test) }
        compatibilityJava11Check.configure { dependsOn(java11Test) }
        compatibilityJava21Check.configure { dependsOn(java21Test) }
        compatibilityJava25Check.configure { dependsOn(java25Test) }
    }
    pluginManager.withPlugin("fileweft.jvm17-library") {
        val java17Test = tasks.named("java17Test")
        val java21Test = tasks.named("java21Test")
        val java25Test = tasks.named("java25Test")
        compatibilityJava17Check.configure { dependsOn(java17Test) }
        compatibilityJava21Check.configure { dependsOn(java21Test) }
        compatibilityJava25Check.configure { dependsOn(java25Test) }
    }

    if (name in publishableModuleNames) {
        pluginManager.apply("maven-publish")
        pluginManager.withPlugin("java") {
            extensions.configure<PublishingExtension> {
                publications.create<MavenPublication>("mavenJava") {
                    from(components["java"])
                    artifactId = project.name
                    versionMapping {
                        usage("java-api") {
                            fromResolutionOf("runtimeClasspath")
                        }
                        usage("java-runtime") {
                            fromResolutionResult()
                        }
                    }
                    pom {
                        name.set("FlowWeft module ${project.name}")
                        description.set("FlowWeft enterprise file and workflow infrastructure module ${project.name}.")
                        url.set(projectHomepage)
                        licenses {
                            license {
                                name.set(apacheLicenseName)
                                url.set(apacheLicenseUrl)
                                distribution.set("repo")
                            }
                        }
                        developers {
                            developer {
                                id.set("icen.ai")
                                name.set("icen.ai")
                                organization.set("icen.ai")
                                email.set("support@icen.ai")
                            }
                        }
                        scm {
                            connection.set(projectScmConnection)
                            developerConnection.set(projectScmConnection)
                            url.set(projectHomepage)
                            tag.set(releaseScmTag)
                        }
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
            tasks.withType<Jar>().configureEach {
                from(rootProject.layout.projectDirectory.file("LICENSE")) {
                    into("META-INF")
                }
                from(rootProject.layout.projectDirectory.file("NOTICE")) {
                    into("META-INF")
                }
            }
        }
    }
}

val releaseTestEnvironment = tasks.register("verifyReleaseTestEnvironment") {
    group = "verification"
    description = "Fails closed unless every external integration suite required for a release is enabled."
    inputs.property(
        "postgresIntegrationEnabled",
        providers.environmentVariable("FILEWEFT_RUN_POSTGRES_TESTS").map { value -> value == "true" }.orElse(false),
    )
    inputs.property(
        "mysqlIntegrationEnabled",
        providers.environmentVariable("FILEWEFT_RUN_MYSQL_TESTS").map { value -> value == "true" }.orElse(false),
    )
    inputs.property(
        "kingbaseIntegrationEnabled",
        providers.environmentVariable("FILEWEFT_RUN_KINGBASE_TESTS").map { value -> value == "true" }.orElse(false),
    )
    inputs.property(
        "rustFsIntegrationEnabled",
        providers.environmentVariable("FILEWEFT_RUN_RUSTFS_TESTS").map { value -> value == "true" }.orElse(false),
    )
    inputs.property(
        "devApiIntegrationEnabled",
        providers.environmentVariable("FILEWEFT_RUN_DEV_E2E").map { value -> value == "true" }.orElse(false),
    )
    inputs.property(
        "devUiIntegrationEnabled",
        providers.environmentVariable("FILEWEFT_RUN_DEV_UI_E2E").map { value -> value.equals("true", ignoreCase = true) }.orElse(false),
    )
    inputs.property(
        "devPlatformSharedSecretLength",
        providers.environmentVariable("FILEWEFT_DEV_PLATFORM_SHARED_SECRET").map { value -> value.length }.orElse(0),
    )
    doLast(
        org.gradle.api.Action<org.gradle.api.Task> {
            val requiredFlags = linkedMapOf(
                "FILEWEFT_RUN_POSTGRES_TESTS" to inputs.properties["postgresIntegrationEnabled"],
                "FILEWEFT_RUN_MYSQL_TESTS" to inputs.properties["mysqlIntegrationEnabled"],
                "FILEWEFT_RUN_KINGBASE_TESTS" to inputs.properties["kingbaseIntegrationEnabled"],
                "FILEWEFT_RUN_RUSTFS_TESTS" to inputs.properties["rustFsIntegrationEnabled"],
                "FILEWEFT_RUN_DEV_E2E" to inputs.properties["devApiIntegrationEnabled"],
                "FILEWEFT_RUN_DEV_UI_E2E" to inputs.properties["devUiIntegrationEnabled"],
            )
            val disabledFlags = requiredFlags.filterValues { enabled -> enabled != true }.keys
            require(disabledFlags.isEmpty()) {
                "Release verification requires these external integration flags to be true: $disabledFlags"
            }
            val secretLength = (inputs.properties["devPlatformSharedSecretLength"] as Number).toInt()
            require(secretLength >= 32) {
                "Release verification requires FILEWEFT_DEV_PLATFORM_SHARED_SECRET with at least 32 characters."
            }
        },
    )
}

val cleanRemoteCnbConsumerGradleHome = tasks.register<Delete>("cleanRemoteCnbConsumerGradleHome") {
    group = "verification"
    description = "Removes the isolated Gradle home used by the remote CNB consumer smoke test."
    delete(layout.buildDirectory.dir("remote-cnb-consumer-gradle-home"))
}

val remoteCnbConsumerSmoke = tasks.register<Exec>("remoteCnbConsumerSmoke") {
    group = "verification"
    description = "Cold-resolves all public modules from CNB Maven and compiles independent consumers."
    dependsOn(cleanRemoteCnbConsumerGradleHome, verifyPublicationInventory)
    workingDir(rootProject.projectDir)
    environment(
        "GRADLE_USER_HOME",
        layout.buildDirectory.dir("remote-cnb-consumer-gradle-home").get().asFile.absolutePath,
    )
    commandLine(nestedGradleWrapperCommand + listOf(
        "-p",
        rootProject.file("release-smoke").absolutePath,
        "clean",
        "releaseSmoke",
        "-PfileweftVersion=$releaseVersion",
        "-PfileweftRepositoryUrl=https://maven.cnb.cool/china.ai/maven/-/packages/",
        "--no-daemon",
        "--no-configuration-cache",
        "--refresh-dependencies",
    ))
    doFirst {
        require(!releaseVersion.endsWith("-SNAPSHOT")) {
            "Remote CNB consumer smoke requires a stable release version, but was $releaseVersion."
        }
    }
}

val postgresIntegrationCheck = tasks.register("postgresIntegrationCheck") {
    group = "verification"
    description = "Runs PostgreSQL persistence with FlowWeft, Boot 2, and Boot 3 Flyway runtimes."
    dependsOn(
        ":fileweft-persistence:postgresIntegrationTest",
        ":fileweft-persistence:postgresFlyway8CompatibilityTest",
        ":fileweft-persistence:postgresFlyway11CompatibilityTest",
        ":flowweft-workflow-persistence-jdbc:workflowPostgresIntegrationTest",
        ":flowweft-migration-cli:flowweftMigrationPostgresIntegrationTest",
    )
}

val mysqlIntegrationCheck = tasks.register("mysqlIntegrationCheck") {
    group = "verification"
    description = "Runs MySQL 8 persistence with FlowWeft, Boot 2, and Boot 3 Flyway runtimes."
    dependsOn(
        ":fileweft-persistence:mysqlIntegrationTest",
        ":fileweft-persistence:mysqlFlyway8CompatibilityTest",
        ":fileweft-persistence:mysqlFlyway11CompatibilityTest",
        ":flowweft-workflow-persistence-jdbc:workflowMySqlIntegrationTest",
        ":flowweft-migration-cli:flowweftMigrationMySqlIntegrationTest",
    )
}

val kingbaseIntegrationCheck = tasks.register("kingbaseIntegrationCheck") {
    group = "verification"
    description = "Runs KingbaseES V8 persistence with FlowWeft, Boot 2, and Boot 3 Flyway runtimes."
    dependsOn(
        ":fileweft-persistence:kingbaseIntegrationTest",
        ":fileweft-persistence:kingbaseFlyway8CompatibilityTest",
        ":fileweft-persistence:kingbaseFlyway11CompatibilityTest",
        ":fileweft-spring-boot2-starter:kingbaseFlywayAutoConfigurationIntegrationTest",
        ":fileweft-spring-boot3-starter:kingbaseFlywayAutoConfigurationIntegrationTest",
        ":flowweft-workflow-persistence-jdbc:workflowKingbaseIntegrationTest",
        ":flowweft-migration-cli:flowweftMigrationKingbaseIntegrationTest",
    )
}

val rustFsIntegrationCheck = tasks.register("rustFsIntegrationCheck") {
    group = "verification"
    description = "Runs the dedicated RustFS storage-adapter integration lane."
    dependsOn(":fileweft-adapter-s3:rustFsIntegrationTest")
}

val ossIntegrationCheck = tasks.register("ossIntegrationCheck") {
    group = "verification"
    description = "Runs the dedicated Alibaba Cloud OSS storage-adapter integration lane."
    dependsOn(":flowweft-adapter-oss:ossIntegrationTest")
}

val devAcceptanceCheck = tasks.register("devAcceptanceCheck") {
    group = "verification"
    description = "Runs Dev API and Playwright acceptance sequentially against one Compose stack."
    dependsOn(
        ":fileweft-dev:devApiAcceptanceTest",
        ":fileweft-dev:devUiE2e",
    )
}

val externalAcceptanceCheck = tasks.register("externalAcceptanceCheck") {
    group = "verification"
    description = "Runs every external-system suite required for a FileWeft release exactly once."
    dependsOn(
        releaseTestEnvironment,
        postgresIntegrationCheck,
        mysqlIntegrationCheck,
        kingbaseIntegrationCheck,
        rustFsIntegrationCheck,
        ossIntegrationCheck,
        devAcceptanceCheck,
    )
}

val releaseQualityCheck = tasks.register("releaseQualityCheck") {
    group = "verification"
    description = "Runs fast architecture, build-logic, documentation, migration, partition, and credential checks."
    dependsOn(
        verifyFileWeftBuildLogic,
        verifyPublicationInventory,
        "verifyFileWeftArchitecture",
        "verifyFileWeftWebApiDependencies",
        verifyDocsSite,
        verifyCnbPathPolicy,
        verifyFileWeftMigrationResources,
        verifyExternalTestPartition,
        verifyReleaseCredentialHygiene,
    )
}

val fastCheck = tasks.register("fastCheck") {
    group = "verification"
    description = "Runs the local fast feedback gate without cross-JDK or external-system suites."
    dependsOn(releaseQualityCheck)
}

val releaseVerification = tasks.register("releaseVerification") {
    group = "verification"
    description = "Runs release quality, JVM compatibility, and external acceptance gates without publishing artifacts."
    dependsOn(
        releaseTestEnvironment,
        releaseQualityCheck,
        compatibilityCheck,
        externalAcceptanceCheck,
    )
}

val releaseArtifactVerification = tasks.register("releaseArtifactVerification") {
    group = "verification"
    description = "Verifies local Maven artifacts, metadata, SBOM, documentation, migrations, and cold consumers."
    dependsOn(
        publishReleaseRepository,
        verifyPublishedReleaseRepository,
        verifyPublishedStarterConfigurationMetadata,
        "verifySbom",
        verifyDocsSite,
        verifyFileWeftMigrationResources,
        verifyReleaseCredentialHygiene,
        releaseConsumerSmoke,
    )
}

val cleanReleaseDirectory = tasks.register<Delete>("cleanReleaseDirectory") {
    group = "build"
    description = "Removes stale FileWeft release archives before assembling the current version."
    delete(layout.buildDirectory.dir("release"))
}

val assembleReleaseBundle = tasks.register<Zip>("assembleReleaseBundle") {
    group = "distribution"
    description = "Builds the artifact-verified FileWeft $releaseVersion Maven repository and SBOM bundle."
    dependsOn(
        releaseArtifactVerification,
        cleanReleaseDirectory,
    )
    archiveBaseName.set("fileweft")
    archiveVersion.set(version.toString())
    archiveClassifier.set("release")
    destinationDirectory.set(layout.buildDirectory.dir("release"))
    inputs.file(releaseNotesFile)
        .withPropertyName("releaseNotesFile")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    from(releaseRepositoryDirectory) {
        into("repository")
    }
    from(layout.buildDirectory.dir("reports/cyclonedx-release")) {
        include("bom.json", "bom.xml")
        into("sbom")
    }
    from(releaseNotesFile) {
        rename { "RELEASE_NOTES.md" }
    }
    from("README.md")
    from("SKILL.md")
    from("fileweft-docs") {
        into("fileweft-docs")
    }
    from("docs") {
        into("docs")
    }
    from(projectLicenseFile)
    from(projectNoticeFile)
    from("SECURITY.md")
}

val verifyReleaseBundleContents = tasks.register("verifyReleaseBundleContents") {
    group = "verification"
    description = "Verifies the legal, security, Maven repository, and SBOM contents of the release ZIP."
    notCompatibleWithConfigurationCache("Release bundle verification inspects the final ZIP at execution time.")
    dependsOn(assembleReleaseBundle)
    val archiveFile = assembleReleaseBundle.flatMap { task -> task.archiveFile }
    inputs.file(archiveFile)
        .withPropertyName("releaseBundle")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(projectLicenseFile, projectNoticeFile, layout.projectDirectory.file("SECURITY.md"))
        .withPropertyName("releasePolicyFiles")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    doLast {
        val releaseArchive = archiveFile.get().asFile
        require(releaseArchive.isFile && releaseArchive.length() > 0L) {
            "Release bundle was not created: ${releaseArchive.absolutePath}"
        }
        val staleArchives = releaseArchive.parentFile.listFiles()
            .orEmpty()
            .filter { candidate -> candidate.isFile && candidate.extension == "zip" && candidate != releaseArchive }
        require(staleArchives.isEmpty()) {
            "Release directory contains stale ZIP archives: ${staleArchives.map { archive -> archive.name }}"
        }
        ZipFile(releaseArchive).use { zip ->
            val archiveEntries = zip.entries().asSequence()
                .filterNot { entry -> entry.isDirectory }
                .toList()
            val duplicateEntries = archiveEntries
                .groupingBy { entry -> entry.name }
                .eachCount()
                .filterValues { count -> count > 1 }
                .keys
            require(duplicateEntries.isEmpty()) {
                "Release bundle contains duplicate ZIP entries: $duplicateEntries"
            }
            val entries = archiveEntries.associateBy { entry -> entry.name }
            val requiredFiles = setOf(
                "LICENSE",
                "NOTICE",
                "SECURITY.md",
                "README.md",
                "SKILL.md",
                "fileweft-docs/index.html",
                "fileweft-docs/styles.css",
                "fileweft-docs/app.js",
                "fileweft-docs/content.js",
                "docs/production-operations.md",
                "docs/plugin-development.md",
                "docs/releases/0.0.1.md",
                "RELEASE_NOTES.md",
                "sbom/bom.json",
                "sbom/bom.xml",
            )
            require(entries.keys.containsAll(requiredFiles)) {
                "Release bundle is missing required files: ${requiredFiles - entries.keys}"
            }
            mapOf(
                "LICENSE" to projectLicenseFile.asFile,
                "NOTICE" to projectNoticeFile.asFile,
                "SECURITY.md" to layout.projectDirectory.file("SECURITY.md").asFile,
            ).forEach { (entryName, sourceFile) ->
                val packaged = zip.getInputStream(entries.getValue(entryName)).use { stream -> stream.readBytes() }
                require(packaged.contentEquals(sourceFile.readBytes())) {
                    "Release bundle entry differs from its reviewed source: $entryName"
                }
            }
            val repositoryPrefix = "repository/${rootProject.group.toString().replace('.', '/')}/"
            val publishedPomFiles = entries.keys.filter { entry ->
                entry.startsWith(repositoryPrefix) && entry.endsWith(".pom")
            }
            require(publishedPomFiles.size == publishableModuleNames.size) {
                "Release bundle must contain exactly ${publishableModuleNames.size} published POMs, " +
                    "found ${publishedPomFiles.size}."
            }
            require(entries.keys.none { entry -> entry.startsWith("repository/$withdrawnJvmPath/") }) {
                "Release bundle contains the withdrawn $withdrawnMavenGroup Maven group."
            }
        }
    }
}

val releaseArtifactCheck = tasks.register("releaseArtifactCheck") {
    group = "verification"
    description = "Builds and verifies the complete local release repository, SBOM, consumers, and release ZIP."
    dependsOn(verifyReleaseBundleContents)
}

val releaseCheck = tasks.register("releaseCheck") {
    group = "verification"
    description = "Runs every FileWeft $releaseVersion quality, compatibility, acceptance, and artifact release gate."
    dependsOn(
        releaseVerification,
        releaseArtifactCheck,
    )
}

tasks.register("releaseBundle") {
    group = "distribution"
    description = "Compatibility release entry point: runs every release gate and produces the verified bundle."
    dependsOn(releaseCheck)
}

gradle.projectsEvaluated {
    val runtimeMatrixTasks = allprojects
        .flatMap { project ->
            project.tasks.withType(Test::class.java)
                .matching { task -> task.name.matches(Regex("java(?:8|11|17|21|25)Test")) }
                .toList()
        }
        .sortedBy { task -> "${task.project.path}:${task.name}" }

    if (fullReleaseVerificationRequested) {
        runtimeMatrixTasks.forEach { matrixTask ->
            matrixTask.dependsOn(releaseTestEnvironment)
        }
        listOf(
            project(":fileweft-persistence").tasks.named("postgresIntegrationTest"),
            project(":fileweft-adapter-s3").tasks.named("rustFsIntegrationTest"),
            project(":fileweft-dev").tasks.named("devApiAcceptanceTest"),
            project(":fileweft-dev").tasks.named("devUiE2e"),
        ).forEach { externalTask ->
            externalTask.configure { dependsOn(releaseTestEnvironment) }
        }
    }

    val publishedProjects = subprojects.filter { project -> project.name in publishableModuleNames }
    val persistenceJar = project(":fileweft-persistence").tasks.named<Jar>("jar")
    verifyFileWeftMigrationResources.configure {
        dependsOn(persistenceJar)
        inputs.file(persistenceJar.flatMap { task -> task.archiveFile })
            .withPropertyName("fileWeftPersistenceJar")
            .withPathSensitivity(PathSensitivity.RELATIVE)
        doLast(
            org.gradle.api.Action<org.gradle.api.Task> {
                val inputFiles = inputs.files.files
                val archives = inputFiles.filter { file -> file.isFile && file.extension.equals("jar", ignoreCase = true) }
                require(archives.size == 1) {
                    "Expected exactly one persistence JAR input but found: ${archives.map { file -> file.absolutePath }}"
                }
                val archive = archives.single()
                val expected = (inputs.properties["expectedFileWeftMigrationResources"] as List<*>)
                    .map { resource -> resource as String }
                    .toSet()
                val sourceDirectoryPath = inputs.properties["fileWeftMigrationSourceDirectoryPath"] as String
                val sourceDirectory = File(sourceDirectoryPath)
                val sourceFiles = inputFiles
                    .filter { file -> file.isFile && file.extension.equals("sql", ignoreCase = true) }
                    .associateBy { file ->
                        "ai/icen/fw/db/migration/" +
                            file.toRelativeString(sourceDirectory).replace("\\", "/")
                    }
                val missingSources = expected - sourceFiles.keys
                val unexpectedSources = sourceFiles.keys - expected
                require(missingSources.isEmpty() && unexpectedSources.isEmpty()) {
                        "FileWeft migration source inputs differ from the reviewed V001-V029 set; " +
                        "missing=$missingSources, unexpected=$unexpectedSources."
                }
                require(archive.isFile) { "FileWeft persistence JAR was not created: ${archive.absolutePath}" }
                ZipFile(archive).use { jar ->
                    val jarEntries = jar.entries().asSequence()
                        .filterNot { entry -> entry.isDirectory }
                        .toList()
                    val duplicateEntries = jarEntries
                        .groupingBy { entry -> entry.name }
                        .eachCount()
                        .filterValues { count -> count > 1 }
                        .keys
                    require(duplicateEntries.isEmpty()) {
                        "FileWeft persistence JAR contains duplicate ZIP entries: $duplicateEntries"
                    }
                    val fileEntries = jarEntries.associateBy { entry -> entry.name }
                    val actual = fileEntries.keys
                        .filter { name -> name.startsWith("ai/icen/fw/db/migration/") }
                        .toSet()
                    val missing = expected - actual
                    val unexpected = actual - expected
                    require(missing.isEmpty() && unexpected.isEmpty()) {
                        "FileWeft persistence JAR migration resources differ from the reviewed V001-V029 set; " +
                            "missing=$missing, unexpected=$unexpected."
                    }
                    val empty = expected.filter { resource -> fileEntries.getValue(resource).size <= 0L }
                    require(empty.isEmpty()) { "FileWeft persistence JAR contains empty migration resources: $empty" }
                    val contentMismatches = expected.filter { resource ->
                        val source = sourceFiles.getValue(resource).readBytes()
                        val packaged = jar.getInputStream(fileEntries.getValue(resource)).use { stream -> stream.readBytes() }
                        !source.contentEquals(packaged)
                    }
                    require(contentMismatches.isEmpty()) {
                        "FileWeft persistence JAR migration resources differ byte-for-byte from their sources: " +
                            contentMismatches
                    }
                    val legacy = fileEntries.keys.filter { name -> name.startsWith("db/migration/") }
                    require(legacy.isEmpty()) {
                        "FileWeft persistence JAR must not expose Flyway's shared db/migration namespace: $legacy"
                    }
                }
            },
        )
    }
    val localReleasePublishTasks = publishedProjects.map { project ->
        project.tasks.named("publishMavenJavaPublicationToFileWeftReleaseRepository")
    }
    localReleasePublishTasks.forEach { taskProvider ->
        taskProvider.configure {
            dependsOn(cleanReleaseRepository, verifyPublicationInventory)
        }
    }
    publishReleaseRepository.configure {
        dependsOn(verifyPublicationInventory, localReleasePublishTasks)
    }
    installReleaseToMavenLocal.configure {
        dependsOn(
            verifyPublicationInventory,
            publishedProjects.map { project ->
                project.tasks.named("publishMavenJavaPublicationToMavenLocal")
            },
        )
    }
    fastCheck.configure {
        dependsOn(subprojects.map { project -> project.tasks.named("test") })
    }
    if (cnbPublishingRequested && cnbArtifactsPassword.isPresent) {
        val cnbPublishTasks = publishedProjects.sortedBy { project -> project.name }.map { project ->
            project.tasks.named("publishMavenJavaPublicationToCnbArtifactsRepository")
        }
        cnbPublishTasks.zipWithNext().forEach { (previous, next) ->
            next.configure {
                mustRunAfter(previous)
            }
        }
        cnbPublishTasks.forEach { taskProvider ->
            taskProvider.configure {
                if (verifiedCnbPublishingRequested) {
                    dependsOn(releaseArtifactCheck, verifyCnbReleaseIdentity)
                } else {
                    dependsOn(releaseCheck)
                }
                notCompatibleWithConfigurationCache("CNB credentials must never be stored in the configuration cache.")
            }
        }
        if (verifiedCnbPublishingRequested) {
            publishVerifiedCnbArtifacts.configure {
                dependsOn(cnbPublishTasks, releaseArtifactCheck, verifyCnbReleaseIdentity)
                notCompatibleWithConfigurationCache("CNB credentials must never be stored in the configuration cache.")
            }
        } else {
            publishCnbArtifacts.configure {
                dependsOn(cnbPublishTasks)
                notCompatibleWithConfigurationCache("CNB credentials must never be stored in the configuration cache.")
            }
        }
    }
}


