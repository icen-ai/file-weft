import org.gradle.api.artifacts.Configuration
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.plugin.KaptExtension

plugins {
    id("fileweft.jvm8-library")
}

apply(plugin = "org.jetbrains.kotlin.kapt")

dependencies {
    api(project(":fileweft-runtime"))
    // Auto-configuration bean methods are public JVM API. Keep every type used
    // in their signatures available to Java and Kotlin consumers.
    api(project(":fileweft-agent"))
    api(project(":fileweft-adapter"))
    api(project(":fileweft-adapter-micrometer"))
    api(libs.micrometer.core)
    api(project(":fileweft-persistence"))
    api(libs.jackson.databind)
    api(libs.spring.boot2.autoconfigure)
    compileOnly(libs.flyway.core.boot2)
    add("kapt", libs.spring.boot2.configuration.processor)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.spring.boot2.test)
    testImplementation(libs.spring.boot2.starter.jdbc)
    testImplementation(libs.assertj.core)
    testCompileOnly(libs.flyway.core.boot2)
    testRuntimeOnly(libs.junit.platform.launcher)
}

extensions.configure<KaptExtension> {
    arguments {
        arg(
            "org.springframework.boot.configurationprocessor.additionalMetadataLocations",
            file("src/main/resources").absolutePath,
        )
    }
}

tasks.matching { task -> task.name == "kaptKotlin" }.configureEach {
    inputs.file(layout.projectDirectory.file("src/main/resources/META-INF/additional-spring-configuration-metadata.json"))
        .withPropertyName("additionalSpringConfigurationMetadata")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

val runKingbaseIntegration = providers.environmentVariable("FILEWEFT_RUN_KINGBASE_TESTS")
    .map { value -> value == "true" }
    .orElse(false)
val testSourceSet = sourceSets.named("test")
val kingbaseFlyway8Runtime: Configuration = configurations.create("kingbaseFlyway8Runtime") {
    isCanBeConsumed = false
    isCanBeResolved = true
    extendsFrom(configurations.testRuntimeClasspath.get())
    resolutionStrategy.eachDependency {
        if (requested.group == "org.flywaydb" && requested.name in setOf("flyway-core", "flyway-mysql")) {
            useVersion("8.5.13")
            because("The Spring Boot 2 Kingbase host lane must execute with the exact Boot-managed Flyway 8 ABI.")
        }
    }
}
dependencies.add(kingbaseFlyway8Runtime.name, "org.flywaydb:flyway-core:8.5.13")
dependencies.add(kingbaseFlyway8Runtime.name, "org.flywaydb:flyway-mysql:8.5.13")

tasks.register<Test>("kingbaseFlywayAutoConfigurationIntegrationTest") {
    group = "verification"
    description = "Runs Spring Boot 2 Flyway auto-configuration against real KingbaseES with Flyway 8 and JDK 8."
    notCompatibleWithConfigurationCache("Kingbase Flyway host lanes resolve a versioned host runtime classpath that the configuration cache cannot serialize.")
    testClassesDirs = testSourceSet.get().output.classesDirs
    classpath = testSourceSet.get().output + sourceSets.main.get().output + kingbaseFlyway8Runtime
    useJUnitPlatform()
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(8))
    })
    filter { includeTestsMatching("*KingbaseFlywayAutoConfigurationIntegrationTest") }
    maxParallelForks = 1
    inputs.property("FILEWEFT_RUN_KINGBASE_TESTS", runKingbaseIntegration)
    doNotTrackState("The Spring Boot 2 Kingbase host test must execute against the current database state.")
    doFirst {
        require(inputs.properties.getValue("FILEWEFT_RUN_KINGBASE_TESTS") == true) {
            "Set FILEWEFT_RUN_KINGBASE_TESTS=true and start the dedicated Kingbase test service."
        }
        val flywayArtifacts = kingbaseFlyway8Runtime.resolvedConfiguration.resolvedArtifacts
            .filter { artifact -> artifact.moduleVersion.id.group == "org.flywaydb" }
        val modules = flywayArtifacts.map { artifact -> artifact.name }.toSet()
        val versions = flywayArtifacts.map { artifact -> artifact.moduleVersion.id.version }.toSet()
        require(modules == setOf("flyway-core", "flyway-mysql") && versions == setOf("8.5.13")) {
            "Expected exact Flyway 8.5.13 modules [flyway-core, flyway-mysql], but resolved " +
                flywayArtifacts.map { artifact -> "${artifact.name}:${artifact.moduleVersion.id.version}" }
        }
    }
}
