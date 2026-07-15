import org.gradle.api.artifacts.Configuration
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.plugin.KaptExtension

plugins {
    id("fileweft.jvm17-library")
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
    compileOnly(project(":flowweft-adapter-oss"))
    api(libs.jackson.databind)
    api(libs.spring.boot3.autoconfigure)
    compileOnly(libs.flyway.core.boot3)
    // Spring Boot 3.5 manages Flyway 11, where PostgreSQL support is no
    // longer bundled in flyway-core. Kingbase intentionally reuses that
    // PostgreSQL engine through a stable JDBC metadata adapter.
    // Keep all Flyway service-provider modules on one internal ABI even when a
    // consumer does not import Spring Boot's dependency-management BOM.
    runtimeOnly(libs.flyway.core.boot3)
    runtimeOnly(libs.flyway.mysql.boot3)
    runtimeOnly(libs.flyway.postgresql.boot3)
    add("kapt", libs.spring.boot3.configuration.processor)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.spring.boot3.test)
    testImplementation(libs.spring.boot3.starter.test)
    testImplementation(libs.spring.boot3.starter.jdbc)
    testImplementation(libs.assertj.core)
    testImplementation(project(":flowweft-adapter-oss"))
    testCompileOnly(libs.flyway.core.boot3)
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
val kingbaseFlyway11Runtime: Configuration = configurations.create("kingbaseFlyway11Runtime") {
    isCanBeConsumed = false
    isCanBeResolved = true
    extendsFrom(configurations.testRuntimeClasspath.get())
    resolutionStrategy.eachDependency {
        if (requested.group == "org.flywaydb" &&
            requested.name in setOf("flyway-core", "flyway-mysql", "flyway-database-postgresql")
        ) {
            useVersion("11.7.2")
            because("The Spring Boot 3 Kingbase host lane must execute with one coherent Flyway 11 service-provider ABI.")
        }
    }
}
dependencies.add(kingbaseFlyway11Runtime.name, "org.flywaydb:flyway-core:11.7.2")
dependencies.add(kingbaseFlyway11Runtime.name, "org.flywaydb:flyway-mysql:11.7.2")
dependencies.add(kingbaseFlyway11Runtime.name, "org.flywaydb:flyway-database-postgresql:11.7.2")

tasks.register<Test>("kingbaseFlywayAutoConfigurationIntegrationTest") {
    group = "verification"
    description = "Runs Spring Boot 3 Flyway auto-configuration against real KingbaseES with Flyway 11 and JDK 17."
    testClassesDirs = testSourceSet.get().output.classesDirs
    classpath = testSourceSet.get().output + sourceSets.main.get().output + kingbaseFlyway11Runtime
    useJUnitPlatform()
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(17))
    })
    filter { includeTestsMatching("*KingbaseFlywayAutoConfigurationIntegrationTest") }
    maxParallelForks = 1
    inputs.property("FILEWEFT_RUN_KINGBASE_TESTS", runKingbaseIntegration)
    doNotTrackState("The Spring Boot 3 Kingbase host test must execute against the current database state.")
    doFirst {
        require(inputs.properties.getValue("FILEWEFT_RUN_KINGBASE_TESTS") == true) {
            "Set FILEWEFT_RUN_KINGBASE_TESTS=true and start the dedicated Kingbase test service."
        }
        val flywayArtifacts = kingbaseFlyway11Runtime.resolvedConfiguration.resolvedArtifacts
            .filter { artifact -> artifact.moduleVersion.id.group == "org.flywaydb" }
        val modules = flywayArtifacts.map { artifact -> artifact.name }.toSet()
        val versions = flywayArtifacts.map { artifact -> artifact.moduleVersion.id.version }.toSet()
        require(
            modules == setOf("flyway-core", "flyway-mysql", "flyway-database-postgresql") &&
                versions == setOf("11.7.2"),
        ) {
            "Expected exact Flyway 11.7.2 modules [flyway-core, flyway-mysql, flyway-database-postgresql], but resolved " +
                flywayArtifacts.map { artifact -> "${artifact.name}:${artifact.moduleVersion.id.version}" }
        }
    }
}
