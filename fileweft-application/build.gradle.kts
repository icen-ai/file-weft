import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    id("fileweft.jvm8-library")
}

dependencies {
    api(project(":fileweft-core"))
    api(project(":fileweft-domain"))
    api(project(":fileweft-spi"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Compile the consumer together with a frozen copy of the pre-owner API. Only the consumer
// bytecode is packaged below, so tests necessarily link it to the current implementation.
val releasedResumableUploadConsumer = extensions.getByType<SourceSetContainer>().create(
    "releasedResumableUploadConsumer",
) {
    compileClasspath = configurations.getByName("compileClasspath")
    runtimeClasspath = output + compileClasspath
}
extensions.configure<KotlinJvmProjectExtension> {
    sourceSets.named(releasedResumableUploadConsumer.name) {
        kotlin.setSrcDirs(
            listOf(
                "src/binaryCompatibilityOldApi/kotlin",
                "src/binaryCompatibilityConsumer/kotlin",
            ),
        )
    }
}

val releasedResumableUploadConsumerJar = tasks.register<Jar>(
    "releasedResumableUploadConsumerJar",
) {
    description = "Packages only the old-API-compiled consumer, excluding the frozen API classes."
    dependsOn(tasks.named(releasedResumableUploadConsumer.classesTaskName))
    archiveClassifier.set("released-resumable-upload-consumer")
    destinationDirectory.set(layout.buildDirectory.dir("binary-compatibility"))
    from(releasedResumableUploadConsumer.output) {
        include("ai/icen/fw/application/upload/compatibility/**")
    }
}

tasks.withType<Test>().configureEach {
    dependsOn(releasedResumableUploadConsumerJar)
    classpath = classpath.plus(files(releasedResumableUploadConsumerJar.flatMap { it.archiveFile }))
}
