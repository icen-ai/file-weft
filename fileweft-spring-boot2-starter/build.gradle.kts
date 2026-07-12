import org.gradle.api.tasks.PathSensitivity
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
    add("kapt", libs.spring.boot2.configuration.processor)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.spring.boot2.test)
    testImplementation(libs.assertj.core)
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
