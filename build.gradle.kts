import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.Exec
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.jvm.tasks.Jar
import org.gradle.api.tasks.bundling.Zip

plugins {
    id("fileweft.architecture-guard")
    id("org.cyclonedx.bom") version "3.2.4"
    id("fileweft.sbom-verification")
}

group = "com.fileweft"
version = "0.0.1"

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
    description = "Publishes every public FileWeft 0.0.1 module to build/repository."
}

val installReleaseToMavenLocal = tasks.register("installReleaseToMavenLocal") {
    group = "publishing"
    description = "Installs every public FileWeft 0.0.1 module into Maven local."
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
        "--no-daemon",
        "--no-configuration-cache",
    )
}

val publishCnbArtifacts = tasks.register("publishCnbArtifacts") {
    group = "publishing"
    description = "Publishes every public FileWeft 0.0.1 module to the configured CNB Maven registry."
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
    description = "Builds the shareable FileWeft 0.0.1 Maven repository, SBOM, and release notes bundle."
    dependsOn(publishReleaseRepository, "verifySbom")
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
    from("docs/releases/0.0.1.md") {
        rename { "RELEASE_NOTES.md" }
    }
    from("README.md")
}

val releaseCheck = tasks.register("releaseCheck") {
    group = "verification"
    description = "Runs all 0.0.1 release gates and creates the shareable release bundle."
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
    publishReleaseRepository.configure {
        dependsOn(
            publishedProjects.map { project ->
                project.tasks.named("publishMavenJavaPublicationToFileWeftReleaseRepository")
            },
        )
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
