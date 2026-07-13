import org.gradle.api.JavaVersion
import org.gradle.api.attributes.Bundling
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.1.21" apply false
}

version = providers.gradleProperty("fileweftVersion").orNull
    ?: throw GradleException("-PfileweftVersion is required for release consumer smoke testing.")

val expectedFileWeftModules = setOf(
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
val releaseInventory = configurations.create("releaseInventory") {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
    }
}
dependencies {
    expectedFileWeftModules.forEach { moduleName ->
        add(releaseInventory.name, "ai.icen:$moduleName:${project.version}")
    }
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "org.jetbrains.kotlin.jvm")

    group = "ai.icen.release.smoke"
    version = rootProject.version
}

fun Project.targetJvm(javaVersion: JavaVersion, kotlinTarget: JvmTarget, release: Int) {
    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = javaVersion
        targetCompatibility = javaVersion
    }
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(release)
    }
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.jvmTarget.set(kotlinTarget)
        compilerOptions.javaParameters.set(true)
    }
}

project(":boot2-consumer") {
    targetJvm(JavaVersion.VERSION_1_8, JvmTarget.JVM_1_8, 8)
    dependencies {
        add("implementation", "ai.icen:fileweft-spring-boot2-starter:${rootProject.version}")
        add("implementation", "ai.icen:fileweft-web-spring-boot2-starter:${rootProject.version}")
    }
}

project(":boot3-consumer") {
    targetJvm(JavaVersion.VERSION_17, JvmTarget.JVM_17, 17)
    dependencies {
        add("implementation", "ai.icen:fileweft-spring-boot3-starter:${rootProject.version}")
        add("implementation", "ai.icen:fileweft-web-spring-boot3-starter:${rootProject.version}")
    }
}

project(":library-consumer") {
    targetJvm(JavaVersion.VERSION_1_8, JvmTarget.JVM_1_8, 8)
    dependencies {
        add("implementation", "ai.icen:fileweft-spi:${rootProject.version}")
        add("implementation", "ai.icen:fileweft-agent:${rootProject.version}")
        add("implementation", "ai.icen:fileweft-persistence:${rootProject.version}")
        add("implementation", "ai.icen:fileweft-adapter-micrometer:${rootProject.version}")
    }
}

val verifyReleaseInventory = tasks.register("verifyReleaseInventory") {
    group = "verification"
    description = "Resolves the exact public FileWeft module inventory from the configured Maven repository."
    inputs.property("expectedFileWeftModules", expectedFileWeftModules.sorted())
    inputs.files(releaseInventory)

    doLast {
        val resolvedFileWeftModules = releaseInventory.resolvedConfiguration.resolvedArtifacts
            .map { artifact -> artifact.moduleVersion.id }
            .filter { component -> component.group == "ai.icen" && component.version == project.version.toString() }
            .map { component -> component.name }
            .toSet()
        require(resolvedFileWeftModules == expectedFileWeftModules) {
            "Resolved FileWeft inventory differs from the release contract; " +
                "missing=${expectedFileWeftModules - resolvedFileWeftModules}, " +
                "unexpected=${resolvedFileWeftModules - expectedFileWeftModules}."
        }
    }
}

tasks.register("releaseSmoke") {
    group = "verification"
    description = "Verifies the exact release inventory and compiles independent Boot 2, Boot 3, and library consumers."
    dependsOn(verifyReleaseInventory)
    dependsOn(subprojects.map { consumerProject -> consumerProject.tasks.named("build") })
}
